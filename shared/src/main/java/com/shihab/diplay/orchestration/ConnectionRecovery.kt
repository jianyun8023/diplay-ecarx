package com.shihab.diplay.orchestration

import com.shihab.diplay.airplay.NativeMedia

/** Rust owns recovery policy and retry budgets; the host retains callbacks and platform resources. */
enum class ConnectionRecovery {
    RETRY, STARTUP_RETRY, STARTUP_STOP, USER_ACTION, WIFI_RESET, USB_PAIRING;

    data class Decision(val action: ConnectionRecovery, val requiresUserAction: Boolean)
    data class MenuFailureDecision(val replace: Boolean, val stop: Boolean)
    enum class MenuExit { REPLAY_FAILURE, HANDSHAKE_IN_PROGRESS, RESTART, START_IF_READY }

    companion object {
        fun forFailure(failure: CarPlayStatus.Failed): Decision {
            NativeMedia.requireAvailable()
            val facts = (if (failure.usbPairingFailure != null) 1 else 0) or
                (if (failure.wifiResetRequired) 2 else 0) or
                (if (!failure.automaticRetryAllowed) 4 else 0) or
                (if (failure.knownCause == ConnectionFailureCause.WIRELESS_CARPLAY_UNAVAILABLE) 8 else 0)
            val result = NativeConnectionRecovery.action(facts, failure.startupFailure?.ordinal ?: -1)
            return Decision(entries[result and 7], result and 8 != 0)
        }

        fun menuFailure(existingManual: Boolean, incomingManual: Boolean): MenuFailureDecision {
            NativeMedia.requireAvailable()
            val result = NativeConnectionRecovery.menuFailure(existingManual, incomingManual)
            return MenuFailureDecision(result and 1 != 0, result and 2 != 0)
        }

        fun menuExit(hasFailure: Boolean, startup: Boolean, manual: Boolean, wifiReset: Boolean,
            reconnect: Boolean, wireless: Boolean, handshake: Boolean, recoveryPending: Boolean,
            hasController: Boolean): MenuExit {
            NativeMedia.requireAvailable()
            val facts = (if (hasFailure) 1 else 0) or (if (startup) 2 else 0) or
                (if (manual) 4 else 0) or (if (wifiReset) 8 else 0) or
                (if (reconnect) 16 else 0) or (if (wireless) 32 else 0) or
                (if (handshake) 64 else 0) or (if (recoveryPending) 128 else 0) or
                (if (hasController) 256 else 0)
            return MenuExit.entries[NativeConnectionRecovery.menuExit(facts)]
        }
    }
}

internal object NativeConnectionRecovery {
    @JvmStatic external fun action(facts: Int, startup: Int): Int
    @JvmStatic external fun retryTransition(token: Long, event: Int): Long
    @JvmStatic external fun retryQuery(token: Long, field: Int): Long
    @JvmStatic external fun stable(first: Long, now: Long): Boolean
    @JvmStatic external fun controlTransition(token: Long, event: Int): Long
    @JvmStatic external fun controlRecovered(token: Long, snapshot: Long): Boolean
    @JvmStatic external fun menuFailure(existingManual: Boolean, incomingManual: Boolean): Int
    @JvmStatic external fun menuExit(facts: Int): Int
    @JvmStatic external fun backgroundTransition(token: Long, event: Int): Long
    @JvmStatic external fun backgroundConsumed(token: Long): Boolean
    @JvmStatic external fun failureTransition(token: Long, event: Int, generation: Int): Long
    @JvmStatic external fun failureQuery(token: Long, field: Int): Boolean
    @JvmStatic external fun usbStopTransition(token: Long, event: Int, generation: Int): Long
    @JvmStatic external fun usbStopMatches(token: Long, generation: Int): Boolean
}

/** Host-lifetime global stop and independent startup/USB generation latches. */
class HostFailureState {
    private var failureToken = 0L
    private var usbToken = 0L
    init { NativeMedia.requireAvailable() }
    val stopped: Boolean get() = NativeConnectionRecovery.failureQuery(failureToken, 0)
    fun stop() { failureToken = NativeConnectionRecovery.failureTransition(failureToken, 0, 0) }
    fun resume() { failureToken = NativeConnectionRecovery.failureTransition(failureToken, 1, 0) }
    fun duplicateStartup(generation: Int): Boolean {
        failureToken = NativeConnectionRecovery.failureTransition(failureToken, 2, generation)
        return NativeConnectionRecovery.failureQuery(failureToken, 1)
    }
    fun stopUsb(generation: Int) {
        usbToken = NativeConnectionRecovery.usbStopTransition(usbToken, 0, generation)
        stop()
    }
    fun usbBlocks(generation: Int): Boolean = NativeConnectionRecovery.usbStopMatches(usbToken, generation)
    fun usbFallback() {
        resume()
        usbToken = NativeConnectionRecovery.usbStopTransition(usbToken, 1, 0)
    }
}

/** Host-lifetime marker for an attempt deliberately kept after StartSession. */
class BackgroundAttemptState {
    private var token = 0L
    init { NativeMedia.requireAvailable() }
    fun keep() { token = NativeConnectionRecovery.backgroundTransition(token, 0) }
    fun foreground() { token = NativeConnectionRecovery.backgroundTransition(token, 1) }
    fun consumeFailure(): Boolean {
        token = NativeConnectionRecovery.backgroundTransition(token, 2)
        return NativeConnectionRecovery.backgroundConsumed(token)
    }
}

/** Host-lifetime observation of control recovery, separate from retry budgets. */
class ControlRecoveryState {
    private var token = 0L
    init { NativeMedia.requireAvailable() }
    fun running() { token = NativeConnectionRecovery.controlTransition(token, 0) }
    fun loss() { token = NativeConnectionRecovery.controlTransition(token, 1) }
    fun snapshot(): Long = token
    fun recoveredSince(snapshot: Long): Boolean = NativeConnectionRecovery.controlRecovered(token, snapshot)
}

/** Host-lifetime retry state; the token is owned and interpreted by Rust. */
class HostRetryState {
    private var token = 0L
    init { NativeMedia.requireAvailable() }
    val attempts: Int get() = NativeConnectionRecovery.retryQuery(token, 0).toInt()
    val startupRetries: Int get() = NativeConnectionRecovery.retryQuery(token, 1).toInt()
    val stableSessionMillis: Long get() = NativeConnectionRecovery.retryQuery(token, 4)
    fun schedule(startup: Boolean, tunnel: Boolean): Long? {
        token = NativeConnectionRecovery.retryTransition(token, if (startup) 0 else if (tunnel) 1 else 2)
        return if (NativeConnectionRecovery.retryQuery(token, 2) != 0L) null
            else NativeConnectionRecovery.retryQuery(token, 3)
    }
    fun resetAttempts() { token = NativeConnectionRecovery.retryTransition(token, 3) }
    fun resetStartup() { token = NativeConnectionRecovery.retryTransition(token, 4) }
    fun stable(first: Long, now: Long): Boolean = NativeConnectionRecovery.stable(first, now)
}
