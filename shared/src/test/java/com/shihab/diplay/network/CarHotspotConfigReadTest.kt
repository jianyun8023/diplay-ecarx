package com.shihab.diplay.network

import android.net.wifi.WifiConfiguration
import com.shihab.diplay.orchestration.ManualHotspotSecurity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarHotspotConfigReadTest {
    private val logs = mutableListOf<String>()
    private fun log(message: String) { logs += message }

    @Test @Config(sdk = [28]) fun androidNineReadsInTheAppWithoutTheOldUnsupportedVersionGate() {
        val outcome = CarHotspotConfigRead.read(org.robolectric.RuntimeEnvironment.getApplication(),
            sdk = 28, log = ::log)
        assertNotEquals(CarHotspotConfigRead.Reason.UNSUPPORTED_SDK, outcome.reason)
    }

    @Test fun appReadReturnsTheStoredConfiguration() {
        val config = WifiConfiguration().apply {
            SSID = "Car-Hotspot"
            preSharedKey = "password1"
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
        }
        val outcome = CarHotspotConfigRead.readApp({ config }, ::log)
        assertEquals(CarHotspotSavedCredentials("Car-Hotspot", "password1", ManualHotspotSecurity.WPA2), outcome.credentials)
        assertNull(outcome.reason)
    }

    @Test fun appReadMapsOpenNetworksAndRejectsExoticKeyManagement() {
        val open = WifiConfiguration().apply {
            SSID = "Car"
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
        }
        assertEquals(CarHotspotSavedCredentials("Car", "", ManualHotspotSecurity.OPEN),
            CarHotspotConfigRead.readApp({ open }, ::log).credentials)
        val enterprise = WifiConfiguration().apply {
            SSID = "Car"
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X)
        }
        assertNull(CarHotspotConfigRead.readApp({ enterprise }, ::log).credentials)
        assertNull(CarHotspotConfigRead.readApp({ null }, ::log).credentials)
    }

@Test fun malformedSavedCredentialsAreNotImported() {
    val secured = WifiConfiguration().apply {
        SSID = "Car"
        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
    }
    assertNull(CarHotspotConfigRead.readApp({ secured }, ::log).credentials)
    secured.preSharedKey = "password1"
    secured.SSID = ""
    assertNull(CarHotspotConfigRead.readApp({ secured }, ::log).credentials)
    assertEquals(CarHotspotConfigRead.Reason.UNAVAILABLE,
        CarHotspotConfigRead.readApp({ throw SecurityException("platform permission missing") }, ::log).reason)
}

@Test fun otherAndroidReleasesDoNotOfferAPlatformHotspotImport() {
    assertEquals(CarHotspotConfigRead.Reason.UNSUPPORTED_SDK,
        CarHotspotConfigRead.read(org.robolectric.RuntimeEnvironment.getApplication(), sdk = 33, log = ::log).reason)
}
}
