package com.shihab.diplay

import com.shihab.diplay.orchestration.HostRetryState

internal class WirelessStartupRetryBudget {
    private val state = HostRetryState()
    val retries: Int get() = state.startupRetries
    val attempts: Int get() = state.attempts
    val stableSessionMillis: Long get() = state.stableSessionMillis
    private var frameSession: Any? = null
    private var firstFrameMillis = 0L

    fun schedule(startup: Boolean, tunnel: Boolean = false): Long? = state.schedule(startup, tunnel)
    fun sessionActive() = state.resetAttempts()
    fun resetAttempts() = state.resetAttempts()

    fun firstFrame(session: Any, nowMillis: Long): Boolean {
        if (frameSession === session) return false
        frameSession = session
        firstFrameMillis = nowMillis
        return true
    }

    fun resetIfStable(session: Any, nowMillis: Long): Boolean {
        if (frameSession !== session || !state.stable(firstFrameMillis, nowMillis)) return false
        state.resetStartup()
        return true
    }

    fun disconnected() { frameSession = null }
    fun manualRetry() { state.resetStartup(); disconnected() }
}
