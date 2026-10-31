package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.bluetooth.CarBluetoothProfileGuard
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayTransportRecoveryTest {
    private lateinit var host: CarPlayHostActivity
    private lateinit var usb: UsbDevice
    private val loop get() = shadowOf(Looper.getMainLooper())

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        call("initializeSessionLog")
        set("locationReportingEnabled", false)
        set("awaitingVpnConsent", true)
        set("awaitingWiredPermissions", true)
        set("wirelessEnabled", true)
        AirPlayPersistence.saveWirelessEnabled(host, true)
        usb = mock(UsbDevice::class.java)
        `when`(usb.vendorId).thenReturn(1452)
        `when`(usb.deviceName).thenReturn("/dev/bus/usb/001/002")
    }

    @After fun cleanup() {
        call("restoreCarBluetooth")
        ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        ReflectionHelpers.getField<SessionLogFile>(host, "sessionLog").close()
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor"))
            ReflectionHelpers.getField<ExecutorService>(host, name).shutdownNow()
        CarPlayBackgroundSession.clear()
        host.getSharedPreferences("diplay_bt_profile_guard", 0).edit().clear().commit()
    }

    private fun set(name: String, value: Any?) = ReflectionHelpers.setField(host, name, value)
    private fun call(name: String): Any? = host.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(host)
    private fun event(action: String, device: UsbDevice = usb) = Intent(action).putExtra(UsbManager.EXTRA_DEVICE, device)
    private fun attached() {
        host.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }
            .invoke(host, event(UsbManager.ACTION_USB_DEVICE_ATTACHED))
    }
    private fun receive(action: String, device: UsbDevice = usb) =
        ReflectionHelpers.getField<BroadcastReceiver>(host, "usbConnectionReceiver").onReceive(host, event(action, device))
    private fun wireless() = call("getUseWirelessTransport") as Boolean

    @Test fun bothTransportsYieldCurrentPhoneNotSavedWirelessSelection() {
        CarBluetoothProfileGuard.saveMediaOnlyEnabled(host, true)
        DiPlayPreferences.savePhone(host, "00:11:22:33:44:55", "previous")
        val current = mock(AirPlaySession::class.java)
        `when`(current.deviceBtMac).thenReturn("00:11:22:33:44:66")
        set("activeAirPlaySession", current)
        for (usbTakeover in listOf(false, true)) {
            CarPlayBackgroundSession.usbTakeover = usbTakeover
            call("yieldCarBluetooth")
            val session = ReflectionHelpers.getField<Any>(CarBluetoothProfileGuard, "session")
            assertEquals("00:11:22:33:44:66", ReflectionHelpers.getField<String>(session, "peerAddress"))
            call("restoreCarBluetooth")
        }
        `when`(current.deviceBtMac).thenReturn("")
        call("yieldCarBluetooth")
        val session = ReflectionHelpers.getField<Any>(CarBluetoothProfileGuard, "session")
        assertNull(ReflectionHelpers.getField<String?>(session, "peerAddress"))
    }

    @Test fun usbTakeoverAndSettingsReloadKeepWirelessPreference() {
        attached()
        assertFalse(wireless())
        assertTrue(AirPlayPersistence.loadWirelessEnabled(host))
        call("loadConnectionSettings")
        assertFalse(wireless())
        assertTrue(AirPlayPersistence.loadWirelessEnabled(host))
    }

    @Test fun unplugAfterFailureRestoresWirelessAndClearsStoppedRetry() {
        attached()
        set("startupRetryStopped", true)
        set("usbPairingStoppedGeneration", 0)
        set("menuOpen", true)
        receive(UsbManager.ACTION_USB_DEVICE_DETACHED)
        loop.idleFor(1999, TimeUnit.MILLISECONDS)
        assertFalse(wireless())
        loop.idleFor(1, TimeUnit.MILLISECONDS)
        assertTrue(wireless())
        assertFalse(ReflectionHelpers.getField(host, "startupRetryStopped"))
        assertTrue(ReflectionHelpers.getField(host, "recoveryPendingAfterMenu"))
        assertTrue(AirPlayPersistence.loadWirelessEnabled(host))
    }

    @Test fun reenumerationReturnCancelsFallbackAndKeepsUsb() {
        attached()
        set("usbReenumerating", true)
        receive(UsbManager.ACTION_USB_DEVICE_DETACHED)
        loop.idleFor(3000, TimeUnit.MILLISECONDS)
        assertFalse(wireless())
        receive(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        loop.idleFor(10_000, TimeUnit.MILLISECONDS)
        assertFalse(wireless())
    }

    @Test fun unplugDuringReenumerationEventuallyRestoresWireless() {
        attached()
        set("usbReenumerating", true)
        set("menuOpen", true)
        receive(UsbManager.ACTION_USB_DEVICE_DETACHED)
        loop.idleFor(10_000, TimeUnit.MILLISECONDS)
        assertTrue(wireless())
    }

    @Test fun stillEnumeratedPhoneAndUnrelatedUsbDoNotTriggerFallback() {
        attached()
        shadowOf(host.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(usb, true)
        receive(UsbManager.ACTION_USB_DEVICE_DETACHED)
        loop.idleFor(3000, TimeUnit.MILLISECONDS)
        assertFalse(wireless())
        val other = mock(UsbDevice::class.java)
        `when`(other.vendorId).thenReturn(1234)
        receive(UsbManager.ACTION_USB_DEVICE_DETACHED, other)
        loop.idleFor(3000, TimeUnit.MILLISECONDS)
        assertFalse(wireless())
    }

    @Test fun driverDisabledWirelessIsNotEnabledByUnplug() {
        AirPlayPersistence.saveWirelessEnabled(host, false)
        set("wirelessEnabled", false)
        attached()
        receive(UsbManager.ACTION_USB_DEVICE_DETACHED)
        loop.idleFor(3000, TimeUnit.MILLISECONDS)
        assertFalse(wireless())
        assertFalse(AirPlayPersistence.loadWirelessEnabled(host))
    }

    @Test fun unplugDoesNotOverwriteUnsavedWirelessChoice() {
        attached()
        set("menuOpen", true)
        set("wirelessEnabled", false)
        receive(UsbManager.ACTION_USB_DEVICE_DETACHED)
        loop.idleFor(3000, TimeUnit.MILLISECONDS)
        assertFalse(wireless())
        assertTrue(AirPlayPersistence.loadWirelessEnabled(host))
        assertTrue(ReflectionHelpers.getField(host, "recoveryPendingAfterMenu"))
    }

    @Test fun ordinaryWirelessSessionIgnoresUsbDetach() {
        receive(UsbManager.ACTION_USB_DEVICE_DETACHED)
        loop.idleFor(15_000, TimeUnit.MILLISECONDS)
        assertTrue(wireless())
        assertFalse(CarPlayBackgroundSession.usbTakeover)
    }
}
