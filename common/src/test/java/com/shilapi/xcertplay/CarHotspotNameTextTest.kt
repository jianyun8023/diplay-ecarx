package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What DiPlay says about the car hotspot before and after its name is saved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1920dp-h1080dp-land-mdpi")
class CarHotspotNameTextTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null

    @Before fun setUp() {
        clearSettings()
        // The car hotspot is off, as on a head unit that has just been switched on.
        context.sendStickyBroadcast(Intent("android.net.wifi.WIFI_AP_STATE_CHANGED")
            .putExtra("wifi_state", 11).putExtra("wifi_ap_mode", 1))
    }

    @After fun tearDown() {
        activity?.finish()
        AppAppearanceRuntime.resetForTest()
        clearSettings()
    }

    private fun clearSettings() {
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    private fun saveHotspot(name: String) {
        AirPlayPersistence.saveManualHotspotSsid(context, name)
        AirPlayPersistence.saveManualHotspotPassphrase(context, "12345678")
    }

    private fun open(page: String? = null): DiPlayActivity = Robolectric.buildActivity(
        DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).apply { page?.let { putExtra("page", it) } },
    ).setup().get().also { activity = it }

    private fun texts(screen: DiPlayActivity): List<String> =
        descendants(screen.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    @Test fun theHomePageDoesNotQuoteAHotspotNameThatWasNeverSaved() {
        val home = open()
        val shown = texts(home)
        assertTrue(shown.toString(), home.getString(R.string.hotspot_details_off) in shown)
        assertFalse(shown.toString(), home.getString(R.string.msg_car_hotspot_off, "") in shown)
        assertFalse(shown.toString(), shown.any { "“”" in it })
    }

    @Test fun theHomePageNamesASavedHotspotThatIsOff() {
        saveHotspot("Car 5G")
        val home = open()
        assertTrue(texts(home).toString(), home.getString(R.string.msg_car_hotspot_off, "Car 5G") in texts(home))
    }

    @Test fun connectionSetupAsksForTheDetailsUntilTheyAreSaved() {
        val setup = open("connection")
        val shown = texts(setup)
        assertTrue(shown.toString(), setup.getString(R.string.save_hotspot_details_and_use_this_mode) in shown)
        val manual = descendants(setup.window.decorView).filterIsInstance<Button>().single {
            it.text == setup.getString(R.string.save_hotspot_details_and_use_this_mode)
        }
        assertTrue("Manual setup must already be expanded when details are missing", manual.isShown)
        // Nothing is saved, so there is no hotspot to edit and nothing to call saved.
        assertFalse(shown.toString(), shown.any { it.startsWith(setup.getString(R.string.edit_saved_hotspot_prefix)) })
        assertFalse(shown.toString(), setup.getString(R.string.hotspot_details_saved) in shown)
    }

    // A plain space at the end of a string resource is dropped; Traditional Chinese read "… ·Car 5G".
    @Test fun theNameIsSetApartFromTheButtonTitleInEveryLanguage() {
        for (tag in listOf("en", "ar", "es", "ja", "ru", "uk", "zh-CN", "zh-TW")) {
            val localized = context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
            assertTrue(tag, localized.getString(R.string.edit_saved_hotspot_prefix).endsWith(" · "))
        }
    }

    @Test fun connectionSetupOffersToEditASavedHotspotByItsName() {
        saveHotspot("Car 5G")
        val setup = open("connection")
        val shown = texts(setup)
        assertTrue(shown.toString(), "Car 5G" in shown)
        assertTrue(shown.toString(), setup.getString(R.string.edit_saved_hotspot_prefix) + "Car 5G" in shown)
        val edit = descendants(setup.window.decorView).filterIsInstance<Button>().single {
            it.text.toString() == setup.getString(R.string.edit_saved_hotspot_prefix) + "Car 5G"
        }
        assertFalse("Saved details belong under More", edit.isShown)
        descendants(setup.window.decorView).filterIsInstance<Button>().single {
            it.text.startsWith(setup.getString(R.string.settings_hotspot_more) + " · ")
        }.performClick()
        assertTrue(edit.isShown)
        assertTrue(shown.toString(), setup.getString(R.string.settings_manual_hotspot_state_unknown) in shown)
        assertFalse(shown.toString(), setup.getString(R.string.save_hotspot_details_and_use_this_mode) in shown)
    }
}
