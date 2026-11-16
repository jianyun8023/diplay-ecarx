package com.shihab.diplay.orchestration

import com.shihab.diplay.airplay.NativeMedia

/** Rust owns recovery policy and retry budgets; the host retains callbacks and platform resources. */
enum class ConnectionRecovery {
    RETRY, STARTUP_RETRY, STARTUP_STOP, USER_ACTION, WIFI_RESET, USB_PAIRING;

    data class Decision(val action: ConnectionRecovery, val requiresUserAction: Boolean)

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

    }
}

internal object NativeConnectionRecovery {
    @JvmStatic external fun action(facts: Int, startup: Int): Int
    @JvmStatic external fun retryTransition(token: Long, event: Int): Long
    @JvmStatic external fun retryQuery(token: Long, field: Int): Long
    @JvmStatic external fun stable(first: Long, now: Long): Boolean
    @JvmStatic external fun controlTransition(token: Long, event: Int): Long
    @JvmStatic external fun controlRecovered(token: Long, snapshot: Long): Boolean
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
