package com.shilapi.xcertplay.media

/** Recover a growing input backlog, while allowing delayed bursts and static tail frames to finish. */
internal class VideoBacklogRecovery {
    /** Survives [reset]: only a new decoder moves it. */
    private var startupEndsNs: Long? = null
    private var episodeStartNs: Long? = null
    private var initialSpanNs = 0L
    private var initialPendingFrames = 0
    private var lastObservedNs: Long? = null

    fun reset() {
        episodeStartNs = null
        initialSpanNs = 0L
        initialPendingFrames = 0
        lastObservedNs = null
    }

    /**
     * A new decoder starts behind: the frames that arrived while it was created wait in the queue.
     * Recovery drops the reference chain and waits for a keyframe, and the iPhone does not answer
     * keyframe requests on the transitional stream before the tunnel StartSession (v129 car test,
     * 2026-11-07: recovery 0.63 s after the first frame froze the picture for 8.7 s). Let a new
     * decoder catch up first; a queue overflow still resyncs at once.
     */
    fun decoderStarted(nowNs: Long) {
        reset()
        startupEndsNs = nowNs + STARTUP_GRACE_NS
    }

    fun observe(nowNs: Long, currentReceivedNs: Long, pendingFrames: Int,
        newestPendingReceivedNs: Long?): Boolean {
        startupEndsNs?.let { ends ->
            if (nowNs < ends) {
                reset()
                return false
            }
            startupEndsNs = null
        }
        if (pendingFrames <= 0 || newestPendingReceivedNs == null ||
            currentReceivedNs > nowNs || newestPendingReceivedNs > nowNs ||
            newestPendingReceivedNs < currentReceivedNs ||
            lastObservedNs?.let { nowNs < it } == true) {
            reset()
            return false
        }
        lastObservedNs = nowNs
        val ageNs = nowNs - currentReceivedNs
        val spanNs = newestPendingReceivedNs - currentReceivedNs
        if (ageNs <= LOW_AGE_NS || spanNs <= LOW_SPAN_NS) {
            reset()
            return false
        }
        if (ageNs >= HARD_AGE_NS && spanNs >= SOFT_SPAN_NS) {
            reset()
            return true
        }
        if (ageNs <= SOFT_AGE_NS || spanNs < SOFT_SPAN_NS) return false
        val start = episodeStartNs
        if (start == null) {
            episodeStartNs = nowNs
            initialSpanNs = spanNs
            initialPendingFrames = pendingFrames
            return false
        }
        val growing = spanNs - initialSpanNs >= SPAN_GROWTH_NS ||
            pendingFrames - initialPendingFrames >= FRAME_GROWTH
        if (nowNs - start >= GRACE_NS && growing) {
            reset()
            return true
        }
        return false
    }

    private companion object {
        const val SOFT_AGE_NS = 250_000_000L
        const val SOFT_SPAN_NS = 100_000_000L
        const val LOW_AGE_NS = 150_000_000L
        const val LOW_SPAN_NS = 50_000_000L
        const val HARD_AGE_NS = 1_500_000_000L
        const val GRACE_NS = 750_000_000L
        const val SPAN_GROWTH_NS = 100_000_000L
        const val FRAME_GROWTH = 4
        const val STARTUP_GRACE_NS = 3_000_000_000L
    }
}
