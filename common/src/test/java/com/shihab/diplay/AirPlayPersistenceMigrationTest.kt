package com.shihab.diplay

import android.content.Context
import android.os.UserManager
import com.shihab.diplay.storage.LegacyPreferences
import org.junit.After
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AirPlayPersistenceMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun setUserUnlocked(unlocked: Boolean) {
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(unlocked)
    }

    @Before
    fun clearPreferences() {
        setUserUnlocked(true)
        // Also unregister any pending receiver left by a prior test before resetting its files.
        LegacyPreferences.ensureMigrated(context)
        for (name in listOf("xcertplay_airplay", "diplay_airplay")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After
    fun restoreUnlockedState() {
        setUserUnlocked(true)
        LegacyPreferences.ensureMigrated(context)
    }

    @Test
    fun firstAirPlayReadMigratesLegacyIdentityBeforeGeneratingANewOne() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
            .putString("identity_private", "01020304")
            .putString("identity_public", "05060708")
            .putString("pairing_id", "legacy-pairing-id")
            .putInt("display_fps", 48)
            .commit()

        setUserUnlocked(false)
        assertFalse(LegacyPreferences.ensureMigrated(context))

        // Model the warmed Direct Boot process: unlock has happened, but the queued broadcast
        // callback has not run yet. The first settings access must migrate synchronously.
        setUserUnlocked(true)
        AirPlayPersistence.loadIdentity(context)

        val current = context.getSharedPreferences("diplay_airplay", Context.MODE_PRIVATE)
        assertEquals("01020304", current.getString("identity_private", null))
        assertEquals("05060708", current.getString("identity_public", null))
        assertEquals("legacy-pairing-id", current.getString("pairing_id", null))
        assertEquals(48, current.getInt("display_fps", 0))
        assertTrue(current.getBoolean("migrated_from_legacy_airplay", false))
    }

    @Test
    fun airPlayPersistenceDoesNotOpenCredentialPreferencesWhileLocked() {
        setUserUnlocked(false)
        assertFalse(LegacyPreferences.ensureMigrated(context))

        var failedClosed = false
        try {
            AirPlayPersistence.loadIdentity(context)
        } catch (_: IllegalStateException) {
            failedClosed = true
        }
        assertTrue("AirPlay persistence must fail closed until CE storage is available", failedClosed)
    }
}
