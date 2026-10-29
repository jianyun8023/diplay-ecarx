package com.shilapi.xcertplay.network

import android.net.wifi.WifiConfiguration
import com.shilapi.xcertplay.network.CarHotspotProbe.Action
import com.shilapi.xcertplay.network.CarHotspotProbe.Status
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CarHotspotProbeTest {
    private class Device : CarHotspotProbe.Platform {
        override var sdk = 28
        var config = WifiConfiguration().apply {
            SSID = "Original-Car"
            preSharedKey = "original-key"
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
            hiddenSSID = true
            javaClass.getField("apBand").setInt(this, 1)
            javaClass.getField("apChannel").setInt(this, 149)
        }
        var writes = 0
        var starts = 0
        var radioState = ManualHotspotControl.State.OFF
        var accepted = true
        var applyWrite = true
        var transition = true
        var denyRead = false
        var time = 0L
        override fun inspect() = "sdk=$sdk APIs=present"
        override fun read(): WifiConfiguration {
            if (denyRead) throw SecurityException("private config: Original-Car original-key")
            return WifiConfiguration(config)
        }
        override fun write(config: WifiConfiguration): Boolean {
            writes++
            if (accepted && applyWrite) this.config = WifiConfiguration(config)
            return accepted
        }
        override fun softAp(enabled: Boolean): Boolean {
            starts++
            if (transition) radioState = if (enabled) ManualHotspotControl.State.ON else ManualHotspotControl.State.OFF
            return accepted
        }
        override fun request(enabled: Boolean, cancelled: () -> Boolean) = ManualHotspotControl.Result.PERMISSION_REQUIRED
        override fun state() = radioState
        override fun now() = time
        override fun pause() { time += 100_000_000L }
    }

    @Test fun inspectAndReadNeverWriteAndDoNotExportCredentials() {
        val device = Device()
        val probe = CarHotspotProbe(device)
        assertEquals(Status.OBSERVED, probe.run(Action.INSPECT).status)
        assertFalse(probe.canRestore)
        val read = probe.run(Action.READ)
        assertEquals("Original-Car", read.credentials?.name)
        assertEquals("original-key", read.credentials?.key)
        assertFalse(read.evidence.contains("Original-Car"))
        assertFalse(read.evidence.contains("original-key"))
        assertTrue(probe.canRestore)
        assertEquals(0, device.writes)
        assertEquals(0, device.starts)
    }

    @Test fun explicitWriteChangesTheNameAndKeyPreservesRadioAndRestoresTheOriginal() {
        val device = Device()
        val probe = CarHotspotProbe(device)
        probe.run(Action.READ)
        val changed = probe.run(Action.WRITE, CarHotspotProbe.Credentials("Temporary-Car", "temporary-key"))
        assertEquals(Status.VERIFIED, changed.status)
        assertEquals("Temporary-Car", device.config.SSID)
        assertEquals("temporary-key", device.config.preSharedKey)
        assertTrue(device.config.hiddenSSID)
        assertEquals(149, device.config.javaClass.getField("apChannel").getInt(device.config))
        assertEquals(0, device.starts)
        assertEquals(Status.VERIFIED, probe.run(Action.RESTORE).status)
        assertEquals("Original-Car", device.config.SSID)
        assertEquals("original-key", device.config.preSharedKey)
    }

    @Test fun requiresOriginalReadValidInputAndWpa2BeforeWriting() {
        val device = Device()
        val probe = CarHotspotProbe(device)
        val input = CarHotspotProbe.Credentials("New-Car", "new-car-key")
        assertEquals(Status.REJECTED, probe.run(Action.WRITE, input).status)
        probe.run(Action.READ)
        assertEquals(Status.REJECTED, probe.run(Action.WRITE, input.copy(name = "车".repeat(11))).status)
        assertEquals(Status.REJECTED, probe.run(Action.WRITE, input.copy(key = "short")).status)
        assertEquals(Status.CANCELLED, probe.run(Action.WRITE, input) { true }.status)
        device.config.allowedKeyManagement.clear()
        assertEquals(Status.UNSUPPORTED, probe.run(Action.WRITE, input).status)
        assertEquals(0, device.writes)
    }

    @Test fun acceptedSetterWithoutMatchingReadbackIsNotSuccess() {
        val device = Device().apply { applyWrite = false }
        val probe = CarHotspotProbe(device)
        probe.run(Action.READ)
        assertEquals(Status.FAILED, probe.run(Action.WRITE, CarHotspotProbe.Credentials("New-Car", "new-car-key")).status)
        assertEquals(1, device.writes)
    }

    @Test fun permissionFailureDoesNotExposeExceptionValues() {
        val probe = CarHotspotProbe(Device().apply { denyRead = true })
        val read = probe.run(Action.READ)
        assertEquals(Status.DENIED, read.status)
        assertFalse(read.evidence.contains("Original-Car"))
        assertFalse(read.evidence.contains("original-key"))
    }

    @Test fun softApRequiresObservedStateAndDoesNotRetryOrRecover() {
        val device = Device().apply { transition = false }
        val probe = CarHotspotProbe(device)
        assertEquals(Status.TIMED_OUT, probe.run(Action.START_OEM).status)
        assertEquals(1, device.starts)
        assertEquals(8_000_000_000L, device.time)
        device.transition = true
        assertEquals(Status.VERIFIED, probe.run(Action.START_OEM).status)
        assertEquals(Status.OBSERVED, probe.run(Action.START_OEM).status)
        assertEquals(2, device.starts)
        assertEquals(Status.VERIFIED, probe.run(Action.STOP_OEM).status)
        assertEquals(Status.DENIED, probe.run(Action.START_PLATFORM).status)
    }

    @Test fun unsupportedAndroidDoesNotAttemptAnyWrite() {
        val device = Device().apply { sdk = 30 }
        val probe = CarHotspotProbe(device)
        assertEquals(Status.OBSERVED, probe.run(Action.INSPECT).status)
        assertEquals(Status.UNSUPPORTED, probe.run(Action.READ).status)
        assertEquals(Status.UNSUPPORTED, probe.run(Action.START_OEM).status)
        assertEquals(0, device.starts)
    }
}
