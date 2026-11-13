package com.shihab.diplay.media

import com.shihab.diplay.airplay.HeapPacket
import com.shihab.diplay.airplay.AudioFormat
import java.io.Closeable
import java.nio.ByteBuffer

/** Runs renderer-worker entry points synchronously, with the real Rust PCM/schedule/queue owners. */
internal class RendererNativeFixture(private val renderer: Any) : Closeable {
    private val type = renderer.javaClass
    val progress = type.getDeclaredMethod("getBufferProgress").apply { isAccessible = true }
        .invoke(renderer) as AudioBufferProgress
    val queue = type.getDeclaredField("nativeQueue").apply { isAccessible = true }
        .get(renderer) as NativeAudioQueue

    init {
        val format = type.getDeclaredField("format").apply { isAccessible = true }.get(renderer) as AudioFormat
        val channels = type.getDeclaredField("pcmChannels").apply { isAccessible = true }.getInt(renderer)
        type.getDeclaredField("nativePcm").apply { isAccessible = true }
            .set(renderer, NativePcm.create(format.sampleRate, channels, voiceFilter = false, opus = false))
    }

    fun write(bytes: Int) {
        type.getDeclaredMethod("writePcm", ByteBuffer::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(renderer, ByteBuffer.allocateDirect(bytes), bytes)
    }

    fun write(bytes: ByteArray) {
        val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes)
        type.getDeclaredMethod("writePcm", ByteBuffer::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(renderer, buffer, bytes.size)
        buffer.rewind()
        buffer.get(bytes)
    }

    fun drain() {
        checkNotNull(queue.consumer()).use { reader ->
            val metadata = LongArray(3)
            while (true) (reader.poll(metadata, 0) ?: break).release()
        }
    }

    fun fill() {
        // Same RTP timestamp: test bounded capacity without triggering stale-media recovery.
        repeat(192 - queue.size) { check(queue.push(HeapPacket(ByteArray(112)), 0, System.nanoTime()) and 1 != 0) }
    }

    override fun close() {
        // These fixtures never launch the worker; run its actual finally cleanup synchronously.
        (renderer as Closeable).close()
        type.getDeclaredMethod("release").apply { isAccessible = true }.invoke(renderer)
    }
}
