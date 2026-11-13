package com.shihab.diplay

import com.shihab.diplay.backup.SettingsBackupCodec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class MediaSessionSettingsTest {
    @Test fun siriSettingsHaveDefaultsAndRoundTripThroughBackup() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("diplay_media_session", 0)
        prefs.edit().clear().commit()
        assertTrue(MediaSessionSettings.wheelSiriEnabled(app))
        assertEquals(VoiceKeyPress.SHORT, MediaSessionSettings.voiceKeyPress(app))
        assertEquals(HySiriKey.TOGGLE_155_156, MediaSessionSettings.hySiriKey(app))
        MediaSessionSettings.setWheelSiriEnabled(app, false)
        MediaSessionSettings.setVoiceKeyPress(app, VoiceKeyPress.LONG)
        MediaSessionSettings.setHySiriKey(app, HySiriKey.TOGGLE_312_303)
        val backup = SettingsBackupCodec.export(app, "test")
        prefs.edit().clear().commit()
        SettingsBackupCodec.import(app, backup)
        assertFalse(MediaSessionSettings.wheelSiriEnabled(app))
        assertEquals(VoiceKeyPress.LONG, MediaSessionSettings.voiceKeyPress(app))
        assertEquals(HySiriKey.TOGGLE_312_303, MediaSessionSettings.hySiriKey(app))
    }
    @Test fun modeDefaultsToEcarxAndIsIncludedInBackup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("diplay_media_session", 0).edit().clear().commit()
        assertEquals(MediaSessionMode.ECARX, MediaSessionSettings.mode(app))
        MediaSessionSettings.setMode(app, MediaSessionMode.ECARX)
        assertEquals(MediaSessionMode.ECARX, MediaSessionSettings.mode(app))
        val backup = JSONObject(String(SettingsBackupCodec.export(app, "test")))
        assertTrue(backup.getJSONObject("settings").getJSONObject("diplay_media_session").getBoolean("ecarx"))
        MediaSessionSettings.setMode(app, MediaSessionMode.ANDROID)
        assertEquals(MediaSessionMode.ANDROID, MediaSessionSettings.mode(app))
    }
}
