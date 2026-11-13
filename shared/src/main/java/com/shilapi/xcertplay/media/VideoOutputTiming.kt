package com.shilapi.xcertplay.media

/** Presentation slots, pause history and adaptive output delay are one GC-managed Rust owner. */
internal class VideoOutputTiming(initialDelayNanos: Long) {
    private val owner = NativeVideoTiming.outputCreate(initialDelayNanos)
    val pacingEnabled: Boolean get() = NativeVideoTiming.outputValue(owner, 1) != 0L
    val lastQueuedNanos: Long get() = NativeVideoTiming.outputValue(owner, 0)
    fun queued(presentationMicros: Long, localNanos: Long, queuedNanos: Long) =
        NativeVideoTiming.outputQueued(owner, presentationMicros, localNanos, queuedNanos)
    data class Plan(val latencyNanos: Long, val latencyKnown: Boolean, val heldOverGap: Boolean,
        val pacingDelayNanos: Long?, val renderAtNanos: Long?, val late: Boolean)
    fun plan(presentationMicros: Long, nowNanos: Long, shown: Boolean): Plan {
        val values = NativeVideoTiming.outputPlan(owner, presentationMicros, nowNanos, shown)
        val flags = values[1].toInt()
        return Plan(values[0], flags and 1 != 0, flags and 2 != 0,
            if (flags and 4 != 0) values[2] else null, if (flags and 8 != 0) values[3] else null, flags and 16 != 0)
    }
}

/** One session's actual request timestamps, including forced refreshes and native-pipe events. */
internal class VideoKeyFrameRequests {
    private val owner = NativeVideoFlow.keyframeCreate()
    fun record(nowNanos: Long) { NativeVideoFlow.keyframeRequest(owner, nowNanos, true) }
    fun requestIfDue(nowNanos: Long): Boolean = NativeVideoFlow.keyframeRequest(owner, nowNanos, false)
}
