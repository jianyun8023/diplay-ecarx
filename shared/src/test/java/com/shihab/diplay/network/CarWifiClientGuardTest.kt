package com.shihab.diplay.network

import android.net.wifi.WifiManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarWifiClientGuardTest {
    private val disabled = WifiManager.WIFI_STATE_DISABLED
    private val enabling = WifiManager.WIFI_STATE_ENABLING
    private val enabled = WifiManager.WIFI_STATE_ENABLED

    @Test fun turnsTheClientOffWhenItIsStoredOnOrRunning() {
        // Stored on: the car settings app would turn it on at its next start (11-06 cold boot).
        assertTrue(CarWifiClientGuard.shouldTurnOff(28, keepOff = true, persisted = 1, clientState = disabled))
        assertTrue(CarWifiClientGuard.shouldTurnOff(28, keepOff = true, persisted = 2, clientState = disabled))
        assertTrue(CarWifiClientGuard.shouldTurnOff(28, keepOff = true, persisted = 0, clientState = enabling))
        assertTrue(CarWifiClientGuard.shouldTurnOff(28, keepOff = true, persisted = null, clientState = enabled))
    }

    @Test fun leavesTheClientAloneWhenAlreadyOffDisabledOrOffAndroid9() {
        assertFalse(CarWifiClientGuard.shouldTurnOff(28, keepOff = true, persisted = 0, clientState = disabled))
        assertFalse(CarWifiClientGuard.shouldTurnOff(28, keepOff = true, persisted = 3, clientState = disabled))
        assertFalse(CarWifiClientGuard.shouldTurnOff(28, keepOff = false, persisted = 1, clientState = enabled))
        assertFalse(CarWifiClientGuard.shouldTurnOff(29, keepOff = true, persisted = 1, clientState = enabled))
    }

    @Test fun waitsForTheClientOnlyWhenTheDriverKeepsIt() {
        assertTrue(CarWifiClientGuard.shouldAwaitClient(28, keepOff = false, persisted = 1, clientState = disabled))
        assertTrue(CarWifiClientGuard.shouldAwaitClient(28, keepOff = false, persisted = 1, clientState = enabling))
        assertFalse(CarWifiClientGuard.shouldAwaitClient(28, keepOff = false, persisted = 1, clientState = enabled))
        assertFalse(CarWifiClientGuard.shouldAwaitClient(28, keepOff = false, persisted = 0, clientState = disabled))
        assertFalse(CarWifiClientGuard.shouldAwaitClient(28, keepOff = true, persisted = 1, clientState = disabled))
        assertFalse(CarWifiClientGuard.shouldAwaitClient(29, keepOff = false, persisted = 1, clientState = disabled))
    }
}
