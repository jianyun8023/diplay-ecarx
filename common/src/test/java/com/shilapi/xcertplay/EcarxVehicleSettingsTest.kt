package com.shilapi.xcertplay

import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h1080dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EcarxVehicleSettingsTest {
    @Test fun realVehicleOptInIsOffByDefaultAndOnlyShownOnEcarx() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            assertFalse(AirPlayPersistence.loadEcarxVehicleControls(activity))
            assertEquals(SettingsCategory.ADVANCED, SettingsInformationArchitecture.sectionsByCategory.entries
                .single { SettingsSection.ECARX_VEHICLE_CONTROLS in it.value }.key)
            fun render(): LinearLayout {
                val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                ReflectionHelpers.setField(activity, "page", "settings")
                ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.ECARX_VEHICLE_CONTROLS))
                activity.javaClass.getDeclaredMethod("allSettingsSections", LinearLayout::class.java)
                    .apply { isAccessible = true }.invoke(activity, root)
                root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 1920, 1080)
                return root
            }
            assertTrue(views(render()).none { it is Switch })
            shadowOf(activity.packageManager).installPackage(PackageInfo().apply { packageName = "com.ecarx.xui.service" })
            val root = render()
            // The test app is debuggable, so the car-test themeless switch shows after the vehicle switch.
            val (toggle, themeless) = views(root).filterIsInstance<Switch>().toList()
            assertFalse(themeless.isChecked)
            themeless.performClick()
            assertTrue(AirPlayPersistence.loadThemelessVehicleControls(activity))
            themeless.performClick()
            assertFalse(toggle.isChecked)
            assertTrue(toggle.isEnabled)
            toggle.performClick()
            assertTrue(AirPlayPersistence.loadEcarxVehicleControls(activity))
            toggle.performClick()
            assertFalse(AirPlayPersistence.loadEcarxVehicleControls(activity))
            val output = File("build/reports/ecarx-vehicle-settings-ui").apply { mkdirs() }
            val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            root.setBackgroundColor(DiPlayPalette.DARK.surface)
            root.draw(Canvas(bitmap))
            File(output, "vehicle-controls-1920x1080.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
            for (text in views(root).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE && it.text.isNotEmpty() }) {
                assertTrue("control has no height: ${text.text}", text.height > 0)
                text.layout?.let { layout ->
                    for (line in 0 until layout.lineCount) assertEquals("clipped: ${text.text}", 0, layout.getEllipsisCount(line))
                    assertTrue("text clipped vertically: ${text.text}", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                }
            }
            assertTrue(activity.getString(R.string.settings_ecarx_vehicle_controls_description).contains("下次连接"))
        } finally {
            shadowOf(activity.packageManager).removePackage("com.ecarx.xui.service")
            activity.finish()
        }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
}
