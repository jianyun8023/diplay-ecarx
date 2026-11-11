package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One decoder's pictures drawn onto several surfaces: the stream's own ([setPrimary]) and its mirrors
 * ([setMirror]). The decoder renders to [input]. Each call returns once the change is applied, so a
 * surface handed back here is no longer drawn to and its producer slot is free for a codec.
 */
internal interface VideoFanout {
    val input: Surface
    fun setPrimary(surface: Surface?)
    fun setMirror(key: String, surface: Surface?)
    /** Stops drawing everywhere and releases [input]; the decoder must no longer render to it. */
    fun close()
}

/**
 * Whether stream [type]'s mirrors share its decoder through a [VideoFanout]. The main screen keeps a decoder per
 * mirror: its own frames are paced by timestamp and shown without the extra GPU step.
 */
internal fun mirrorsShareDecoder(type: Int): Boolean = type != MAIN_SCREEN_TYPE

/**
 * What the fanout thread costs, as one diagnostic line per [intervalNanos] (and at close): frames drawn, the
 * thread's wall time per frame (taking the picture and drawing it onto every target, swaps included), its CPU
 * time as a share of one core, the targets drawn to and the targets dropped because their surface had gone.
 * GPU work that the driver finishes later is not in it.
 */
internal class FanoutStats(private val intervalNanos: Long = 5 * 60 * 1_000_000_000L) {
    private var windowStart = -1L
    private var frames = 0
    private var wallNanos = 0L
    private var maxWallNanos = 0L
    private var cpuNanos = 0L
    private var cpuKnown = true
    private var dropped = 0

    /** [cpuNanos] below 0 means the platform could not tell. Returns a line when a window is complete. */
    fun onFrame(nowNanos: Long, wallNanos: Long, cpuNanos: Long, targets: Int): String? {
        if (windowStart < 0) windowStart = nowNanos - wallNanos
        frames++
        this.wallNanos += wallNanos
        maxWallNanos = maxOf(maxWallNanos, wallNanos)
        if (cpuNanos < 0) cpuKnown = false else this.cpuNanos += cpuNanos
        return if (nowNanos - windowStart >= intervalNanos) line(nowNanos, targets) else null
    }

    fun onTargetDropped() { dropped++ }

    /** The partial window, or null when nothing was drawn in it. */
    fun flush(nowNanos: Long, targets: Int): String? = if (frames == 0) null else line(nowNanos, targets)

    private fun line(nowNanos: Long, targets: Int): String {
        val window = (nowNanos - windowStart).coerceAtLeast(1)
        val text = "video fanout windowS=${window / 1_000_000_000L} frames=$frames " +
            "fps=${oneDecimal(frames * 1_000_000_000.0 / window)} " +
            "drawAvgMs=${oneDecimal(wallNanos / 1_000_000.0 / frames)} drawMaxMs=${oneDecimal(maxWallNanos / 1_000_000.0)} " +
            "cpu=${if (cpuKnown) oneDecimal(cpuNanos * 100.0 / window) + "%" else "unknown"} " +
            "targets=$targets dropped=$dropped"
        windowStart = nowNanos
        frames = 0
        wallNanos = 0
        maxWallNanos = 0
        cpuNanos = 0
        cpuKnown = true
        dropped = 0
        return text
    }

    private fun oneDecimal(value: Double): String = String.format(java.util.Locale.ROOT, "%.1f", value)
}

/**
 * [VideoFanout] with OpenGL ES 2 on a thread of its own: each decoded picture arrives as an external texture
 * and is drawn, stretched as the decoder itself would scale it, onto every target's window surface.
 */
internal class GlVideoFanout(width: Int, height: Int, private val report: (String) -> Unit = {}) : VideoFanout {
    private class Target(val surface: Surface, val egl: EGLSurface)

