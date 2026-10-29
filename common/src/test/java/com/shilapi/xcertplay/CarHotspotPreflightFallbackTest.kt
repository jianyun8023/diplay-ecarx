package com.shilapi.xcertplay

import android.net.wifi.WifiManager
import android.os.Looper
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering.Result
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowWifiManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29], qualifiers = "en-w1920dp-h1080dp-land-mdpi", shadows = [CarHotspotPreflightFallbackTest.ApState::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CarHotspotPreflightFallbackTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var owner: ActivityController<DiPlayActivity>
    private lateinit var activity: DiPlayActivity

    @Before fun setup() {
        for (name in listOf("diplay", "xcertplay_airplay", "diplay_car_hotspot", "diplay_connection_decisions")) {
            app.getSharedPreferences(name, 0).edit().clear().commit()
        }
        ApState.enabled = null
        owner = Robolectric.buildActivity(DiPlayActivity::class.java)
        activity = owner.get()
        owner.setup()
        ReflectionHelpers.setField(activity, "setupError", null)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(app, "Car hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "12345678")
        CarHotspotSettings.setEnabled(app, true)
    }

    @After fun cleanup() {
        CarHotspotSettings.setEnabled(app, false)
        owner.pause().stop().destroy()
    }

    @Test fun unknownStateWithUnsupportedControlReachesManualValidationWithoutClaimingReady() {
        attempt(Result.UNSUPPORTED)
        assertTrue(report().contains("event=PHONE_SELECTION_REQUIRED"))
        assertEquals(Result.UNSUPPORTED, ReflectionHelpers.getField<Result>(activity, "hotspotStartupResult"))
        assertNull(ReflectionHelpers.getField<CarHotspotSetup.Phase?>(activity, "hotspotStartupPhase"))
    }

    @Test fun fallbackStillRejectsMissingCredentials() {
        AirPlayPersistence.saveManualHotspotPassphrase(app, "short")
        attempt(Result.UNSUPPORTED)
        assertTrue(report().contains("event=HOTSPOT_SETUP_REQUIRED"))
        assertFalse(report().contains("event=PHONE_SELECTION_REQUIRED"))
    }

    @Test fun knownOffAndGenuineFailuresNeverContinueToConnect() {
        ApState.enabled = false
        attempt(Result.UNSUPPORTED)
        assertFalse(report().contains("event=CONNECT_ENTERED"))
        ApState.enabled = null
        for (result in listOf(Result.FAILED, Result.TIMED_OUT, Result.PERMISSION_REQUIRED, Result.CANCELLED)) {
            attempt(result)
            assertFalse("$result must not fall back", report().contains("event=CONNECT_ENTERED"))
        }
    }

    private fun report() = ConnectionDiagnostics.report(activity)

    private fun attempt(result: Result) {
        app.getSharedPreferences("diplay_connection_decisions", 0).edit().clear().commit()
        activity.prepareHotspot = { _, _ -> result }
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "connectWithSource",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
            ReflectionHelpers.ClassParameter.from(ConnectionDiagnostics.Source::class.java, ConnectionDiagnostics.Source.UI_OR_CONTINUATION))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (ReflectionHelpers.getField<Boolean>(activity, "hotspotStartupRunning") && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(ReflectionHelpers.getField(activity, "hotspotStartupRunning"))
    }

    @Implements(WifiManager::class)
    class ApState : ShadowWifiManager() {
        @Implementation fun getWifiApState(): Int = when (enabled) {
            true -> 13
            false -> 11
            null -> throw UnsupportedOperationException("AP state is unavailable")
        }
        @Implementation fun isWifiApEnabled(): Boolean = enabled
            ?: throw UnsupportedOperationException("AP state is unavailable")
        companion object { @Volatile var enabled: Boolean? = null }
    }
}
