package com.shihab.diplay

import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shihab.diplay.host.R
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

    @Test fun aboutHidesUpstreamUpdateControlsAtHeadUnitSize() {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DiPlayPalette.DARK.surface)
        }
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "about",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, root))
        assertTrue(hasTitle(root, R.string.about_diplay))
        assertTrue(hasTitle(root, R.string.backup_open))
        assertFalse(hasTitle(root, R.string.update_check))
        assertFalse(hasTitle(root, R.string.settings_background_update_checks))
        screenshot(root, "about-upstream-updates-disabled")
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

    @Test fun wheelKeyCardKeepsTheSiriSwitchAndDropsTheLearningList() {
        WheelZoomSettings.setMediaPlayPause(activity, true)
        val root = settings(SettingsCategory.VEHICLE)
        // The voice key that opens Siri is the one wheel key DiPlay acts on, so its switch stays.
        assertTrue(hasTitle(root, R.string.wheel_siri_key))
        // The learning list for every other key is not offered at all, instead of sitting there until it
        // times out: on the car those keys never reached either channel.
        assertFalse(hasTitle(root, R.string.settings_custom_wheel_keys))
        assertFalse(hasTitle(root, R.string.settings_generic_wheel_keys))
        assertFalse(hasTitle(root, R.string.ecarx_sw_key_title))
        assertFalse(views(root).filterIsInstance<TextView>().any {
            it.contentDescription == activity.getString(R.string.wheel_media_play_pause)
        })
        // Hiding the UI is not a reset: a mapping saved before the change survives.
        assertTrue(WheelZoomSettings.mediaPlayPause(activity))
        screenshot(root, "vehicle-wheel-keys")
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
        assertTrue(hasTitle(advanced, R.string.settings_ecarx_cluster_mirror_main))
        assertTrue(hasTitle(advanced, R.string.settings_ecarx_cluster_safe_area))
        val navigation = settings(SettingsCategory.NAVIGATION)
        assertTrue(hasTitle(navigation, R.string.center_map_follows_dashboard))
        assertTrue(hasTitle(navigation, R.string.ecarx_navigation_on_instrument_cluster))
        assertFalse(hasTitle(advanced, R.string.ecarx_navigation_on_instrument_cluster))
        assertFalse(hasTitle(navigation, R.string.settings_ecarx_lane_guidance))
        assertFalse(hasTitle(navigation, R.string.settings_ecarx_cluster_safe_area))
        assertFalse(hasTitle(navigation, R.string.settings_ecarx_cluster_mirror_main))
        assertTrue(hasTitle(navigation, R.string.ecarx_instrument_cluster_projection))
        assertFalse(hasTitle(advanced, R.string.ecarx_instrument_cluster_projection))
        screenshot(advanced, "advanced-cluster-mirror")
        screenshot(navigation, "navigation-output-controls")
        assertFalse(hasTitle(advanced, R.string.settings_harman_fullscreen_experimental))
        assertFalse(hasTitle(settings(SettingsCategory.CONNECTION), R.string.mfi_certificate_signing_target))
        val quick = LinearLayout(activity)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "buildQuickSettings",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, quick))
        assertFalse(hasTitle(quick, R.string.report_location_to_iphone))
        assertTrue(hasTitle(settings(SettingsCategory.NAVIGATION), R.string.report_location_to_iphone))
    }

    @Test fun navigationOutputSwitchesSaveIndependentChoices() {
        val root = settings(SettingsCategory.NAVIGATION)
        val controls = views(root).filterIsInstance<android.widget.Switch>().toList()
        val hud = controls.single { it.contentDescription == activity.getString(R.string.ecarx_navigation_on_instrument_cluster) }
        val map = controls.single { it.contentDescription == activity.getString(R.string.ecarx_instrument_cluster_projection) }
        assertTrue(hud.isChecked)
        assertTrue(map.isChecked)
        hud.performClick()
        assertFalse(com.shihab.diplay.hud.EcarxOutputSettings.enabled(activity))
        assertTrue(com.shihab.diplay.hud.EcarxOutputSettings.autoShowMap(activity))
        map.performClick()
        assertFalse(com.shihab.diplay.hud.EcarxOutputSettings.autoShowMap(activity))
        hud.performClick()
        assertTrue(com.shihab.diplay.hud.EcarxOutputSettings.enabled(activity))
        assertFalse(com.shihab.diplay.hud.EcarxOutputSettings.autoShowMap(activity))
        val rebuilt = views(settings(SettingsCategory.NAVIGATION)).filterIsInstance<android.widget.Switch>().toList()
        assertTrue(rebuilt.single { it.contentDescription == hud.contentDescription }.isChecked)
        assertFalse(rebuilt.single { it.contentDescription == map.contentDescription }.isChecked)
    }

    @Test fun nativeWiredPathDefaultsOnAndPreservesOptOutInSettingsBackup() {
        assertTrue(AirPlayPersistence.loadNativeWiredDataPath(activity))
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DiPlayPalette.DARK.surface)
        }
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "renderSections",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, root),
            ReflectionHelpers.ClassParameter.from(Set::class.java, setOf(SettingsSection.ADVANCED_MEDIA)))
        val control = views(root).filterIsInstance<android.widget.Switch>().single {
            it.contentDescription == activity.getString(R.string.settings_native_wired_data_path)
        }
        assertTrue(control.isChecked)
        screenshot(root, "native-wired-data-path")
        control.performClick()
        assertFalse(AirPlayPersistence.loadNativeWiredDataPath(activity))
        val backup = com.shihab.diplay.backup.SettingsBackupCodec.export(activity, "test")
        AirPlayPersistence.saveNativeWiredDataPath(activity, true)
        assertFalse(com.shihab.diplay.backup.SettingsBackupCodec.import(activity, backup).invalid)
        assertFalse(AirPlayPersistence.loadNativeWiredDataPath(activity))
    }

    @Test @Config(sdk = [28]) fun carHotspotBootSwitchSavesWithoutAdb() {
        AirPlayPersistence.saveWirelessHotspotMode(activity, com.shihab.diplay.orchestration.WirelessHotspotMode.MANUAL)
        com.shihab.diplay.network.CarHotspotSettings.setEnabled(activity, true)
        AirPlayPersistence.saveAutoStartOnBoot(activity, false)
        val switch = views(settings(SettingsCategory.CONNECTION)).filterIsInstance<android.widget.Switch>()
            .single { it.contentDescription == activity.getString(R.string.open_after_the_car_starts) }
        switch.performClick()
        assertTrue(AirPlayPersistence.loadAutoStartOnBoot(activity))
        switch.performClick()
        assertFalse(AirPlayPersistence.loadAutoStartOnBoot(activity))
    }

    @Test fun mediaModeLivesInAudioOnly() {
        val root = settings(SettingsCategory.AUDIO)
        assertTrue(hasTitle(root, R.string.settings_media_output_mode))
        assertFalse(hasTitle(settings(SettingsCategory.ADVANCED), R.string.settings_media_output_mode))
        val focused = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(DiPlayPalette.DARK.surface)
        }
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "renderSections",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, focused),
            ReflectionHelpers.ClassParameter.from(Set::class.java, setOf(SettingsSection.MEDIA_OUTPUT)))
        assertTrue(hasTitle(focused, R.string.settings_media_output_mode))
        screenshot(focused, "media-session-mode")
    }

    @Test fun searchFindsTheSiriSwitchWithoutTheLearningList() {
        ReflectionHelpers.setField(activity, "settingsSectionFilter", null)
        val result = DiPlayActivity.SettingsSearchResult(
            activity.getString(R.string.wheel_siri_key), SettingsCategory.VEHICLE)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "openSearchResult",
            ReflectionHelpers.ClassParameter.from(DiPlayActivity.SettingsSearchResult::class.java, result))
        val siri = views(activity.window.decorView).filterIsInstance<TextView>().first {
            it.contentDescription == result.title
        }
        assertTrue(visible(siri))
        assertTrue(views(activity.window.decorView).filterIsInstance<Button>().none {
            it.text.startsWith(activity.getString(R.string.settings_custom_wheel_keys))
        })
    }

    @Test @Config(sdk = [28], shadows = [HotspotWritePermissionShadow::class])
    fun carHotspotKeepsReadAndStartTogetherAndModificationUnderMore() {
        HotspotWritePermissionShadow.allowed = false
        AirPlayPersistence.saveWirelessHotspotMode(activity, com.shihab.diplay.orchestration.WirelessHotspotMode.MANUAL)
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
        screenshot(root, "connection-hotspot-expanded")

        val autoStart = views(root).filterIsInstance<android.widget.Switch>()
            .single { it.contentDescription == activity.getString(R.string.auto_car_hotspot_title) }
        autoStart.isChecked = false
        autoStart.performClick()
        assertTrue(com.shihab.diplay.network.CarHotspotSettings.enabled(activity))
        assertEquals(com.shihab.diplay.network.CarHotspotTethering.Result.PERMISSION_REQUIRED,
            ReflectionHelpers.getField<com.shihab.diplay.network.CarHotspotTethering.Result>(activity, "hotspotStartupResult"))
    }

    @Test @Config(sdk = [28, 33]) fun carHotspotOffersStandardControlsWithoutExperimentalRepair() {
        AirPlayPersistence.saveWirelessHotspotMode(activity, com.shihab.diplay.orchestration.WirelessHotspotMode.MANUAL)
        val root = LinearLayout(activity)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "wirelessLinkControls",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, root))
        assertEquals(android.os.Build.VERSION.SDK_INT == 28, hasTitle(root, R.string.settings_car_hotspot_config_title))
        assertTrue(hasTitle(root, R.string.auto_car_hotspot_title))
        assertTrue(hasTitle(root, R.string.settings_manual_hotspot_title))
        assertFalse(views(root).filterIsInstance<TextView>().any { it.text == "自动加入热点（实验性）" })
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
        val file = File("build/reports/simplified-settings-ui/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
