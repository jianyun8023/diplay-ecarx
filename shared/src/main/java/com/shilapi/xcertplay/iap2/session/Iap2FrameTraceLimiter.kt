package com.shilapi.xcertplay.iap2.session

import com.shilapi.xcertplay.iap2.trace.Iap2TraceDirection
import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded per-session trace decisions, keyed by endpoint AND direction.
 * The operation is atomic so a heartbeat's suppressed count cannot lose concurrent frames.
 */
internal class Iap2FrameTraceLimiter(
    private val initialFrames: Long = 3,
    private val heartbeatNanos: Long = 60_000_000_000L,
) {
    data class Decision(val detail: Boolean, val suppressed: Long)

    private data class EndpointKey(val messageId: Int, val direction: Iap2TraceDirection)

    private class State {
        var seen: Long = 0
        var lastLoggedNs: Long? = null
        var suppressed: Long = 0
    }

    private val states = ConcurrentHashMap<EndpointKey, State>()

    fun select(
        direction: Iap2TraceDirection,
        messageId: Int,
        nowNs: Long,
        detailed: Boolean,
    ): Decision? {
        var decision: Decision? = null
        states.compute(EndpointKey(messageId, direction)) { _, existing ->
            val state = existing ?: State()
            state.seen++
            val heartbeatDue = state.lastLoggedNs?.let { nowNs - it >= heartbeatNanos } == true
            if (detailed || state.seen <= initialFrames || heartbeatDue) {
                // Explicit DEBUG emits all frames. No suppressed count is carried across
                // detailed tracing because those frames were not suppressed.
                decision = Decision(detail = detailed, suppressed = if (detailed) 0 else state.suppressed)
                state.suppressed = 0
                state.lastLoggedNs = nowNs
            } else {
                state.suppressed++
            }
            state
        }
        return decision
    }
}
