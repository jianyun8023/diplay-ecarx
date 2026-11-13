package com.shihab.diplay

import com.shihab.diplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Test

class LaunchAutoConnectPolicyTest {
    @Test fun existingWifiUsesControllerReadinessRatherThanFixedFiveSecondWait() {
        assertEquals(LaunchAutoConnectPolicy.Plan.IMMEDIATE,
            LaunchAutoConnectPolicy.plan(true, WirelessHotspotMode.EXISTING_WIFI, false))
    }

    @Test fun manualHotspotAlreadyOnDispatchesImmediatelyAndLateOnDispatchesEarly() {
        assertEquals(LaunchAutoConnectPolicy.Plan.WAIT_FOR_CAR_HOTSPOT,
            LaunchAutoConnectPolicy.plan(true, WirelessHotspotMode.MANUAL, false))
        assertEquals(0L, LaunchAutoConnectPolicy.carHotspotDelayMillis(true, 100))
        assertEquals(200L, LaunchAutoConnectPolicy.carHotspotDelayMillis(false, 1_000))
        assertEquals(150L, LaunchAutoConnectPolicy.carHotspotDelayMillis(false, 4_850))
        assertEquals(0L, LaunchAutoConnectPolicy.carHotspotDelayMillis(false, 5_000))
        assertEquals(0L, LaunchAutoConnectPolicy.carHotspotDelayMillis(null, 5_500))
    }

    @Test fun automaticHotspotStillUsesPrepareAndOtherModesKeepExistingDelay() {
        assertEquals(LaunchAutoConnectPolicy.Plan.IMMEDIATE,
            LaunchAutoConnectPolicy.plan(true, WirelessHotspotMode.MANUAL, true))
        assertEquals(LaunchAutoConnectPolicy.Plan.LEGACY_DELAY,
            LaunchAutoConnectPolicy.plan(true, WirelessHotspotMode.WIFI_P2P, false))
        assertEquals(LaunchAutoConnectPolicy.Plan.LEGACY_DELAY,
            LaunchAutoConnectPolicy.plan(false, WirelessHotspotMode.EXISTING_WIFI, false))
    }
}
