package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.NativeMedia
import java.io.Closeable
import java.nio.ByteBuffer

/** The call's Rust PCM ring and speaker clock, shared by the output worker and microphone. */
internal class EchoReference private constructor(val sampleRate: Int, private var handle: Long) : Closeable {
    constructor(sampleRate: Int, capacityMillis: Int = 2_000) : this(
        sampleRate, NativeEchoReference.create(sampleRate, capacityMillis).also { check(it != 0L) },
    )

    /** Each worker releases its own reference; replacing a renderer does not retire a live microphone. */
    @Synchronized
    fun retain(): EchoReference? = if (handle == 0L) null else EchoReference(sampleRate, NativeEchoReference.retain(handle))

    @Synchronized
    fun append(pcm: ByteArray, offset: Int, length: Int, channels: Int, pendingFrames: Long?, nowNs: Long) {
        if (handle != 0L) NativeEchoReference.append(handle, pcm, offset, length, channels, pendingFrames != null, pendingFrames ?: 0L, nowNs)
    }

    /** Reads without moving the SDK buffer's position. */
    @Synchronized
    fun append(pcm: ByteBuffer, offset: Int, length: Int, channels: Int, pendingFrames: Long?, nowNs: Long) {
        if (handle == 0L) return
        if (offset < 0 || length < 0 || offset > pcm.limit() || length > pcm.limit() - offset) throw IndexOutOfBoundsException()
        if (pcm.isDirect) NativeEchoReference.appendBuffer(handle, pcm, offset, length, channels, pendingFrames != null, pendingFrames ?: 0L, nowNs)
        else {
            val bytes = ByteArray(length)
            pcm.duplicate().apply { position(offset); get(bytes) }
            NativeEchoReference.append(handle, bytes, 0, length, channels, pendingFrames != null, pendingFrames ?: 0L, nowNs)
        }
    }

    @Synchronized
    fun reset() { if (handle != 0L) NativeEchoReference.reset(handle) }

    @Synchronized
    fun read(out: ShortArray, endNs: Long): Boolean {
        if (handle != 0L) return NativeEchoReference.read(handle, out, endNs)
        out.fill(0)
        return false
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) NativeEchoReference.destroy(handle)
        handle = 0L
    }
}

internal object NativeEchoReference {
    init { NativeMedia.requireAvailable() }
    @JvmStatic external fun create(rate: Int, capacityMillis: Int): Long
    @JvmStatic external fun retain(handle: Long): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun append(handle: Long, pcm: ByteArray, offset: Int, length: Int, channels: Int, playing: Boolean, pendingFrames: Long, nowNs: Long)
    @JvmStatic external fun appendBuffer(handle: Long, pcm: ByteBuffer, offset: Int, length: Int, channels: Int, playing: Boolean, pendingFrames: Long, nowNs: Long)
    @JvmStatic external fun reset(handle: Long)
    @JvmStatic external fun read(handle: Long, out: ShortArray, endNs: Long): Boolean
    @JvmStatic external fun captureRead(owner: LongArray, rate: Int, samples: Int, nowNs: Long, stamped: Boolean, timestampNs: Long, framePosition: Long)
    @JvmStatic external fun captureEnd(owner: LongArray, rate: Int, samplesIntoRead: Int): Long
}
