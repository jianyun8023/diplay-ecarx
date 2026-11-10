package com.shilapi.xcertplay.orchestration

/** State of one run. Resource operations remain ordered by the controller's owner lock. */
internal class WirelessRunState {
    enum class Lifecycle { RUNNING, FAILED, RETIRED, CLOSING, CLOSED }
    enum class Handoff { BOOTSTRAP, PENDING, BLUETOOTH_FALLBACK, TUNNEL_ACTIVE }

    @Volatile var lifecycle = Lifecycle.RUNNING
        private set
    @Volatile var handoff = Handoff.BOOTSTRAP
        private set
    @Volatile var tunnelReady = false
        private set

    val running: Boolean get() = lifecycle == Lifecycle.RUNNING
    val handoffRequested: Boolean get() = handoff != Handoff.BOOTSTRAP
    val activeReported: Boolean get() = handoff == Handoff.BLUETOOTH_FALLBACK || handoff == Handoff.TUNNEL_ACTIVE

    @Synchronized fun requestHandoff(): Boolean {
        if (!running || handoff != Handoff.BOOTSTRAP) return false
        handoff = Handoff.PENDING
        return true
    }

    @Synchronized fun markTunnelReady(): Boolean {
        if (!running) return false
        tunnelReady = true
        return true
    }

    @Synchronized fun promoteTunnel(): Boolean {
        if (!running || !tunnelReady ||
            (handoff != Handoff.PENDING && handoff != Handoff.BLUETOOTH_FALLBACK)) return false
        handoff = Handoff.TUNNEL_ACTIVE
        return true
    }

    @Synchronized fun keepBluetoothFallback(renderedFrame: Boolean): Boolean {
        if (!running || handoff != Handoff.PENDING || tunnelReady || !renderedFrame) return false
        handoff = Handoff.BLUETOOTH_FALLBACK
        return true
    }

    @Synchronized fun fail(): Boolean {
        if (!running) return false
        lifecycle = Lifecycle.FAILED
        return true
    }

    @Synchronized fun retire() {
        if (lifecycle == Lifecycle.RUNNING || lifecycle == Lifecycle.FAILED) lifecycle = Lifecycle.RETIRED
    }

    @Synchronized fun beginClose(): Boolean {
        if (lifecycle == Lifecycle.CLOSING || lifecycle == Lifecycle.CLOSED) return false
        lifecycle = Lifecycle.CLOSING
        return true
    }

    @Synchronized fun finishClose() {
        check(lifecycle == Lifecycle.CLOSING)
        lifecycle = Lifecycle.CLOSED
    }
}
