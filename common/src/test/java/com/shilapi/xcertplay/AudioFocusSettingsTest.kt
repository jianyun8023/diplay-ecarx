package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "en-w1920dp-h1080dp-land-mdpi", manifest = Config.NONE)
class AudioFocusSettingsTest {
    @Test fun transientMuteIsOneDependentOptionAndRetainsPreferenceWhenFocusIsOff() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        assertFalse(AirPlayPersistence.loadDisableMediaKeyAudioFocus(activity))
        AirPlayPersistence.saveAudioFocusEnabled(activity, false)
        AirPlayPersistence.saveAudioFocusAutoYield(activity, false)
        val page = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("audioFocusControls", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, page)
        activity.setContentView(page)
        val switches = views(page).filterIsInstance<Switch>().toList()
        assertEquals(4, switches.size)
        val disableMediaKeyFocus = switches.single {
            it.contentDescription == activity.getString(R.string.settings_disable_media_key_audio_focus)
        }
        val focus = switches.single { it.contentDescription == activity.getString(R.string.contrib_audio_home_toggle_audio_focus) }
        val mute = switches.single { it.contentDescription == activity.getString(R.string.audio_focus_auto_yield) }
        val dependent = mute.parent.parent as View
        assertEquals(View.GONE, dependent.visibility)
        disableMediaKeyFocus.isChecked = true
        assertTrue(AirPlayPersistence.loadDisableMediaKeyAudioFocus(activity))
        assertEquals(View.GONE, dependent.visibility)
        focus.isChecked = true
        assertEquals(View.VISIBLE, dependent.visibility)
        assertFalse(mute.isChecked)
        mute.isChecked = true
        focus.isChecked = false
        assertEquals(View.GONE, dependent.visibility)
        assertTrue(AirPlayPersistence.loadAudioFocusAutoYield(activity))
        assertFalse(AirPlayPersistence.loadAudioFocusEnabled(activity))
        focus.isChecked = true
        assertTrue(mute.isChecked)
    }

    @Test fun audioRoutingAndFocusDefaultOnAndPreserveExplicitOptOut() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        assertTrue(AirPlayPersistence.loadAdvancedAudioChannelMapping(activity))
        assertTrue(AirPlayPersistence.loadAudioFocusEnabled(activity))
        assertTrue(AirPlayPersistence.loadNavigationPromptAudioFocus(activity))
        assertEquals(0, AirPlayPersistence.loadMediaAudioChannel(activity))
        assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(activity))
        val page = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("audioFocusControls", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, page)
        activity.setContentView(page)
        val switches = views(page).filterIsInstance<Switch>().toList()
        val focus = switches.single { it.contentDescription == activity.getString(R.string.contrib_audio_home_toggle_audio_focus) }
        val prompts = switches.single {
            it.contentDescription == activity.getString(R.string.settings_navigation_prompt_audio_focus)
        }
        val mute = switches.single { it.contentDescription == activity.getString(R.string.audio_focus_auto_yield) }
        assertSame(mute.parent.parent, prompts.parent.parent)
        assertEquals(View.VISIBLE, (prompts.parent.parent as View).visibility)
        assertTrue(focus.isChecked)
        assertTrue(prompts.isChecked)
        focus.isChecked = false
        assertTrue(AirPlayPersistence.loadNavigationPromptAudioFocus(activity))
        prompts.isChecked = false
        assertFalse(AirPlayPersistence.loadNavigationPromptAudioFocus(activity))
        AirPlayPersistence.saveAdvancedAudioChannelMapping(activity, false)
        assertFalse(AirPlayPersistence.loadAdvancedAudioChannelMapping(activity))
        assertFalse(AirPlayPersistence.loadAudioFocusEnabled(activity))
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
