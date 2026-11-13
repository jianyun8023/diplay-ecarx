package com.shihab.diplay

import com.shihab.diplay.BootCarPlayLaunch.Decision
import com.shihab.diplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Test

class BootCarPlayLaunchTest {
    private val ready = BootCarPlayLaunch.Inputs(
        autoConnect = true,
        wireless = true,
        phoneChosen = true,
        setupGuidePending = false,
        authenticationReady = true,
        sessionRunning = false,
        mode = WirelessHotspotMode.MANUAL,
        hotspotDetailsValid = true,
        hotspotReportedOff = false,
        appStartsHotspot = false,
        notificationPermissionPending = false,
    )

    @Test fun configuredWirelessAutoConnectOpensCarPlayDirectly() {
        assertEquals(Decision.CARPLAY, BootCarPlayLaunch.decide(ready))
        assertEquals(Decision.CARPLAY, BootCarPlayLaunch.decide(ready.copy(mode = WirelessHotspotMode.EXISTING_WIFI)))
    }

    @Test fun anythingTheHomeScreenWouldAskAboutKeepsTheHomeScreen() {
        listOf(
            ready.copy(autoConnect = false),
            ready.copy(wireless = false),
            ready.copy(phoneChosen = false),
            ready.copy(setupGuidePending = true),
            ready.copy(authenticationReady = false),
            ready.copy(sessionRunning = true),
            ready.copy(notificationPermissionPending = true),
            ready.copy(hotspotDetailsValid = false),
            ready.copy(hotspotReportedOff = true),
        ).forEach { assertEquals(it.toString(), Decision.HOME, BootCarPlayLaunch.decide(it)) }
    }

    @Test fun anOffHotspotIsFineWhenDiPlayStartsIt() {
        assertEquals(Decision.CARPLAY,
            BootCarPlayLaunch.decide(ready.copy(hotspotReportedOff = true, appStartsHotspot = true)))
    }

    @Test fun otherWirelessBackendsKeepTheirStartUpTiming() {
        WirelessHotspotMode.entries
            .filter { it != WirelessHotspotMode.MANUAL && it != WirelessHotspotMode.EXISTING_WIFI }
            .forEach { assertEquals(it.name, Decision.HOME, BootCarPlayLaunch.decide(ready.copy(mode = it))) }
    }
}
