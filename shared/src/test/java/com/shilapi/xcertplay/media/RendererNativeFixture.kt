package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.HeapPacket
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
        type.getDeclaredField("nativePcm").apply { isAccessible = true }
            .set(renderer, NativePcm.create(48_000, 2, voiceFilter = false, opus = false))
    }

    fun write(bytes: Int) {
        type.getDeclaredMethod("writePcm", ByteBuffer::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(renderer, ByteBuffer.allocateDirect(bytes), bytes)
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
