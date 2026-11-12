package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2BootstrapPhase

/**
 * A progress watchdog for the wireless iAP2 bootstrap handshake.
 *
 * The bootstrap has no single meaningful budget: bringing up RFCOMM, negotiating the iAP2 CSM,
 * authenticating over MFi and the phone-driven transport exchange happen at very different speeds,
 * and a stall in each one means something different. Instead of one global timeout, every
 * checkpoint gets its own limit measured from the previous one, so the limit that fires names the
 * phase that actually stalled:
 *
 *  - [Iap2BootstrapPhase.RFCOMM_CONNECTED] without CSM  → the Bluetooth link never became iAP2.
 *  - [Iap2BootstrapPhase.CSM_ESTABLISHED] without MFi   → the phone never answered identification/MFi.
 *  - [Iap2BootstrapPhase.MFi_AUTHENTICATED] or a later checkpoint without progress → the phone
 *    authenticated but stopped driving the handshake.
 *
 * Once [Iap2BootstrapPhase.START_SESSION_SENT] is out, AirPlay TCP and tunnel readiness are watched
 * by [FirstTcpWatchdog] and the wireless handoff watchdog; this watchdog stops arming timers there
 * so it never races them, and [Iap2BootstrapPhase.TUNNEL_ESTABLISHED] simply ends it.
 *
 * [schedule] and [nowNanos] are injected so the timing logic is unit-testable without a thread.
 */
internal class Iap2BootstrapWatchdog(
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onTimeout: (Iap2BootstrapStall) -> Unit,
    private val nowNanos: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = {},
) {
    private val core = StartupWatchdogCore(2, now = nowNanos())
    val finished: Boolean get() = core.finished
    private var cancelTimer: (() -> Unit)? = null

    @Synchronized fun mark(phase: Iap2BootstrapPhase, atNanos: Long = nowNanos()) {
        val progress = core.event(6, phase.ordinal, atNanos)
        if (progress.has(StartupWatchdogCore.DUPLICATE)) {
            log("bootstrap phase=${phase.name} already at=${Iap2BootstrapPhase.entries[progress.reached].name} ignored")
            return
        }
        if (!progress.has(StartupWatchdogCore.PROGRESS)) return
        log("bootstrap phase=${phase.name} sincePreviousMs=${progress.elapsed}")
        // The log is a platform callback: decide the timer after it, matching the original order.
        val timer = core.event(14, phase.ordinal)
        if (timer.has(StartupWatchdogCore.CANCEL)) cancel()
        if (timer.has(StartupWatchdogCore.ARM)) {
            val generation = timer.generation
            cancelTimer = schedule(timer.delay) { expire(generation) }
        }
    }

    @Synchronized fun terminate(): Boolean {
        val result = core.event(3)
        if (result.has(StartupWatchdogCore.CANCEL)) cancel()
        return result.has(StartupWatchdogCore.ACCEPTED)
    }

    private fun expire(armedGeneration: Int) {
        val stall = synchronized(this) {
            val result = core.event(7, armedGeneration)
            if (!result.has(StartupWatchdogCore.TIMEOUT)) return
            cancelTimer = null
            // Rust committed finished before reading the injected clock, including reentrant clocks.
            Iap2BootstrapStall(
                Iap2BootstrapPhase.entries[result.reached], Iap2BootstrapPhase.entries[result.missing],
                StartupWatchdogCore.elapsed(result.baseNanos, nowNanos()),
            )
        }
        onTimeout(stall)
    }

    private fun cancel() { cancelTimer?.invoke(); cancelTimer = null }

}

/**
 * Per-checkpoint limits for the wireless iAP2 bootstrap, measured from the previous checkpoint.
 *
 * The budgets are deliberately generous: a healthy handshake usually reaches every checkpoint in
 * well under a second, but CSM bring-up has been measured as late as 34.6 s on the real car, so the
 * limits must cover the long tail and only fire on a genuinely stuck phone while still ending the
 * attempt long before the five-minute control-loop budget would.
 */
internal object Iap2BootstrapPolicy {
    /**
     * RFCOMM connect to a live iAP2 peer plus CSM negotiation. The 45 s budget covers the 34.6 s
     * long-tail sample with margin, so a slow but healthy link is not mistaken for a dead one.
     */
    const val RFCOMM_TO_CSM_MILLIS = 45_000L

    /** Identification exchange plus MFi authentication. */
    const val CSM_TO_MFI_MILLIS = 30_000L

    /**
     * Authenticated phone → any further bootstrap progress. Held just above
     * [Iap2WirelessControlClient]'s 60 s transport wait, which already names the "answered nothing"
     * case, so this only fires when that wait is bypassed by partial progress.
     */
    const val MFI_TO_PROGRESS_MILLIS = 65_000L

    /** Partial progress (availability / transport identifier) then silence before StartSession. */
    const val PARTIAL_PROGRESS_MILLIS = 45_000L

    fun budgetToReach(missing: Iap2BootstrapPhase): Long = StartupWatchdogCore.budget(missing.ordinal)
}

/** The checkpoint the bootstrap stalled at, and what the driver-facing cause should be. */
internal data class Iap2BootstrapStall(
    val reached: Iap2BootstrapPhase,
    val missing: Iap2BootstrapPhase,
    val waitedMillis: Long,
) {
    val cause: ConnectionFailureCause
        get() = if (StartupWatchdogCore.bluetoothStall(reached.ordinal)) ConnectionFailureCause.WIRELESS_BLUETOOTH_LINK_FAILED
            else ConnectionFailureCause.WIRELESS_PHONE_NOT_RESPONDING

    fun message(): String = when (reached) {
        Iap2BootstrapPhase.RFCOMM_CONNECTED ->
            "The Bluetooth link connected but the iPhone never opened an iAP2 control session; " +
                "waited ${waitedMillis}ms for CSM after RFCOMM connected"
        else ->
            "The iPhone stopped responding during the wireless iAP2 handshake after " +
                "${reached.name}; waited ${waitedMillis}ms for ${missing.name}"
    }

    fun logLine(): String = "bootstrap stall reached=${reached.name} missing=${missing.name} waitedMs=$waitedMillis"
}
