package com.shihab.diplay.orchestration

import com.shihab.diplay.airplay.NativeMedia
import com.shihab.diplay.transport.TransportFailureKind

/** Rust selects the action; the host retains its retry budgets, callbacks and platform resources. */
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

        fun retryDelayMillis(attempts: Int, kind: TransportFailureKind): Long {
            NativeMedia.requireAvailable()
            return NativeConnectionRecovery.retryDelay(attempts, kind == TransportFailureKind.IAP_TUNNEL)
        }
    }
}

internal object NativeConnectionRecovery {
    @JvmStatic external fun action(facts: Int, startup: Int): Int
    @JvmStatic external fun retryDelay(attempts: Int, tunnel: Boolean): Long
}
