package com.shihab.diplay

import com.shihab.diplay.orchestration.WirelessHotspotMode

/** Only scheduling, not connection retries. The controller still validates the interface and IP. */
internal object LaunchAutoConnectPolicy {
    const val LEGACY_DELAY_MILLIS = 5_000L
    private const val CAR_HOTSPOT_POLL_MILLIS = 200L

    enum class Plan { IMMEDIATE, WAIT_FOR_CAR_HOTSPOT, LEGACY_DELAY }

    fun plan(wireless: Boolean, mode: WirelessHotspotMode, appEnablesHotspot: Boolean): Plan = when {
        !wireless -> Plan.LEGACY_DELAY
        appEnablesHotspot -> Plan.IMMEDIATE // Existing asynchronous prepare/connect handoff
        mode == WirelessHotspotMode.EXISTING_WIFI -> Plan.IMMEDIATE
        mode == WirelessHotspotMode.MANUAL -> Plan.WAIT_FOR_CAR_HOTSPOT
        else -> Plan.LEGACY_DELAY // Keep P2P and other backends unchanged
    }

    /** Avoid a false "hotspot off" prompt during the OEM's delayed boot-time hotspot start. */
    fun carHotspotDelayMillis(hotspotOn: Boolean?, elapsedMs: Long): Long {
        val remaining = (LEGACY_DELAY_MILLIS - elapsedMs.coerceAtLeast(0)).coerceAtLeast(0)
        return if (hotspotOn == true) 0L else minOf(CAR_HOTSPOT_POLL_MILLIS, remaining)
    }
}
