package com.shihab.diplay.media

/** A keyframe that waited 1.5 s with a newer frame 100 ms later behind it is not worth decoding. */
internal object VideoRecoveryFrameAge {
    fun isObsolete(nowNs: Long, receivedNs: Long, pendingFrames: Int, newestPendingReceivedNs: Long?): Boolean =
        nowNs >= receivedNs && nowNs - receivedNs >= 1_500_000_000 && pendingFrames > 0 &&
            newestPendingReceivedNs != null && newestPendingReceivedNs <= nowNs &&
            newestPendingReceivedNs - receivedNs >= 100_000_000
}

internal const val VIDEO_DRAIN_POLL_MS = 5L
internal const val VIDEO_IDLE_POLL_MS = 50L
internal const val VIDEO_STILL_GAP_NS = 500_000_000L

/**
 * The decoder worker's wait for its next job. When it drains a codec's output itself ([drainsOutput]: a
 * codec and no output thread) it polls every [VIDEO_DRAIN_POLL_MS] while the codec may still emit a
 * frame. The codec holds the last frames of a burst until later input arrives, so once input has
 * paused for a still-screen gap nothing comes out until the next frame, and that frame wakes the
 * queue at once. Otherwise only due checks (hardware probe, keyframe request, stats) run, every
 * [VIDEO_IDLE_POLL_MS].
 */
internal fun videoWorkerWaitMillis(drainsOutput: Boolean, lastQueuedNanos: Long, nowNanos: Long): Long =
    NativeVideoPolicy.workerWait(drainsOutput, lastQueuedNanos, nowNanos)
