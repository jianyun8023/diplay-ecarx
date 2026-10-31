package com.shilapi.xcertplay.network

import android.net.wifi.WifiConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarHotspotBandTest {
    private val logs = mutableListOf<String>()
    private fun log(message: String) { logs += message }

    private val twoGhz = WifiConfiguration().apply {
        SSID = "Car-Original"
        preSharedKey = "secret123"
        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
        javaClass.getField("apBand").setInt(this, WifiConfiguration::class.java.getField("AP_BAND_2GHZ").getInt(null))
        javaClass.getField("apChannel").setInt(this, 6)
    }

    private fun fiveGhzBand() = WifiConfiguration::class.java.getField("AP_BAND_5GHZ").getInt(null)
    private fun bandOf(config: WifiConfiguration) = config.javaClass.getField("apBand").getInt(config)
    private fun channelOf(config: WifiConfiguration) = config.javaClass.getField("apChannel").getInt(config)

    @Test fun changesOnlyTheSavedBandAndKeepsTheCredentials() {
        var stored: WifiConfiguration? = twoGhz
        val result = CarHotspotBand.preferFiveGhz(
            dualBand = true,
            read = { stored },
            write = { stored = it; true },
            log = ::log,
        )
        assertEquals(CarHotspotBand.Result.CHANGED, result)
        val saved = stored!!
        assertEquals(fiveGhzBand(), bandOf(saved))
        assertEquals(0, channelOf(saved))
        assertEquals("Car-Original", saved.SSID)
        assertEquals("secret123", saved.preSharedKey)
    }

    @Test fun alreadyFiveGhzDoesNotWrite() {
        twoGhz.javaClass.getField("apBand").setInt(twoGhz, fiveGhzBand())
        var writes = 0
        val result = CarHotspotBand.preferFiveGhz(true, { twoGhz }, { writes++; true }, ::log)
        assertEquals(CarHotspotBand.Result.ALREADY_5GHZ, result)
        assertEquals(0, writes)
    }

    @Test fun a2OnlyRadioIsNotTouched() {
        var writes = 0
        val result = CarHotspotBand.preferFiveGhz(false, { twoGhz }, { writes++; true }, ::log)
        assertEquals(CarHotspotBand.Result.NOT_DUAL_BAND, result)
        assertEquals(0, writes)
        assertEquals(WifiConfiguration::class.java.getField("AP_BAND_2GHZ").getInt(null), bandOf(twoGhz))
    }

    @Test fun anUnreadableConfigurationIsReported() {
        val result = CarHotspotBand.preferFiveGhz(true, { null }, { error("must not write") }, ::log)
        assertEquals(CarHotspotBand.Result.UNAVAILABLE, result)
    }

    @Test fun aRejectedWriteLeavesTheOriginalBandReportedAsFailure() {
        var stored: WifiConfiguration? = twoGhz
        val result = CarHotspotBand.preferFiveGhz(true, { stored }, { stored = it; false }, ::log)
        assertEquals(CarHotspotBand.Result.FAILED, result)
        assertEquals(WifiConfiguration::class.java.getField("AP_BAND_2GHZ").getInt(null), bandOf(twoGhz))
    }

    @Test fun aReadbackThatStaysOn2GhzIsAFailure() {
        val result = CarHotspotBand.preferFiveGhz(true, { twoGhz }, { true }, ::log)
        assertEquals(CarHotspotBand.Result.FAILED, result)
    }

    @Test fun onlyTheVerifiedAppWriteSdksAreSupported() {
        assertTrue(CarHotspotBand.supported(25))
        assertTrue(CarHotspotBand.supported(28))
        assertFalse(CarHotspotBand.supported(29))
        assertFalse(CarHotspotBand.supported(30))
    }
}
