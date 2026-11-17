package com.shihab.diplay

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Looper
import com.shihab.diplay.network.CarHotspotStatus
import com.shihab.diplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/**
 * The boot auto-start path itself: which mode waits for the car hotspot, and what the waiting launch
 * does with the screen. The wait's own rules live in `CarHotspotReadyTest` (shared) and
 * `BootReadinessGateTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BootCarPlayLaunchGateTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun configuredForTheBootAutoStart() {
        // The identity is irrelevant here; the boot check only asks whether it is ready.
        ReflectionHelpers.setField(DiPlayBootstrap, "ready", true)
        app.getSharedPreferences("diplay_wireless_startup_timeline", Context.MODE_PRIVATE)
            .edit().clear().commit()
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        AirPlayPersistence.saveWirelessEnabled(app, true)
        DiPlayPreferences.saveAutoConnect(app, true)
        DiPlayPreferences.savePhone(app, "AA:BB:CC:DD:EE:FF", "iPhone")
        AirPlayPersistence.saveManualHotspotSsid(app, "DiPlay-MANUAL")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "diplay-passphrase")
    }

    private fun timelineLines(): List<String> =
        app.getSharedPreferences("diplay_wireless_startup_timeline", Context.MODE_PRIVATE)
            .getString("events", "").orEmpty().lineSequence().filter { it.contains("stage=") }.toList()

    /** The screen is opened from the gate's main-thread callback; pump both until it lands. */
    private fun pumpUntilStartedActivity(): String? {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(app).nextStartedActivity?.let { return it.component?.className }
            Thread.sleep(5)
        }
        return null
    }

    @Test fun anExistingWifiBootLaunchIsNotGated() {
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.EXISTING_WIFI)
        assertTrue(BootCarPlayLaunch.open(app))
        // Opened synchronously: no hotspot wait on a start that has no car hotspot.
        assertEquals(CarPlayHostActivity::class.java.name, shadowOf(app).nextStartedActivity?.component?.className)
        assertTrue(timelineLines().none { it.contains("BOOT_READY_GATE") })
    }

    /** Firmware that hides the AP state: both AP reads fail, so the gate has no evidence either way. */
    @Implements(WifiManager::class)
    class UnobservableApState {
        @Implementation fun getWifiApState(): Int = throw UnsupportedOperationException("AP state hidden")
        @Implementation fun isWifiApEnabled(): Boolean = throw UnsupportedOperationException("AP state hidden")
    }

    @Test @Config(shadows = [UnobservableApState::class])
    fun aCarHotspotBootLaunchWaitsForTheGateBeforeOpeningTheScreen() {
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        // This JVM cannot read the car's AP state, so the gate must fail open at its first sample
        // instead of spending the cap. The gate reads the very same CarHotspotStatus value.
        assertNull("the test environment reports an AP state; the gate would then wait for it",
            CarHotspotStatus.isEnabled(app))
        assertTrue(BootCarPlayLaunch.open(app))
        assertNull("the screen waits for the hotspot gate", shadowOf(app).nextStartedActivity)
        assertEquals(CarPlayHostActivity::class.java.name, pumpUntilStartedActivity())
        val gate = timelineLines().filter { it.contains("stage=BOOT_READY_GATE") }
        assertEquals(gate.toString(), 1, gate.size)
        assertTrue(gate.single(), gate.single().contains("reason=unobservable"))
        assertTrue(gate.single(), gate.single().contains("address=none"))
    }

    @Test @Config(shadows = [UnobservableApState::class])
    fun aSecondBootCallbackDoesNotOpenASecondGatedLaunch() {
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        assertNull(CarHotspotStatus.isEnabled(app))
        assertTrue(BootCarPlayLaunch.open(app, early = true))
        // BOOT_COMPLETED arrives while the gated launch still waits: it must not fall through to Home.
        assertTrue(BootCarPlayLaunch.open(app))
        assertNotNull(pumpUntilStartedActivity())
        shadowOf(Looper.getMainLooper()).idle()
        // One screen start even though both callbacks ran.
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(1, timelineLines().count { it.contains("stage=UNLOCK_DIRECT_HOST") })
    }
}
