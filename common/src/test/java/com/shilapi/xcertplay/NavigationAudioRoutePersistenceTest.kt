package com.shilapi.xcertplay

import com.shilapi.xcertplay.backup.SettingsBackupCodec
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NavigationAudioRoutePersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", 0)

    @Before fun clearSettings() { prefs.edit().clear().commit() }

    @Test fun bothContentModesSelectAutomaticRoutingAndSurviveBackup() {
        assertFalse(AirPlayPersistence.loadNavigationOemContent(context))
        for (oem in listOf(true, false)) {
            AirPlayPersistence.saveNavigationAudioChannel(context, 13)
            AirPlayPersistence.saveNavigationOemContent(context, oem)
            assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))
            val document = SettingsBackupCodec.export(context, "test")
            prefs.edit().clear().commit()
            assertFalse(SettingsBackupCodec.import(context, document).invalid)
            assertEquals(oem, AirPlayPersistence.loadNavigationOemContent(context))
            assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))
        }
    }

    @Test fun formerLegacy13UsesNavigationAttributesAfterUpgrade() {
        for (key in listOf("navigation_audio_channel", "navigation_stream_type")) {
            prefs.edit().clear().putInt(key, 13).putInt("media_audio_channel", 3).commit()
            assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))
            assertEquals(0, prefs.getInt("navigation_audio_channel", -1))
            assertEquals(3, AirPlayPersistence.loadMediaAudioChannel(context))
            AirPlayPersistence.saveNavigationAudioChannel(context, 13)
            assertEquals(13, AirPlayPersistence.loadNavigationAudioChannel(context))
        }
    }

    @Test fun defaultsAndOtherExistingChoicesRemainUnchanged() {
        for (channel in listOf(0, 3, 14, 15)) {
            prefs.edit().clear().putInt("navigation_audio_channel", channel)
                .putInt("navigation_stream_type", 13).commit()
            assertEquals(channel, AirPlayPersistence.loadNavigationAudioChannel(context))
        }
        prefs.edit().clear().commit()
        assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))
        assertTrue(AirPlayPersistence.applySetupNavigationAudioChannelDefault(context, 15))
        assertEquals(15, AirPlayPersistence.loadNavigationAudioChannel(context))
    }

    @Test fun newExplicitAndSetupChoicesAreNotMistakenForOldSettings() {
        AirPlayPersistence.saveNavigationAudioChannel(context, 13)
        assertEquals(13, AirPlayPersistence.loadNavigationAudioChannel(context))
        prefs.edit().clear().commit()
        assertTrue(AirPlayPersistence.applySetupNavigationAudioChannelDefault(context, 13))
        assertEquals(13, AirPlayPersistence.loadNavigationAudioChannel(context))
    }

    @Test fun backupPreservesAChannelChosenAfterMigration() {
        AirPlayPersistence.saveNavigationAudioChannel(context, 13)
        val document = SettingsBackupCodec.export(context, "test")
        prefs.edit().clear().commit()
        assertFalse(SettingsBackupCodec.import(context, document).invalid)
        assertEquals(13, AirPlayPersistence.loadNavigationAudioChannel(context))
    }
}
