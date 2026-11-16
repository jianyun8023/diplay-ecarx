package com.shihab.diplay.media

/** Original host retry check, kept independent of the native token. */
class AutoPlayRecoveryReference {
    var checks = 0
        private set
    fun duplicate(sameSession: Boolean): Boolean = sameSession
    fun reset() { checks = 0 }
    fun check(bluetoothOn: Boolean): AutoPlayRecovery.Action {
        if (!bluetoothOn) return AutoPlayRecovery.Action.SEND
        checks += 1
        return if (checks <= 5) AutoPlayRecovery.Action.WAIT else AutoPlayRecovery.Action.GIVE_UP
    }
}
