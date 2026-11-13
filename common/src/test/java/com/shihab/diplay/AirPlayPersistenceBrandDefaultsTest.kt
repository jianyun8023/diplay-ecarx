package com.shihab.diplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class AirPlayPersistenceBrandDefaultsTest {
    private val preferences
        get() = RuntimeEnvironment.getApplication()
            .getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Test
    fun missingAndLegacyDefaultValuesUseLynkCoBranding() {
        val context = RuntimeEnvironment.getApplication()
        preferences.edit().clear().commit()

        assertEquals("Lynk&Co", AirPlayPersistence.loadManufacturer(context))
        assertEquals("Lynk&Co", AirPlayPersistence.loadModel(context))
        assertEquals("Lynk&Co", AirPlayPersistence.loadOemLabel(context))

        // Older builds could persist their former DiPlay defaults when settings were saved.
        preferences.edit()
            .putString("manufacturer", "DiPlay")
            .putString("model", "DiPlay")
            .putString("oem_label", "")
            .commit()

        assertEquals("Lynk&Co", AirPlayPersistence.loadManufacturer(context))
        assertEquals("Lynk&Co", AirPlayPersistence.loadModel(context))
        assertEquals("Lynk&Co", AirPlayPersistence.loadOemLabel(context))
    }

    @Test
    fun explicitCustomIdentityValuesArePreserved() {
        val context = RuntimeEnvironment.getApplication()
        preferences.edit()
            .putString("manufacturer", "Toyota")
            .putString("model", "Head Unit X")
            .putString("oem_label", "DiPlay")
            .commit()

        assertEquals("Toyota", AirPlayPersistence.loadManufacturer(context))
        assertEquals("Head Unit X", AirPlayPersistence.loadModel(context))
        // Unlike manufacturer/model, the OEM label was not a persisted DiPlay default.
        assertEquals("DiPlay", AirPlayPersistence.loadOemLabel(context))
    }
}
