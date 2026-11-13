package com.shihab.diplay.airplay

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class VideoCodec { H264, H265 }

/**
 * Receives one CarPlay screen stream on a TCP data port.
 *
 * Each message is a 128-byte AirPlayScreenHeader followed by a body: a clear VideoConfig
 * (avcC/hvcC) or a ChaCha20-Poly1305 sealed VideoFrame. The key is the DataStream output key
 * and the per-frame nonce is an 8-byte little-endian counter.
 *
 * Rust owns framing, decryption and pooled Annex B packets; this adapter owns the Android socket.
 */
class ScreenStream(
    private val key: ByteArray,
    private val expectedPeer: InetAddress,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    interface Listener {
        fun onCodec(codec: VideoCodec) {}
        fun onConfig(codecData: ByteArray) {}
        fun onNativeVideoRoute(route: NativeVideoRoute) {}
        fun onReceiving() {}
        fun onFrame(naluBytes: ByteArray) {}
        /**
         * A frame with the iPhone's frame time ([senderNanos], from the header) and when its body finished
         * arriving ([arrivalNanos], System.nanoTime). Listeners that do not pace frames ignore both.
         */
        fun onFrame(naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) = onFrame(naluBytes)
        /** A frame the listener now owns: it releases [frame] once done with it. */
        fun onFrame(frame: MediaPacket, senderNanos: Long, arrivalNanos: Long) {
            try {
                onFrame(frame.toByteArray(), senderNanos, arrivalNanos)
            } finally {
                frame.release()
            }
        }
        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var thread: Thread? = null
    private val nativeHandle = AtomicLong(0)
    @Volatile private var nativeRoute: NativeVideoRoute? = null
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        NativeMedia.requireAvailable()
        this.listener = listener
        val bound = ServerSocket()
        bound.reuseAddress = true
        bound.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        server = bound
        thread = Thread({ accept(bound) }, "airplay-screen").apply { isDaemon = true; start() }
        return bound.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        safeClose(socket)
        safeClose(server)
        stopNative()
        thread?.interrupt()
    }

    private fun stopNative() {
        nativeRoute?.close()
        val handle = nativeHandle.getAndSet(0)
        if (handle != 0L) NativeMedia.stop(handle)
    }

    private fun accept(bound: ServerSocket) {
        try {
            val accepted = bound.acceptFrom(expectedPeer) { onDiagnostic("Video: rejected sender outside the control session") }
            socket = accepted
            runNative(accepted)
        } catch (error: Exception) {
            safeClose(socket)
            if (!closed.get()) listener.onClosed(error)
        }
    }

    /** Transfers the duplicated descriptor only after native startup succeeds. */
    private fun runNative(sock: Socket) {
        val fd = ParcelFileDescriptor.fromSocket(sock).detachFd()
        val callbacks = object : NativeStreamCallbacks() {
            override fun onPacket(handle: Long, size: Int, flags: Int, a: Long, b: Long) {
                val frame = NativePacket(handle, size, flags)
                if (closed.get()) frame.release() else listener.onFrame(frame, a, b)
            }

            override fun onConfig(bytes: ByteArray) = onConfigBody(bytes)
            override fun onReceiving() { if (!closed.get()) listener.onReceiving() }

            override fun onLog(level: Int, line: String) {
                if (level == 0) Log.i(TAG, line) else Log.w(TAG, line)
            }

            override fun onReport(line: String) = onDiagnostic(line)

            override fun onEnded(error: String?) {
                stopNative()
                if (socket === sock) socket = null
                safeClose(sock)
                if (!closed.get()) listener.onClosed(error?.let(::IOException))
            }
        }
        var route: NativeVideoRoute? = null
        var transferred = false
        try {
            route = NativeVideoRoute.create()
            nativeRoute = route
            if (route != null) listener.onNativeVideoRoute(route)
            val handle = if (route != null) route.start(fd, key, callbacks) else NativeMedia.startVideo(fd, key, callbacks)
            if (handle == 0L) throw IOException("Native screen receiver could not start")
            transferred = true
            nativeHandle.set(handle)
            if (closed.get()) stopNative()
        } finally {
            if (!transferred) {
                route?.close()
                ParcelFileDescriptor.adoptFd(fd).close()
            }
        }
    }

    private fun onConfigBody(bytes: ByteArray) {
        val (codec, codecData) = ScreenCodec.detectConfig(bytes)
        Log.i(TAG, "video codec config codec=$codec body=${bytes.size} data=${codecData.size}")
        listener.onCodec(codec)
        listener.onConfig(codecData)
    }

    private companion object {
        const val TAG = "diplay-usb"
    }
}
