package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NavigationPromptFocusPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test fun freshAndExistingAudioFocusUsersRemainOptedOut() {
        assertFalse(AirPlayPersistence.loadNavigationPromptAudioFocus(context))
        AirPlayPersistence.saveAudioFocusEnabled(context, true)
        assertFalse(AirPlayPersistence.loadNavigationPromptAudioFocus(context))
    }

    @Test fun promptChoicePersistsIndependentlyOfMasterAndAutoYield() {
        AirPlayPersistence.saveNavigationPromptAudioFocus(context, true)
        assertTrue(AirPlayPersistence.loadNavigationPromptAudioFocus(context))
        assertFalse(AirPlayPersistence.loadAudioFocusEnabled(context))
        assertTrue(AirPlayPersistence.loadAudioFocusAutoYield(context))
        AirPlayPersistence.saveAudioFocusEnabled(context, true)
        AirPlayPersistence.saveAudioFocusAutoYield(context, false)
        AirPlayPersistence.saveAudioFocusEnabled(context, false)
        assertTrue(AirPlayPersistence.loadNavigationPromptAudioFocus(context))
        AirPlayPersistence.saveNavigationPromptAudioFocus(context, false)
        assertFalse(AirPlayPersistence.loadNavigationPromptAudioFocus(context))
        assertFalse(AirPlayPersistence.loadAudioFocusAutoYield(context))
    }
}
