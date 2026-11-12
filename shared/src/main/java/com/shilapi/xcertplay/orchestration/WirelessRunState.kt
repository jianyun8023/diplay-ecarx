package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.NativeMedia

/** Rust defines the opaque state token and transitions; the controller still orders platform resources. */
internal class WirelessRunState {
    enum class Lifecycle { RUNNING, RETIRED, CLOSING, CLOSED }
    enum class Handoff { BOOTSTRAP, PENDING, BLUETOOTH_FALLBACK, TUNNEL_ACTIVE }

    private val fallback = if (NativeMedia.available) null else KotlinWirelessRunState()
    private var token = if (fallback == null) NativeMedia.wirelessRunCreate() else 0L

    val lifecycle: Lifecycle get() = fallback?.lifecycle ?: Lifecycle.entries[query(0)]
    val handoff: Handoff get() = fallback?.handoff ?: Handoff.entries[query(1)]
    val tunnelReady: Boolean get() = fallback?.tunnelReady ?: (query(2) != 0)
    val failedOnce: Boolean get() = fallback?.failedOnce ?: (query(3) != 0)
    val running: Boolean get() = fallback?.running ?: (query(4) != 0)
    val handoffRequested: Boolean get() = fallback?.handoffRequested ?: (query(5) != 0)
    val activeReported: Boolean get() = fallback?.activeReported ?: (query(6) != 0)

    fun requestHandoff(): Boolean = fallback?.requestHandoff() ?: transition(1)
    fun markTunnelReady(): Boolean = fallback?.markTunnelReady() ?: transition(2)
    fun promoteTunnel(): Boolean = fallback?.promoteTunnel() ?: transition(3)
    fun keepBluetoothFallback(): Boolean = fallback?.keepBluetoothFallback() ?: transition(4)
    fun fail(): Boolean = fallback?.fail() ?: transition(5)

    fun retire() {
        if (fallback != null) fallback.retire() else transition(6)
    }

    fun beginClose(): Boolean = fallback?.beginClose() ?: transition(7)

    fun finishClose() {
        if (fallback != null) fallback.finishClose() else check(transition(8))
    }

    @Synchronized private fun query(field: Int): Int = NativeMedia.wirelessRunQuery(token, field)

    @Synchronized private fun transition(event: Int): Boolean {
        token = NativeMedia.wirelessRunTransition(token, event).also { check(it >= 0L) }
        return NativeMedia.wirelessRunQuery(token, 7) != 0
    }
}
