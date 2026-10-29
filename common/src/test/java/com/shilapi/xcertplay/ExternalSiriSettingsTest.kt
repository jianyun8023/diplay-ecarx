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
class ExternalSiriSettingsTest {
    @Test fun legacyDisabledChoiceIsPreservedAndNewChoiceTravelsInBackup() {
        val app = RuntimeEnvironment.getApplication()
        val legacy = app.getSharedPreferences("diplay_hy_compatibility", 0)
        legacy.edit().putBoolean("enabled", false).putBoolean("siri_on_launch", true).commit()
        assertFalse(ExternalSiriSettings.enabled(app))
        legacy.edit().putBoolean("enabled", true).commit()
        assertTrue(ExternalSiriSettings.enabled(app))
        ExternalSiriSettings.setEnabled(app, false)
        assertFalse(ExternalSiriSettings.enabled(app))
        val backup = JSONObject(String(SettingsBackupCodec.export(app, "test")))
        assertFalse(backup.getJSONObject("settings").getJSONObject("diplay_external_siri").getBoolean("enabled"))
    }
}
