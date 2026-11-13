package com.shihab.diplay

import android.os.UserManager
import android.content.Context
import com.shihab.diplay.storage.LegacyPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The renamed prefs file must inherit the user's settings from the historical file, but only once
 *  the user is unlocked (the app is directBootAware and runs before credential storage is ready). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyPreferencesMigrationTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun setUserUnlocked(unlocked: Boolean) {
        val userManager = app.getSystemService(UserManager::class.java)
        shadowOf(userManager).setUserUnlocked(unlocked)
    }

    @Before fun clearPreferences() {
        setUserUnlocked(true)
        for (name in listOf("xcertplay_airplay", "diplay_airplay")) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun copiesLegacyAirPlaySettingsToTheNewFile() {
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
            .putInt("display_fps", 48)
            .putBoolean("hevc_enabled", true)
            .putString("manual_hotspot_ssid", "car")
            .commit()

        LegacyPreferences.migrateAirPlay(app)

        val current = app.getSharedPreferences("diplay_airplay", Context.MODE_PRIVATE)
        assertEquals(48, current.getInt("display_fps", 0))
        assertTrue(current.getBoolean("hevc_enabled", false))
        assertEquals("car", current.getString("manual_hotspot_ssid", null))
        // The legacy file stays for rollback / downgrade.
        assertEquals(48, app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).getInt("display_fps", 0))
    }

    @Test fun keepsNewerValuesAndDoesNotRunTwice() {
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().putInt("display_fps", 48).commit()
        app.getSharedPreferences("diplay_airplay", Context.MODE_PRIVATE).edit().putInt("display_fps", 60).commit()

        LegacyPreferences.migrateAirPlay(app)
        val current = app.getSharedPreferences("diplay_airplay", Context.MODE_PRIVATE)
        assertEquals(60, current.getInt("display_fps", 0))

        // A later user change survives a second migration call (sentinel blocks a re-copy).
        current.edit().putInt("display_fps", 30).commit()
        LegacyPreferences.migrateAirPlay(app)
        assertEquals(30, current.getInt("display_fps", 0))
    }

    @Test fun marksMigrationEvenWithoutLegacyFile() {
        LegacyPreferences.migrateAirPlay(app)
        assertTrue(
            app.getSharedPreferences("diplay_airplay", Context.MODE_PRIVATE)
                .getBoolean("migrated_from_legacy_airplay", false),
        )
    }

    @Test fun doesNotMigrateWhileLocked() {
        setUserUnlocked(false)
        // The direct-boot window: migrateAirPlay must be a no-op and must not touch CE storage.
        LegacyPreferences.migrateAirPlay(app)
        assertFalse(
            app.getSharedPreferences("diplay_airplay", Context.MODE_PRIVATE)
                .getBoolean("migrated_from_legacy_airplay", false),
        )
    }

    @Test fun migratesWhenUnlockHappensBetweenCheckAndRegistration() {
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
            .putInt("display_fps", 48)
            .commit()
        // Model the race the reviewer reported: the user unlocks after the initial "is it unlocked?"
        // check but at/just after registration, so the ACTION_USER_UNLOCKED broadcast is never
        // delivered to us. The re-check after registering must catch it and migrate synchronously.
        // The predicate is locked on the first (pre-check) call and unlocked on every later call.
        var calls = 0
        val isUnlocked: (Context) -> Boolean = { calls += 1; calls > 1 }

        LegacyPreferences.ensureMigrated(app, isUnlocked)

        val current = app.getSharedPreferences("diplay_airplay", Context.MODE_PRIVATE)
        assertEquals(48, current.getInt("display_fps", 0))
        assertTrue(current.getBoolean("migrated_from_legacy_airplay", false))
    }
}
