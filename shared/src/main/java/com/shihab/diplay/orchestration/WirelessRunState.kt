package com.shihab.diplay.orchestration

import com.shihab.diplay.airplay.NativeMedia

/** Rust defines the opaque state token and transitions; the controller still orders platform resources. */
internal class WirelessRunState {
    enum class Lifecycle { RUNNING, RETIRED, CLOSING, CLOSED }
    enum class Handoff { BOOTSTRAP, PENDING, BLUETOOTH_FALLBACK, TUNNEL_ACTIVE }

    init { NativeMedia.requireAvailable() }
    private var token = NativeMedia.wirelessRunCreate()

    val lifecycle: Lifecycle get() = Lifecycle.entries[query(0)]
    val handoff: Handoff get() = Handoff.entries[query(1)]
    val tunnelReady: Boolean get() = query(2) != 0
    val failedOnce: Boolean get() = query(3) != 0
    val running: Boolean get() = query(4) != 0
    val handoffRequested: Boolean get() = query(5) != 0
    val activeReported: Boolean get() = query(6) != 0
    val bootstrapDispatched: Boolean get() = signal(8)
    val addressFamilyReachable: Boolean get() = signal(11)
    val tunnelStartDispatched: Boolean get() = signal(9)

    fun markBootstrapDispatched() { nativeSignal(9) }
    fun claimTunnelStart(): Boolean = nativeSignal(10)
    fun claimStableFrame(): Boolean = nativeSignal(11)
    fun markAddressFamilyReachable() { nativeSignal(12) }
    fun claimFirstTcpSample(): Boolean = nativeSignal(13)
    fun handoffTimerDue(): Boolean = decision(0) != 0
    fun handoffTimeout(hasSession: Boolean, rendered: Boolean): Int = decision(1,
        (if (hasSession) 1 else 0) or (if (rendered) 2 else 0))
    fun canExpireBootstrap(sameAttempt: Boolean, hasTunnel: Boolean, hasSession: Boolean): Boolean = decision(2,
        (if (sameAttempt) 1 else 0) or (if (hasTunnel) 2 else 0) or (if (hasSession) 4 else 0)) != 0
    fun keepBluetoothControl(hasSession: Boolean, rendered: Boolean): Boolean = decision(3,
        (if (hasSession) 1 else 0) or (if (rendered) 2 else 0)) != 0
    fun canDispatchStartSession(sameAttempt: Boolean): Boolean = decision(4, if (sameAttempt) 1 else 0) != 0
    fun handoffStarted(hasTunnel: Boolean, hasSession: Boolean): Boolean = decision(5,
        (if (hasTunnel) 1 else 0) or (if (hasSession) 2 else 0)) != 0

    @Synchronized private fun decision(operation: Int, facts: Int = 0): Int {
        return NativeCarPlayController.wirelessDecision(token, operation, facts).also { check(it >= 0) }
    }

    private fun signal(field: Int): Boolean {
        return query(field) != 0
    }
    private fun nativeSignal(event: Int): Boolean {
        return transition(event)
    }

    fun requestHandoff(): Boolean = transition(1)
    fun markTunnelReady(): Boolean = transition(2)
    fun promoteTunnel(): Boolean = transition(3)
    fun keepBluetoothFallback(): Boolean = transition(4)
    fun fail(): Boolean = transition(5)

    fun retire() {
        transition(6)
    }

    fun beginClose(): Boolean = transition(7)

    fun finishClose() {
        check(transition(8))
    }

    @Synchronized private fun query(field: Int): Int = NativeMedia.wirelessRunQuery(token, field)

    @Synchronized private fun transition(event: Int): Boolean {
        token = NativeMedia.wirelessRunTransition(token, event).also { check(it >= 0L) }
        return NativeMedia.wirelessRunQuery(token, 7) != 0
    }
}
