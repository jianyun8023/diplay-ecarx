package com.shilapi.xcertplay

import android.content.Intent
import com.shilapi.xcertplay.ConnectionDiagnostics.Event
import com.shilapi.xcertplay.ConnectionDiagnostics.Source
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ConnectionDiagnosticsTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        app.getSharedPreferences("diplay_connection_decisions", 0).edit().clear().commit()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
    }

    @After fun cleanup() { CarPlayBackgroundSession.clear() }

    @Test fun timelineIsBoundedAndStoresNoPhoneIdentityOrSecret() {
        DiPlayPreferences.savePhone(app, "12:34:56:78:9A:BC", "Private phone name")
        AirPlayPersistence.saveCarBluetoothAudio(app, false)
        repeat(40) { ConnectionDiagnostics.record(app, Source.RETRY, Event.RETRY_SCHEDULED, it, 2_000) }
        val report = ConnectionDiagnostics.report(app)
        assertEquals(32, report.lineSequence().count { it.startsWith("at=") })
        assertFalse(report.contains("generation=7 "))
        assertTrue(report.contains("generation=39 delayMs=2000"))
        assertTrue(report.contains("carBluetoothAudio=false"))
        assertFalse(report.contains("12:34"))
        assertFalse(report.contains("Private phone name"))
        assertEquals("12:34:56:78:9A:BC", DiPlayPreferences.phoneAddress(app))
        assertFalse(AirPlayPersistence.loadCarBluetoothAudio(app))
    }

    @Test fun aclIntentGateRecordsOwnerAndConsumesExtraWithoutStartingAnotherSession() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        DiPlayPreferences.savePhone(app, "12:34:56:78:9A:BC", "Private phone")
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", { done: () -> Unit -> done() })
        val intent = Intent().putExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, true)
        val accepted = DiPlayActivity::class.java.getDeclaredMethod("consumeBluetoothAutoConnectIntent", Intent::class.java)
            .apply { isAccessible = true }.invoke(activity, intent) as Boolean
        assertFalse(accepted)
        assertFalse(intent.hasExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT))
        assertTrue(ConnectionDiagnostics.report(app).contains("source=PHONE_ACL event=OWNER_PRESENT"))
        assertTrue(CarPlayBackgroundSession.hasSession())
    }

    @Test fun setupBlockKeepsConnectionSourceButDoesNotClaimProjectionStarted() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        ReflectionHelpers.setField(activity, "setupError", "Private setup details")
        val connect = DiPlayActivity::class.java.getDeclaredMethod("connectWithSource", Boolean::class.javaPrimitiveType,
            Source::class.java).apply { isAccessible = true }
        connect.invoke(activity, true, Source.PHONE_ACL)
        connect.invoke(activity, true, Source.APP_LAUNCH)
        val report = ConnectionDiagnostics.report(app)
        assertTrue(report.contains("source=PHONE_ACL event=CONNECT_ENTERED"))
        assertTrue(report.contains("source=APP_LAUNCH event=SETUP_BLOCKED"))
        assertFalse(report.contains("event=PROJECTION_REQUESTED"))
        assertFalse(report.contains("Private setup details"))
    }

    @Test fun emptyTraceDoesNotAssertPhoneRadioOrAclWasAbsent() {
        val report = ConnectionDiagnostics.report(app)
        assertTrue(report.contains("does not prove an ACL event was absent"))
        assertFalse(report.contains("phoneBluetoothOff=true"))
    }
}
