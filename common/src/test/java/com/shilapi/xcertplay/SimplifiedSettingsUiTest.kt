package com.shilapi.xcertplay

import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w1920dp-h1080dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SimplifiedSettingsUiTest {
    private lateinit var activity: DiPlayActivity

    @Before fun setup() {
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        shadowOf(activity.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.ecarx.xui.service"
        })
        ReflectionHelpers.setField(activity, "page", "settings")
    }

    @After fun cleanup() {
        shadowOf(activity.packageManager).removePackage("com.ecarx.xui.service")
        activity.finish()
    }

    @Test fun soundOutputDialogShowsMediaAndNavigationTestsAtHeadUnitSize() {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "showSoundOutputTest")
        val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        try {
            val content = dialog.window!!.decorView.findViewById<View>(android.R.id.content)
            val labels = views(content).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            assertTrue(labels.contains(activity.getString(R.string.sound_output_test_media)))
            assertTrue(labels.contains(activity.getString(R.string.sound_output_test_navigation)))
            assertTrue(labels.contains(activity.getString(R.string.sound_output_test_auto_navigation)))
            assertTrue(labels.contains(activity.getString(R.string.sound_output_test_note)))
            // Dialog scroll indicators expect a real Window attach record while Robolectric draws.
            views(content).forEach { it.scrollIndicators = 0 }
            screenshot(content, "sound-output-test")
        } finally { dialog.dismiss() }
    }

    @Test fun customWheelKeysAreCollapsedWithoutChangingSavedAssignments() {
        WheelZoomSettings.setMediaPlayPause(activity, true)
        val root = settings(SettingsCategory.VEHICLE)
        val media = views(root).filterIsInstance<TextView>().first {
            it.contentDescription == activity.getString(R.string.wheel_media_play_pause)
        }
        val details = views(root).filterIsInstance<Button>().single {
            it.text.startsWith(activity.getString(R.string.settings_custom_wheel_keys) + " · ")
        }
        assertFalse(visible(media))
        screenshot(root, "vehicle-collapsed")
        details.performClick()
        assertTrue(visible(media))
        assertTrue(WheelZoomSettings.mediaPlayPause(activity))
        screenshot(root, "vehicle-expanded")
        details.performClick()
        assertFalse(visible(media))
        assertTrue(WheelZoomSettings.mediaPlayPause(activity))
    }

    @Test fun settingsLiveWhereDriversLookForThem() {
        val audio = settings(SettingsCategory.AUDIO)
        assertTrue(hasTitle(audio, R.string.settings_bluetooth_media_yield))
        val tuning = views(audio).filterIsInstance<Button>().single {
            it.text.startsWith(activity.getString(R.string.settings_audio_tuning) + " · ")
        }
        val channel = views(audio).filterIsInstance<Button>().first {
            it.text.startsWith(activity.getString(R.string.contrib_audio_home_media_channel_label))
        }
        assertFalse(visible(channel))
        screenshot(audio, "audio-collapsed")
        tuning.performClick()
        assertTrue(visible(channel))
        screenshot(audio, "audio-expanded")
        val advanced = settings(SettingsCategory.ADVANCED)
        assertFalse(hasTitle(advanced, R.string.settings_bluetooth_media_yield))
        assertTrue(hasTitle(advanced, R.string.settings_external_controller))
        assertTrue(hasTitle(advanced, R.string.mfi_certificate_signing_target))
        assertFalse(hasTitle(advanced, R.string.settings_harman_fullscreen_experimental))
        assertFalse(hasTitle(settings(SettingsCategory.CONNECTION), R.string.mfi_certificate_signing_target))
        val quick = LinearLayout(activity)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "buildQuickSettings",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, quick))
        assertFalse(hasTitle(quick, R.string.report_location_to_iphone))
        assertTrue(hasTitle(settings(SettingsCategory.NAVIGATION), R.string.report_location_to_iphone))
    }

    @Test fun searchOpensTheCollapsedControls() {
        ReflectionHelpers.setField(activity, "settingsSectionFilter", null)
        val result = DiPlayActivity.SettingsSearchResult(
            activity.getString(R.string.wheel_siri_key), SettingsCategory.VEHICLE)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "openSearchResult",
            ReflectionHelpers.ClassParameter.from(DiPlayActivity.SettingsSearchResult::class.java, result))
        val siri = views(activity.window.decorView).filterIsInstance<TextView>().first {
            it.contentDescription == result.title
        }
        assertTrue(visible(siri))
        assertTrue(ReflectionHelpers.getField(activity, "customWheelKeysExpanded"))
    }

    @Test @Config(sdk = [28]) fun carHotspotKeepsReadAndStartTogetherAndModificationUnderMore() {
        AirPlayPersistence.saveWirelessHotspotMode(activity, com.shilapi.xcertplay.orchestration.WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(activity, "Lynk 01")
        AirPlayPersistence.saveManualHotspotPassphrase(activity, "private-password")
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(DiPlayPalette.DARK.surface)
        }
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "wirelessLinkControls",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, root))
        val read = views(root).filterIsInstance<Button>().single {
            it.text == activity.getString(R.string.settings_car_hotspot_config_title)
        }
        val modify = views(root).filterIsInstance<Button>().single {
            it.text == activity.getString(R.string.settings_hotspot_modify)
        }
        assertTrue(visible(read))
        assertFalse(visible(modify))
        assertTrue(hasTitle(root, R.string.auto_car_hotspot_title))
        assertFalse(views(root).filterIsInstance<TextView>().any { it.text.contains("private-password") })
        screenshot(root, "connection-hotspot")
        views(root).filterIsInstance<Button>().single {
            it.text.startsWith(activity.getString(R.string.settings_hotspot_more) + " · ")
        }.performClick()
        assertTrue(visible(modify))
    }

    private fun settings(category: SettingsCategory): LinearLayout {
        ReflectionHelpers.setField(activity, "settingsCategory", category)
        ReflectionHelpers.setField(activity, "settingsSectionFilter",
            SettingsInformationArchitecture.sectionsByCategory.getValue(category))
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(DiPlayPalette.DARK.surface)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "allSettingsSections",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, this))
        }
    }

    private fun hasTitle(root: View, title: Int): Boolean = views(root).filterIsInstance<TextView>().any {
        it.contentDescription == activity.getString(title) || it.text.startsWith(activity.getString(title))
    }

    private fun visible(view: View): Boolean = generateSequence(view) { it.parent as? View }
        .all { it.visibility == View.VISIBLE }

    private fun screenshot(content: View, name: String) {
        (content.parent as? ViewGroup)?.removeView(content)
        val screen = ScrollView(activity).apply { addView(content) }
        screen.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        screen.layout(0, 0, 1920, 1080)
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        screen.draw(Canvas(bitmap))
        val file = File("build/reports/simplified-settings-ui/$name.png").apply { parentFile.mkdirs() }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
