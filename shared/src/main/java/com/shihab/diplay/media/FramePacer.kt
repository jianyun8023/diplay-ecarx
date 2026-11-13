package com.shihab.diplay.media

/**
 * Turns the iPhone's per-frame time into a local time on System.nanoTime, so frames can be shown at an
 * even pace instead of whenever the link and the decoder hand them over. The decoder adds the display
 * delay ([PacingDelay]) on top.
 *
 * The offset between the iPhone's clock and System.nanoTime is a low percentile of
 * (arrival - sender time) over recent frames: the fastest frames show the link's base delay, and late
 * frames are absorbed by the display delay added on top. For the first [refreshEvery] frames the offset
 * follows that percentile at once, so a slow first frame (usually a keyframe) does not set it. After
 * that it moves at most [slewPerSecond] of the elapsed time, so a fast sample leaving the window does
 * not shift every later frame at once. It re-anchors at once when the base delay grows by more than
 * [resetNanos] or shrinks by more than [maxEarlyNanos], and a frame's local time is never more than
 * [maxEarlyNanos] after it arrived.
 */
internal class FramePacer(
    window: Int = 240,
    percentile: Int = 5,
    refreshEvery: Int = 30,
    slewPerSecond: Double = 0.002,
    resetNanos: Long = 500_000_000L,
    maxEarlyNanos: Long = 100_000_000L,
) {
    private val owner = NativeVideoTiming.pacerCreate(window, percentile, refreshEvery, slewPerSecond, resetNanos, maxEarlyNanos)

    fun frameTime(enabled: Boolean, senderNanos: Long, arrivalNanos: Long): Long =
        NativeVideoTiming.pacerFrame(owner, enabled, senderNanos, arrivalNanos)

    fun localTime(senderNanos: Long, arrivalNanos: Long): Long =
        NativeVideoTiming.pacerLocal(owner, senderNanos, arrivalNanos)
}

/** Pure shared timing owners; values are GC-managed and never contain native handles. */
internal object NativeVideoTiming {
    init { com.shihab.diplay.airplay.NativeMedia.requireAvailable() }
    @JvmStatic external fun pacerCreate(window: Int, percentile: Int, refreshEvery: Int, slew: Double, resetNanos: Long, maxEarlyNanos: Long): LongArray
    @JvmStatic external fun pacerFrame(owner: LongArray, enabled: Boolean, sender: Long, arrival: Long): Long
    @JvmStatic external fun outputCreate(initialDelay: Long): LongArray
    @JvmStatic external fun outputQueued(owner: LongArray, presentation: Long, local: Long, now: Long)
    @JvmStatic external fun outputPlan(owner: LongArray, presentation: Long, now: Long, shown: Boolean): LongArray
    @JvmStatic external fun outputValue(owner: LongArray, field: Int): Long
    @JvmStatic external fun pacerLocal(owner: LongArray, senderNanos: Long, arrivalNanos: Long): Long
    @JvmStatic external fun delayCreate(initial: Long, min: Long, max: Long, window: Int, percentile: Int, refreshEvery: Int, margin: Long, rise: Long, fall: Long): LongArray
    @JvmStatic external fun delayFrame(owner: LongArray, neededNanos: Long)
    @JvmStatic external fun delayNanos(owner: LongArray): Long
    @JvmStatic external fun backlogCreate(): LongArray
    @JvmStatic external fun backlogEvent(owner: LongArray, event: Int, nowNanos: Long, receivedNanos: Long, pending: Int, newestNanos: Long, hasNewest: Boolean): Boolean
}
