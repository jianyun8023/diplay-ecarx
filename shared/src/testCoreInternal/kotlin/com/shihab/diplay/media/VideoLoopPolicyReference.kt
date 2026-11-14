package com.shihab.diplay.media

/** Use the existing hard backlog age only when a newer frame remains in this control segment. */
internal object VideoRecoveryFrameAge {
    fun isObsolete(nowNs: Long, receivedNs: Long, backlog: VideoDecodeQueue.Backlog): Boolean =
        NativeVideoFlow.obsolete(nowNs, receivedNs, backlog.pendingFrames,
            backlog.newestPendingReceivedNs ?: 0, backlog.newestPendingReceivedNs != null)
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
