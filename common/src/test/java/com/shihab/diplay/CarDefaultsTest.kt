package com.shihab.diplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarDefaultsTest {
    @Test fun bootAndDirectOutputDefaultOnButKeepSavedChoices() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        app.createDeviceProtectedStorageContext()
            .getSharedPreferences("diplay_boot_startup", 0).edit().clear().commit()

        assertTrue(AirPlayPersistence.loadAutoStartOnBoot(app))
        assertTrue(BootStartupPreferences.loadAutoStartOnBoot(app))
        assertTrue(AirPlayPersistence.loadDirectVideoOutput(app))
        assertFalse(AirPlayPersistence.loadSmoothVideo(app))
        assertFalse(AirPlayPersistence.loadLowLatencyDecoder(app))

        for (enabled in listOf(false, true)) {
            AirPlayPersistence.saveAutoStartOnBoot(app, enabled)
            AirPlayPersistence.saveDirectVideoOutput(app, enabled)
            assertEquals(enabled, AirPlayPersistence.loadAutoStartOnBoot(app))
            assertEquals(enabled, BootStartupPreferences.loadAutoStartOnBoot(app))
            assertEquals(enabled, AirPlayPersistence.loadDirectVideoOutput(app))
        }
    }
}
