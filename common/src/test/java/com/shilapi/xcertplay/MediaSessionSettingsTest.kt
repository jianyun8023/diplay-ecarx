package com.shilapi.xcertplay

import com.shilapi.xcertplay.backup.SettingsBackupCodec
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
    @Test fun modeIsIndependentOfExternalSiriAndIncludedInBackup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("diplay_media_session", 0).edit().clear().commit()
        assertEquals(MediaSessionMode.ANDROID, MediaSessionSettings.mode(app))
        ExternalSiriSettings.setEnabled(app, true)
        assertEquals(MediaSessionMode.ANDROID, MediaSessionSettings.mode(app))
        MediaSessionSettings.setMode(app, MediaSessionMode.ECARX)
        ExternalSiriSettings.setEnabled(app, false)
        assertEquals(MediaSessionMode.ECARX, MediaSessionSettings.mode(app))
        val backup = JSONObject(String(SettingsBackupCodec.export(app, "test")))
        assertTrue(backup.getJSONObject("settings").getJSONObject("diplay_media_session").getBoolean("ecarx"))
        MediaSessionSettings.setMode(app, MediaSessionMode.ANDROID)
        assertEquals(MediaSessionMode.ANDROID, MediaSessionSettings.mode(app))
    }
}
