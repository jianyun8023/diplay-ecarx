package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import java.io.InterruptedIOException

internal class KotlinManualHotspotReadiness(
    private val sample: () -> HotspotNetworkSnapshot,
    private val cancelled: () -> Boolean,
    private val pause: (Long) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
    private val configuredInterface: String? = null,
) {
    fun await(timeoutMillis: Long): HotspotSelection {
        val deadline = nowMillis() + timeoutMillis
        var previous: HotspotSelection? = null
        var stable = 0
        var last: HotspotNetworkSnapshot? = null
        // The tier is decided from the first sample only: an AP that is already on with a tethered
        // interface and a usable address is confirmed quickly, while a hotspot that is still coming
        // up keeps the full confirmation. Any interface or address change observed during the wait
        // drops a fast start back to the full check.
        var confirmedAtStart: Boolean? = null
        var changed = false
        while (true) {
            if (cancelled()) throw InterruptedIOException("Hotspot readiness cancelled")
            if (nowMillis() >= deadline) throw WirelessStartupException(
                WirelessStartupFailure.HOTSPOT_NOT_READY, "Hotspot network is not ready",
                if (last != null && lacksConfiguredInterface(last)) ConnectionFailureCause.HOTSPOT_INTERFACE_NOT_FOUND else null,
            )
            last = sample()
            val selected = selectHotspotInterface(last, log)
            if (cancelled()) throw InterruptedIOException("Hotspot readiness cancelled")
            val previousSelection = previous
            val same = selected != null && previousSelection?.sameAddress(selected) == true
            // A vanished interface counts as a change too, so a fast start never confirms through it.
            if (previousSelection != null && (selected == null || !same)) changed = true
            if (confirmedAtStart == null) confirmedAtStart = selected != null && isConfirmed(last, selected)
            stable = if (same) stable + 1 else 1
            previous = selected
            val required = if (confirmedAtStart == true && !changed) {
                WirelessStartupPolicy.CONFIRMED_STABLE_SAMPLES
            } else {
                WirelessStartupPolicy.STABLE_SAMPLES
            }
            if (selected != null && stable >= required && nowMillis() < deadline) {
                log("hotspot interface confirmed iface=${selected.name} index=${selected.index} atMs=${nowMillis()}")
                return selected
            }
            pause(minOf(WirelessStartupPolicy.INTERFACE_POLL_MILLIS, (deadline - nowMillis()).coerceAtLeast(1)))
        }
    }

    /**
     * True when this sample already proves the hotspot is established: the AP is reported on and
     * [selection] is one of its tethered interfaces carrying a usable address (which
     * [selectHotspotInterface] requires). The ifindex and IPv6 scopeId come from this fresh sample
     * and are never cached across hotspot on/off cycles.
     */
    private fun isConfirmed(snapshot: HotspotNetworkSnapshot, selection: HotspotSelection): Boolean =
        snapshot.apEnabled == true && snapshot.apInterfaces?.contains(selection.name) == true

    private fun lacksConfiguredInterface(snapshot: HotspotNetworkSnapshot): Boolean {
        val name = configuredInterface?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return snapshot.apEnabled == true && snapshot.interfaces.isNotEmpty() && snapshot.interfaces.none { it.name == name }
    }
}
