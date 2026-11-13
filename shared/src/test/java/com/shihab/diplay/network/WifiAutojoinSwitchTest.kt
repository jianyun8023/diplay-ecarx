package com.shihab.diplay.network

import org.junit.Assert.*
import org.junit.Test

class WifiAutojoinSwitchTest {
    class Wifi {
        val calls = mutableListOf<Boolean>()
        var failure: RuntimeException? = null
        fun enableWifiConnectivityManager(enabled: Boolean) {
            failure?.let { throw it }
            calls += enabled
        }
    }

    @Test fun androidNinePauseAndRestoreUseTheSameNativeControl() {
        val wifi = Wifi()
        val control = WifiAutojoinSwitch(wifi)
        assertEquals(WifiScanSwitchResult.DONE, control.set(false))
        assertEquals(WifiScanSwitchResult.DONE, control.set(true))
        assertEquals(listOf(false, true), wifi.calls)
    }

    @Test fun permissionDenialIsKnownToHaveChangedNothing() {
        val wifi = Wifi().apply { failure = SecurityException("CONNECTIVITY_INTERNAL required") }
        val result = WifiAutojoinSwitch(wifi).set(false)
        assertEquals(WifiScanSwitchResult.DENIED, result)
        assertTrue(result.changedNothing)
        assertTrue(wifi.calls.isEmpty())
    }

    @Test fun unsupportedFirmwareIsNotCalled() {
        assertEquals(WifiScanSwitchResult.UNSUPPORTED, WifiAutojoinSwitch(Any()).set(false))
    }

    @Test fun uncertainFailureStillRequiresTheExistingRestoreJournal() {
        val result = WifiAutojoinSwitch(Wifi().apply { failure = IllegalStateException("lost binder reply") }).set(false)
        assertEquals(WifiScanSwitchResult.UNKNOWN, result)
        assertFalse(result.changedNothing)
    }
}
