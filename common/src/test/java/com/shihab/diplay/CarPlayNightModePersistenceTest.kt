package com.shihab.diplay

import android.content.Context
import android.content.pm.PackageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CarPlayNightModePersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun clearPreferences() { prefs.edit().clear().apply() }

    @Test fun freshInstallAndUnknownValuesFollowSystem() {
        assertEquals(CarPlayNightMode.SYSTEM, AirPlayPersistence.loadCarPlayNightMode(context))
        prefs.edit().putString("carplay_night_mode", "future-mode").apply()
        assertEquals(CarPlayNightMode.SYSTEM, AirPlayPersistence.loadCarPlayNightMode(context))
    }

    @Test fun thresholdDefaultAndCustomValuePersist() {
        assertEquals(AmbientLightThreshold(30), AirPlayPersistence.loadAmbientLightThreshold(context))
        AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.AMBIENT)
        AirPlayPersistence.saveAmbientLightThreshold(context, AmbientLightThreshold(200))
        assertEquals(AmbientLightThreshold(200), AirPlayPersistence.loadAmbientLightThreshold(context))
        assertEquals(CarPlayNightMode.AMBIENT, AirPlayPersistence.loadCarPlayNightMode(context))
        AirPlayPersistence.saveAmbientLightThreshold(context, AmbientLightThreshold())
        assertEquals(AmbientLightThreshold(), AirPlayPersistence.loadAmbientLightThreshold(context))
    }

    @Test fun invalidStoredThresholdRestoresTheDefault() {
        for (lux in listOf(-1, 0, 200_001)) {
            prefs.edit().putInt("ambient_lux_threshold", lux).apply()
            assertEquals(AmbientLightThreshold(), AirPlayPersistence.loadAmbientLightThreshold(context))
        }
    }

    @Test fun allModesRoundTripWithoutChangingOtherPreferences() {
        AirPlayPersistence.saveFps(context, 60)
        for (mode in CarPlayNightMode.entries) {
            AirPlayPersistence.saveCarPlayNightMode(context, mode)
            assertEquals(mode, AirPlayPersistence.loadCarPlayNightMode(context))
            assertEquals(60, AirPlayPersistence.loadFps(context))
        }
    }
    @Test fun scheduleTimesPersistAndInvalidStoredMinutesUseDefaults() {
        assertEquals(CarPlayNightSchedule(), AirPlayPersistence.loadCarPlayNightSchedule(context))
        val custom = CarPlayNightSchedule(19 * 60 + 30, 5 * 60 + 15)
        AirPlayPersistence.saveCarPlayNightSchedule(context, custom)
        assertEquals(custom, AirPlayPersistence.loadCarPlayNightSchedule(context))
        prefs.edit().putInt("carplay_night_start_minute", -1)
            .putInt("carplay_night_end_minute", 24 * 60).apply()
        assertEquals(CarPlayNightSchedule(), AirPlayPersistence.loadCarPlayNightSchedule(context))
    }
    @Test fun existingSavedDefaultsAreNotOverwritten() {
        prefs.edit().putInt("ambient_delay_seconds", 5).putInt("ambient_lux_threshold", 50).commit()
        assertEquals(5, AirPlayPersistence.loadAmbientDelaySeconds(context))
        assertEquals(AmbientLightThreshold(50), AirPlayPersistence.loadAmbientLightThreshold(context))
    }

    @Test fun transitionDelayPersistsIncludingImmediateSwitching() {
        // Immediate by default: the body sensor already debounces; the delay is an opt-in.
        assertEquals(0, AirPlayPersistence.loadAmbientDelaySeconds(context))
        AirPlayPersistence.saveAmbientDelaySeconds(context, 7)
        assertEquals(7, AirPlayPersistence.loadAmbientDelaySeconds(context))
        AirPlayPersistence.saveAmbientDelaySeconds(context, 0)
        assertEquals(0, AirPlayPersistence.loadAmbientDelaySeconds(context))
    }

    @Test fun ecarxHidesAndroidThemeAndAppliesItsSavedValueAsTheHeadUnit() {
        assertEquals(
            listOf(CarPlayNightMode.VEHICLE, CarPlayNightMode.AMBIENT, CarPlayNightMode.SCHEDULE,
                CarPlayNightMode.DAY, CarPlayNightMode.NIGHT),
            carPlayNightModeChoices(ecarx = true),
        )
        assertEquals(CarPlayNightMode.SYSTEM, carPlayNightModeChoices(ecarx = false).first())
        assertEquals(CarPlayNightMode.VEHICLE, effectiveCarPlayNightMode(null, ecarx = true))
        assertEquals(CarPlayNightMode.VEHICLE, effectiveCarPlayNightMode("system", ecarx = true))
        assertEquals(CarPlayNightMode.AMBIENT, effectiveCarPlayNightMode("ambient", ecarx = true))
        assertEquals(CarPlayNightMode.SYSTEM, effectiveCarPlayNightMode("system", ecarx = false))
    }

    @Test fun ecarxHeadUnitLoadsTheMigratedChoiceWithoutRewritingTheStoredKey() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = "com.ecarx.xui.service" })
        assertEquals(CarPlayNightMode.VEHICLE, AirPlayPersistence.loadCarPlayNightMode(context))
        AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.SYSTEM)
        assertEquals(CarPlayNightMode.VEHICLE, AirPlayPersistence.loadCarPlayNightMode(context))
        assertEquals("system", AirPlayPersistence.loadSavedCarPlayNightModeKey(context))
        AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.SCHEDULE)
        assertEquals(CarPlayNightMode.SCHEDULE, AirPlayPersistence.loadCarPlayNightMode(context))
    }

    @Test fun recentNightIsKeptPerModeForThirtyMinutes() {
        val saved = 1_000_000L
        assertNull(AirPlayPersistence.loadRecentNight(context, CarPlayNightMode.VEHICLE, saved))
        AirPlayPersistence.saveRecentNight(context, CarPlayNightMode.VEHICLE, true, saved)
        AirPlayPersistence.saveRecentNight(context, CarPlayNightMode.AMBIENT, false, saved)
        assertEquals(true, AirPlayPersistence.loadRecentNight(context, CarPlayNightMode.VEHICLE, saved + 30 * 60_000L))
        assertEquals(false, AirPlayPersistence.loadRecentNight(context, CarPlayNightMode.AMBIENT, saved + 1))
        assertNull(AirPlayPersistence.loadRecentNight(context, CarPlayNightMode.VEHICLE, saved + 30 * 60_000L + 1))
        // A clock that went backwards is not trusted either.
        assertNull(AirPlayPersistence.loadRecentNight(context, CarPlayNightMode.VEHICLE, saved - 1))
    }

    @Test fun onlyDayNightPreferencesTriggerTheLiveHostUpdate() {
        for (key in listOf("carplay_night_mode", "carplay_night_start_minute", "carplay_night_end_minute",
            "ambient_delay_seconds", "ambient_lux_threshold")) {
            assertTrue(key, AirPlayPersistence.isCarPlayNightSettingKey(key))
        }
        assertFalse(AirPlayPersistence.isCarPlayNightSettingKey("carplay_recent_night_vehicle"))
        assertFalse(AirPlayPersistence.isCarPlayNightSettingKey(null))
    }
}
