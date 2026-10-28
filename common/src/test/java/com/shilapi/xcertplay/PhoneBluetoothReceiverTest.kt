package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PhoneBluetoothReceiverTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val selected = "12:34:56:78:9A:BC"

    @Before fun reset() {
        app.getSharedPreferences("diplay_connection_decisions", 0).edit().clear().commit()
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, false)
        app.getSharedPreferences("diplay_phone_wake", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("diplay_phone_wake_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        // Saved in lowercase on purpose: the receiver must match the (always uppercase) device
        // address case-insensitively; Robolectric refuses to build a lowercase remote device.
        DiPlayPreferences.savePhone(app, selected.lowercase(), "iPhone")
    }

    @After fun cleanup() { CarPlayBackgroundSession.clear() }

    private fun event(address: String): Intent {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
        return Intent(BluetoothDevice.ACTION_ACL_CONNECTED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
    }

    @Test fun onlySelectedPhoneWithOptInLaunchesOnce() {
        val launches = mutableListOf<Intent>()
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launches += intent }
        }
        val receiver = PhoneBluetoothReceiver()
        receiver.onReceive(context, event(selected))
        assertTrue(launches.isEmpty())
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
        receiver.onReceive(context, event("AA:BB:CC:DD:EE:FF"))
        assertTrue(launches.isEmpty())
        receiver.onReceive(context, event(selected))
        receiver.onReceive(context, event(selected))
        assertEquals(1, launches.size)
        assertEquals(DiPlayActivity::class.java.name, launches.single().component?.className)
        assertTrue(launches.single().getBooleanExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, false))
        assertTrue(PhoneWakeDiagnostics.report(app).contains("result=startActivity-returned"))
        val trace = ConnectionDiagnostics.report(app)
        for (event in listOf("OPT_OUT", "OTHER_DEVICE", "LAUNCH_REQUESTED", "LAUNCH_RETURNED", "THROTTLED")) {
            assertTrue(event, trace.contains("event=$event"))
        }
        assertFalse(trace.contains(selected))
    }
    @Test fun disconnectedOwnerIsReportedWithoutChangingExistingLaunchPolicy() {
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", { done: () -> Unit -> done() })
        CarPlayBackgroundSession.active = false
        var launches = 0
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launches++ }
        }
        PhoneBluetoothReceiver().onReceive(context, event(selected))
        assertEquals(0, launches)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertFalse(CarPlayBackgroundSession.active)
        val trace = ConnectionDiagnostics.report(app)
        assertTrue(trace.contains("event=OWNER_PRESENT ownerPresent=true active=false"))
    }

    @Test fun failedLaunchAndSubsequentThrottleAreBothVisibleWithoutPrivateErrorText() {
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { throw SecurityException("private-phone-name secret-key") }
        }
        val receiver = PhoneBluetoothReceiver()
        receiver.onReceive(context, event(selected))
        receiver.onReceive(context, event(selected))
        val trace = ConnectionDiagnostics.report(app)
        assertTrue(trace.contains("event=LAUNCH_FAILED"))
        assertTrue(trace.contains("event=THROTTLED"))
        assertFalse(trace.contains("private-phone-name"))
        assertFalse(trace.contains("secret-key"))
    }

    @Test fun absentDeviceAndSelectionAreDistinctAndDoNotLaunch() {
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
        val receiver = PhoneBluetoothReceiver()
        receiver.onReceive(app, Intent(BluetoothDevice.ACTION_ACL_CONNECTED))
        app.getSharedPreferences("diplay", 0).edit().remove("phone_address").commit()
        receiver.onReceive(app, event(selected))
        val trace = ConnectionDiagnostics.report(app)
        assertTrue(trace.contains("event=NO_DEVICE"))
        assertTrue(trace.contains("event=NO_SELECTION"))
    }

}
