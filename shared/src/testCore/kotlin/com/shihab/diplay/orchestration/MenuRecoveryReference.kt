package com.shihab.diplay.orchestration

/** The menu branches from the former host implementation, independent of Rust. */
object MenuRecoveryReference {
    fun failure(existingManual: Boolean, incomingManual: Boolean): Pair<Boolean, Boolean> =
        (existingManual != true) to incomingManual

    fun exit(hasFailure: Boolean, startup: Boolean, manual: Boolean, wifiReset: Boolean,
        reconnect: Boolean, wireless: Boolean, handshake: Boolean, recoveryPending: Boolean,
        hasController: Boolean): ConnectionRecovery.MenuExit {
        if (hasFailure && (startup || manual) && !reconnect) return ConnectionRecovery.MenuExit.REPLAY_FAILURE
        if (hasFailure && wifiReset && (!reconnect || wireless)) return ConnectionRecovery.MenuExit.REPLAY_FAILURE
        if (handshake) return ConnectionRecovery.MenuExit.HANDSHAKE_IN_PROGRESS
        if ((reconnect || recoveryPending) && hasController) return ConnectionRecovery.MenuExit.RESTART
        return ConnectionRecovery.MenuExit.START_IF_READY
    }
}
