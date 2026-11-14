package com.shihab.diplay.media

import com.shihab.diplay.airplay.NativeMedia
import java.io.Closeable

/** The renderer worker owns this Rust output schedule; platform play/pause/writes stay in Kotlin. */
internal class AudioBufferProgress(frameBytes: Int) : Closeable {
    private var handle = NativeAudioScheduler.create(frameBytes).also { check(it != 0L) }
    val playing: Boolean get() = NativeAudioScheduler.outputState(handle, 0) != 0L
    val prebufferBytes: Int get() = NativeAudioScheduler.outputState(handle, 1).toInt()
    val startBytes: Int get() = NativeAudioScheduler.outputState(handle, 2).toInt()

    fun configure(planned: Int, capacity: Int, chunk: Int) = NativeAudioScheduler.configure(handle, planned, capacity, chunk)
    fun writeLimit(remaining: Int, chunk: Int): Int = NativeAudioScheduler.writeLimit(handle, remaining, chunk)
    fun completedWrite(bytes: Int, nowNanos: Long): Boolean = NativeAudioScheduler.completedWrite(handle, bytes, nowNanos)
    fun playback(playing: Boolean) = NativeAudioScheduler.playback(handle, playing)
    fun retainAfterPause(head: Int, nowNanos: Long) = NativeAudioScheduler.retainAfterPause(handle, head, nowNanos)
    fun needsRebuffer(media: Boolean, underrun: Boolean, empty: Boolean, head: Int): Boolean =
        NativeAudioScheduler.needsRebuffer(handle, media, underrun, empty, head)
    fun waitingForTail(empty: Boolean): Boolean = NativeAudioScheduler.waitingForTail(handle, empty)
    fun tailReady(nowNanos: Long, waitNanos: Long): Boolean = NativeAudioScheduler.tailReady(handle, nowNanos, waitNanos)
    fun written(bytes: Int) = NativeAudioScheduler.written(handle, bytes)
    fun queuedBytes(rawHead: Int): Long = NativeAudioScheduler.queuedBytes(handle, rawHead)
    fun shouldRebuffer(isMedia: Boolean, playing: Boolean, underrunSinceStart: Boolean,
        compressedQueueEmpty: Boolean, rawHead: Int, floorBytes: Long): Boolean =
        NativeAudioScheduler.shouldRebuffer(handle, isMedia, playing, underrunSinceStart, compressedQueueEmpty, rawHead, floorBytes)
    fun reset() = NativeAudioScheduler.reset(handle)
    override fun close() {
        if (handle != 0L) NativeAudioScheduler.destroy(handle)
        handle = 0L
    }
}

internal object NativeAudioScheduler {
    init { check(NativeMedia.available) { "Rust core is unavailable: ${NativeMedia.loadError}" } }
    @JvmStatic external fun configure(handle: Long, planned: Int, capacity: Int, chunk: Int)
    @JvmStatic external fun outputState(handle: Long, field: Int): Long
    @JvmStatic external fun writeLimit(handle: Long, remaining: Int, chunk: Int): Int
    @JvmStatic external fun completedWrite(handle: Long, bytes: Int, nowNanos: Long): Boolean
    @JvmStatic external fun playback(handle: Long, playing: Boolean)
    @JvmStatic external fun retainAfterPause(handle: Long, head: Int, nowNanos: Long)
    @JvmStatic external fun needsRebuffer(handle: Long, media: Boolean, underrun: Boolean, empty: Boolean, head: Int): Boolean
    @JvmStatic external fun waitingForTail(handle: Long, empty: Boolean): Boolean
    @JvmStatic external fun tailReady(handle: Long, nowNanos: Long, waitNanos: Long): Boolean
    @JvmStatic external fun writeDisposition(count: Int, nativeOutput: Boolean): Int
    @JvmStatic external fun sanitizeDelay(millis: Int, mode: Int): Int
    @JvmStatic external fun bufferPlan(media: Boolean, rate: Int, channels: Int, minimum: Int, millis: Int): IntArray
    @JvmStatic external fun navigationRoute(follows: Boolean, configured: Int, ducking: Boolean): Int
    @JvmStatic external fun captureRecovery(state: LongArray, event: Int, nowNanos: Long, timeoutMillis: Long): Int
    @JvmStatic external fun promptFocus(state: LongArray, event: Int, enabled: Boolean, held: Boolean): Int
    /** One audio-focus event; the decision slots are documented on the Rust `focus_step`. */
    @JvmStatic external fun focusStep(state: LongArray, event: Int, channel: Int, enabled: Boolean, muteTransient: Boolean): IntArray
    @JvmStatic external fun mediaFocusHeld(state: LongArray): Boolean
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
