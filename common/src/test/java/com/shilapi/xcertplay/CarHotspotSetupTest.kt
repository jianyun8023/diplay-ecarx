package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering.Result
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarHotspotSetupTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("diplay_car_hotspot", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun freshInstallAndExistingHotspotConfigurationRemainOptedOut() {
        assertFalse(CarHotspotSettings.enabled(context))
        configureHotspot()
        assertFalse(eligible())
    }

    @Test fun startupCanReadActualCredentialsAndStillRequiresNoExistingSession() {
        configureHotspot()
        CarHotspotSettings.setEnabled(context, true)
        AirPlayPersistence.saveManualHotspotSsid(context, "")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "")
        assertTrue(eligible())
        assertFalse(CarHotspotSetup.shouldStartOnLaunch(context, hasSession = true))
        AirPlayPersistence.saveManualHotspotPassphrase(context, "short")
        assertTrue(eligible())
    }

    @Test fun successUsesExistingHotspotOrStartsWithoutWaitingForCellular() {
        var enabled = true
        var starts = 0
        val phases = mutableListOf<CarHotspotSetup.Phase>()
        fun prepare() = CarHotspotSetup.prepare(30_000, { false }, phases::add, {},
            hotspotEnabled = { enabled }, networkReady = { false },
            enable = { starts++; Result.READY }, clock = { 0L }, pause = { fail("must not wait") })
        assertEquals(Result.READY, prepare())
        assertEquals(0, starts)
        enabled = false
        assertEquals(Result.READY, prepare())
        assertEquals(1, starts)
        assertFalse(phases.contains(CarHotspotSetup.Phase.WAITING_FOR_NETWORK))
    }

    @Test fun cellularReadinessAllowsOnlyOneRetryWithinTheOriginalBudget() {
        var now = 0L
        val budgets = mutableListOf<Long>()
        val result = CarHotspotSetup.prepare(30_000, { false }, {}, {},
            hotspotEnabled = { false }, networkReady = { now >= 12_000 },
            enable = { remaining ->
                budgets += remaining
                if (budgets.size == 1) { now += remaining; Result.TIMED_OUT } else Result.FAILED
            }, clock = { now }, pause = { now += it })
        assertEquals(Result.FAILED, result)
        assertEquals(listOf(10_000L, 18_000L), budgets)

        now = 0
        val timedOut = CarHotspotSetup.prepare(500, { false }, {}, {},
            hotspotEnabled = { false }, networkReady = { false }, enable = { Result.FAILED },
            clock = { now }, pause = { now += it })
        assertEquals(Result.TIMED_OUT, timedOut)
        assertEquals(500L, now)
    }

    @Test fun permissionFailureDoesNotRetryAndCancellationStopsNetworkWait() {
        var cancelled = false
        var starts = 0
        var outcome = Result.PERMISSION_REQUIRED
        fun prepare() = CarHotspotSetup.prepare(30_000, { cancelled }, {}, {},
            hotspotEnabled = { false }, networkReady = { false }, enable = { starts++; outcome },
            clock = { 0L }, pause = { cancelled = true })
        assertEquals(Result.PERMISSION_REQUIRED, prepare())
        assertEquals(1, starts)
        outcome = Result.FAILED
        assertEquals(Result.CANCELLED, prepare())
        assertEquals(2, starts)
    }

    @Test fun wiredModeNeverPreparesAHotspotEvenWhenTheChoiceIsSaved() {
        configureHotspot()
        CarHotspotSettings.setEnabled(context, true)
        AirPlayPersistence.saveWirelessEnabled(context, false)
        assertFalse(eligible())
        assertFalse(CarHotspotSettings.shouldEnable(context, false, WirelessHotspotMode.MANUAL))
        assertTrue(CarHotspotSettings.enabled(context))
    }

    @Test fun wifiDirectAndLocalOnlyHotspotAreNotAutomaticallyManaged() {
        configureHotspot()
        CarHotspotSettings.setEnabled(context, true)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        assertFalse(eligible())
        assertFalse(CarHotspotSettings.shouldEnable(context, true, WirelessHotspotMode.LOCAL_ONLY_HOTSPOT))
    }

    @Test fun disablingTheChoiceCancelsFutureStartupWithoutChangingConnectionSettings() {
        configureHotspot()
        CarHotspotSettings.setEnabled(context, true)
        assertTrue(eligible())
        CarHotspotSettings.setEnabled(context, false)
        assertFalse(eligible())
        assertEquals("Car hotspot", AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals("12345678", AirPlayPersistence.loadManualHotspotPassphrase(context))
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    private fun eligible() = CarHotspotSetup.shouldStartOnLaunch(context, hasSession = false)

    private fun configureHotspot() {
        AirPlayPersistence.saveWirelessEnabled(context, true)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(context, "Car hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "12345678")
    }
}