    private val thread = HandlerThread("carplay-video-fanout").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private lateinit var surfaceTexture: SurfaceTexture
    private val transform = FloatArray(16)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(QUAD); position(0) }
    // GL thread only.
    private var primary: Target? = null
    private val mirrors = LinkedHashMap<String, Target>()
    private var hasPicture = false
    private var closed = false
    private val stats = FanoutStats()
    override val input: Surface

    init {
        var created: Surface? = null
        try {
            runOnGl {
                setUpGl()
                surfaceTexture = SurfaceTexture(texture).apply {
                    setDefaultBufferSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
                    setOnFrameAvailableListener({ drawFrame() }, handler)
                }
                created = Surface(surfaceTexture)
            }
            input = checkNotNull(created) { "video fanout did not start" }
        } catch (error: Throwable) {
            runCatching { runOnGl { releaseGl() } }
            thread.quitSafely()
            throw error
        }
    }

    override fun setPrimary(surface: Surface?) = runOnGl {
        if (primary?.surface === surface) return@runOnGl
        primary?.let(::destroy)
        primary = surface?.let(::target)
        drawCurrent()
    }

    override fun setMirror(key: String, surface: Surface?) = runOnGl {
        if (mirrors[key]?.surface === surface) return@runOnGl
        mirrors.remove(key)?.let(::destroy)
        surface?.let(::target)?.let { mirrors[key] = it }
        drawCurrent()
    }

    override fun close() {
        try {
            runOnGl {
                stats.flush(System.nanoTime(), targetCount())?.let(report)
                closed = true
                releaseGl()
            }
        } finally {
            runCatching { input.release() }
            thread.quitSafely()
        }
    }

    /** Runs [block] on the GL thread and waits for it; a failure there is thrown here. */
    private fun runOnGl(block: () -> Unit) {
        if (Thread.currentThread() === thread) { block(); return }
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        if (!handler.post { try { block() } catch (error: Throwable) { failure = error } finally { done.countDown() } }) {
            throw IllegalStateException("video fanout thread has stopped")
        }
        var interrupted = false
        try {
            while (true) {
                try {
                    if (!done.await(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw IllegalStateException("video fanout call timed out")
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        failure?.let { throw it }
    }

    private fun setUpGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "no EGL config"
        }
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        pbuffer = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent failed" }
        program = linkProgram()
        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        texture = names[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun releaseGl() {
        primary?.let(::destroy)
        primary = null
        mirrors.values.forEach(::destroy)
        mirrors.clear()
        if (::surfaceTexture.isInitialized) runCatching { surfaceTexture.release() }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            // The default display is shared with the rest of the process, so it is not terminated.
            EGL14.eglReleaseThread()
        }
        pbuffer = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    /** A window surface on [surface], or null (reported) when EGL cannot take it, e.g. a surface already gone. */
    private fun target(surface: Surface): Target? {
        if (closed || !surface.isValid) return null
        val egl = runCatching {
            EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        }.getOrNull()
        if (egl == null || egl == EGL14.EGL_NO_SURFACE) {
            report("video fanout could not draw to a surface eglError=0x${Integer.toHexString(EGL14.eglGetError())}")
            return null
        }
        return Target(surface, egl)
    }

    private fun destroy(target: Target) {
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        EGL14.eglDestroySurface(display, target.egl)
    }

    private fun drawFrame() {
        if (closed) return
        val startNanos = System.nanoTime()
        val startCpu = android.os.Debug.threadCpuTimeNanos()
        try {
            surfaceTexture.updateTexImage()
        } catch (error: RuntimeException) {
            Log.w(TAG, "video fanout could not take a picture", error)
            return
        }
        surfaceTexture.getTransformMatrix(transform)
        hasPicture = true
        drawCurrent()
        val endCpu = android.os.Debug.threadCpuTimeNanos()
        val now = System.nanoTime()
        stats.onFrame(now, now - startNanos, if (startCpu < 0 || endCpu < 0) -1 else endCpu - startCpu, targetCount())
            ?.let(report)
    }

    private fun targetCount(): Int = mirrors.size + if (primary != null) 1 else 0

    /** Draws the latest picture onto every target; a target whose surface has gone is dropped. */
    private fun drawCurrent() {
        if (closed || !hasPicture) return
        primary?.let { if (!draw(it)) { destroy(it); primary = null; stats.onTargetDropped() } }
        val gone = mirrors.filterValues { !draw(it) }.keys
        gone.forEach { key -> mirrors.remove(key)?.let(::destroy); stats.onTargetDropped() }
    }

    private fun draw(target: Target): Boolean {
        if (!EGL14.eglMakeCurrent(display, target.egl, target.egl, context)) return false
        // Several windows share this thread: none may wait for another's vsync.
        EGL14.eglSwapInterval(display, 0)
        val size = IntArray(2)
        EGL14.eglQuerySurface(display, target.egl, EGL14.EGL_WIDTH, size, 0)
        EGL14.eglQuerySurface(display, target.egl, EGL14.EGL_HEIGHT, size, 1)
        GLES20.glViewport(0, 0, size[0], size[1])
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTransform"), 1, false, transform, 0)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val coordinate = GLES20.glGetAttribLocation(program, "aCoordinate")
        quad.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(position)
        quad.position(2)
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(coordinate)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        return EGL14.eglSwapBuffers(display, target.egl)
    }

    private fun linkProgram(): Int {
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { "video fanout program: ${GLES20.glGetProgramInfoLog(program)}" }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] != 0) { "video fanout shader: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val CALL_TIMEOUT_MS = 2_000L
        // x, y, s, t for a full-viewport triangle strip.
        val QUAD = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aCoordinate;
            uniform mat4 uTransform;
            varying vec2 vCoordinate;
            void main() {
                gl_Position = aPosition;
                vCoordinate = (uTransform * aCoordinate).xy;
            }
        """
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vCoordinate;
            void main() {
                gl_FragColor = texture2D(uTexture, vCoordinate);
            }
        """
    }
}
