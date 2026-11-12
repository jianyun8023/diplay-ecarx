package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayListenerIdentity
import com.shilapi.xcertplay.airplay.AirPlayTcpAccepted
import com.shilapi.xcertplay.network.WirelessStartupPolicy

internal class FirstTcpWatchdog(
    val listener: AirPlayListenerIdentity,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onTimeout: () -> Unit,
    private val nowNanos: () -> Long = System::nanoTime,
    private val timeoutMillis: Long = WirelessStartupPolicy.FIRST_TCP_MILLIS,
    private val log: (String) -> Unit = {},
) {
    private val core = StartupWatchdogCore(1, timeout = timeoutMillis)
    val terminated: Boolean get() = core.finished
    private var cancelTimer: (() -> Unit)? = null

    @Synchronized fun startSessionSent(sentAtNanos: Long) {
        val start = core.event(8, time = sentAtNanos)
        if (!start.has(StartupWatchdogCore.PROGRESS)) return
        log("StartSession atNs=$sentAtNanos")
        val arm = core.event(9)
        if (!arm.has(StartupWatchdogCore.ARM)) return
        val remaining = StartupWatchdogCore.remaining(arm.delay, arm.baseNanos, nowNanos())
        cancelTimer = schedule(remaining, ::expire)
    }

    @Synchronized fun accepted(event: AirPlayTcpAccepted): Boolean {
        val result = core.event(10, time = event.acceptedAtNanos,
            condition = event.listener === listener && !event.internal)
        if (!result.has(StartupWatchdogCore.ACCEPTED)) return false
        if (result.has(StartupWatchdogCore.FIRST_ACCEPT)) log("first TCP atNs=${event.acceptedAtNanos}")
        cancel()
        return true
    }

    @Synchronized fun sessionEstablished(): Boolean {
        val start = core.event(11)
        if (!start.has(StartupWatchdogCore.ACCEPTED)) return false
        if (start.has(StartupWatchdogCore.LOG_SUCCESS)) log("session established atNs=${nowNanos()}")
        core.event(12)
        cancel()
        return true
    }

    @Synchronized fun terminate(): Boolean {
        val result = core.event(3)
        if (!result.has(StartupWatchdogCore.ACCEPTED)) return false
        cancel()
        return true
    }

    private fun expire() {
        synchronized(this) {
            val result = core.event(13)
            if (!result.has(StartupWatchdogCore.TIMEOUT)) return
            cancelTimer = null
            log("first TCP timeout atNs=${nowNanos()} startNs=${result.baseNanos}")
        }
        onTimeout()
    }

    private fun cancel() { cancelTimer?.invoke(); cancelTimer = null }

}
