package com.shilapi.xcertplay.media

/**
 * Smooth video's display delay over a frame's local time ([FramePacer.localTime]).
 *
 * For each frame the decoder releases, the delay it needed is how long after its local time it came out.
 * The delay follows the [percentile] of the last [window] such needs plus [marginNanos], so about that
 * share of frames is ready before its display time; the rest are shown at once. The margin is a refresh
 * plus 4 ms because SurfaceFlinger takes a buffer about one refresh before the vsync it is shown at: on
 * this head unit, frames released less than about 16 ms before that vsync missed it. It starts at
 * [initialNanos] and holds it until [refreshEvery] needs are known. A changed goal is approached by at
 * most [risePerFrame] or [fallPerFrame] per frame, so a few milliseconds move a frame by at most one
 * refresh instead of shifting every later frame at once. It rises faster than it falls: frames that miss
 * their time are already shown unevenly.
 */
internal class PacingDelay(
    initialNanos: Long,
    minNanos: Long = 30_000_000L,
    maxNanos: Long = 200_000_000L,
    window: Int = 120,
    percentile: Int = 90,
    refreshEvery: Int = 15,
    marginNanos: Long = 20_000_000L,
    risePerFrame: Long = 1_000_000L,
    fallPerFrame: Long = 500_000L,
) {
    private val owner = NativeVideoTiming.delayCreate(initialNanos, minNanos, maxNanos, window, percentile, refreshEvery, marginNanos, risePerFrame, fallPerFrame)

    val nanos: Long get() = NativeVideoTiming.delayNanos(owner)

    fun onFrame(neededNanos: Long) = NativeVideoTiming.delayFrame(owner, neededNanos)
}
