package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.NativeMedia
import java.io.Closeable

/**
 * Tracks the unsigned AudioTrack/AAudio head across wrap without flushing already queued sound, and
 * decides the music rebuffer. Rust owns the accounting and the rebuffer rule; Kotlin still performs
 * the platform writes. The Kotlin copy below runs only when the native library cannot load.
 */
internal class AudioBufferProgress(private val frameBytes: Int) : Closeable {
    private val fallback = if (NativeMedia.available) null else KotlinAudioBufferProgress(frameBytes)
    private var handle = if (fallback == null) NativeAudioScheduler.create(frameBytes).also { check(it != 0L) } else 0L

    fun written(bytes: Int) {
        val kotlin = fallback
        if (kotlin != null) kotlin.written(bytes) else NativeAudioScheduler.written(handle, bytes)
    }

    fun queuedBytes(rawHead: Int): Long =
        fallback?.queuedBytes(rawHead) ?: NativeAudioScheduler.queuedBytes(handle, rawHead)

    fun shouldRebuffer(
        isMedia: Boolean, playing: Boolean, underrunSinceStart: Boolean,
        compressedQueueEmpty: Boolean, rawHead: Int, floorBytes: Long,
    ): Boolean = fallback?.shouldRebuffer(isMedia, playing, underrunSinceStart, compressedQueueEmpty, rawHead, floorBytes)
        ?: NativeAudioScheduler.shouldRebuffer(handle, isMedia, playing, underrunSinceStart, compressedQueueEmpty, rawHead, floorBytes)

    /** A freshly built output starts from zero without releasing the native owner. */
    fun reset() {
        val kotlin = fallback
        if (kotlin != null) kotlin.reset() else NativeAudioScheduler.reset(handle)
    }

    override fun close() {
        if (handle != 0L) NativeAudioScheduler.destroy(handle)
        handle = 0L
    }
}

/** Library-unavailable fallback; the native path never creates this state. */
internal class KotlinAudioBufferProgress(private val frameBytes: Int) {
    private var writtenBytes = 0L
    private var playedFrames = 0L
    private var lastHead = 0L

    fun written(bytes: Int) {
        writtenBytes += bytes
    }

    fun queuedBytes(rawHead: Int): Long {
        val head = rawHead.toLong() and 0xffff_ffffL
        playedFrames += (head - lastHead) and 0xffff_ffffL
        lastHead = head
        return (writtenBytes - playedFrames * frameBytes).coerceAtLeast(0)
    }

    fun shouldRebuffer(
        isMedia: Boolean, playing: Boolean, underrunSinceStart: Boolean,
        compressedQueueEmpty: Boolean, rawHead: Int, floorBytes: Long,
    ): Boolean = isMedia && playing && underrunSinceStart && compressedQueueEmpty &&
        queuedBytes(rawHead) <= floorBytes

    fun reset() {
        writtenBytes = 0
        playedFrames = 0
        lastHead = 0
    }
}

internal object NativeAudioScheduler {
    @JvmStatic external fun create(frameBytes: Int): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun reset(handle: Long)
    @JvmStatic external fun written(handle: Long, bytes: Int)
    @JvmStatic external fun queuedBytes(handle: Long, rawHead: Int): Long
    @JvmStatic external fun shouldRebuffer(
        handle: Long, isMedia: Boolean, playing: Boolean, underrunSinceStart: Boolean,
        compressedQueueEmpty: Boolean, rawHead: Int, floorBytes: Long,
    ): Boolean
    @JvmStatic external fun startBytesFor(plannedStartBytes: Int, actualCapacityBytes: Int, writeChunkBytes: Int): Int
}
