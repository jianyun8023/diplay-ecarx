package com.shilapi.xcertplay

import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** The CarPlay day/night controls on the current 1920×1080 ECARX head unit. */
@RunWith(RobolectricTestRunner::class)
// SDK 33 ships the platform car service, like the head unit; SDK 29 does not.
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NightModeSettingsScreenshotTest {
    @Test @Config(qualifiers = "zh-rCN-w1920dp-h1080dp-land-mdpi")
    fun outsideLightControlsFitTheCurrentHeadUnit() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        shadowOf(activity.packageManager).installPackage(PackageInfo().apply { packageName = "com.ecarx.xui.service" })
        AirPlayPersistence.saveCarPlayNightMode(activity, CarPlayNightMode.AMBIENT)
        val output = File("build/reports/night-mode-settings-ui").apply { mkdirs() }
        try {
            val width = activity.resources.displayMetrics.widthPixels
            val page = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(DiPlayPalette.DARK.surface)
            }
            ReflectionHelpers.setField(activity, "page", "settings")
            ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.DISPLAY_AND_PERFORMANCE))
            activity.javaClass.getDeclaredMethod("allSettingsSections", LinearLayout::class.java)
                .apply { isAccessible = true }.invoke(activity, page)
            page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            page.layout(0, 0, width, page.measuredHeight)

            val mode = button(page, activity.getString(R.string.carplay_night_mode))
            assertTrue(mode.text.toString(), mode.text.endsWith(activity.getString(R.string.carplay_night_ambient)))
            val delay = button(page, activity.getString(R.string.ambient_delay_title))
            assertTrue(visible(delay))
            val twilightHint = activity.getString(R.string.settings_carplay_night_outside_twilight_hint)
            val usesTwilight = com.shilapi.xcertplay.hud.EcarxTwilightSensor.supported(activity)
            assertTrue("the ECARX frame must show the body twilight sensor variant", usesTwilight)
            assertEquals(usesTwilight, views(page).any { it is TextView && it.text == twilightHint && visible(it) })
            assertEquals(!usesTwilight, views(page).any {
                it is Button && visible(it) && it.text.startsWith(activity.getString(R.string.ambient_light_threshold_title) + " · ")
            })
            assertEquals("controls in one row keep the setting-row height", mode.height, delay.height)

            // A 1920×1080 frame from the day/night title down, as the driver scrolls to it.
            val top = Rect(0, 0, mode.width, mode.height).also { page.offsetDescendantRectToMyCoords(mode, it) }.top
            val frameTop = (top - 48).coerceIn(0, maxOf(0, page.height - 1080))
            val bitmap = Bitmap.createBitmap(width, 1080, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).apply { translate(0f, -frameTop.toFloat()) }.also(page::draw)
                File(output, "carplay-night-outside-light-current-head-unit.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
            for (text in views(page).filterIsInstance<TextView>().filter { visible(it) && it.text.isNotEmpty() }) {
                text.layout?.let { layout ->
                    for (line in 0 until layout.lineCount) {
                        assertEquals("${text.text} ellipsized", 0, layout.getEllipsisCount(line))
                    }
                }
            }
        } finally {
            shadowOf(activity.packageManager).removePackage("com.ecarx.xui.service")
            activity.finish()
        }
    }

    private fun button(root: View, title: String) = views(root).filterIsInstance<Button>().single {
        it.text.startsWith("$title · ")
    }

    /** Detached views never report isShown, so walk the visibility chain instead. */
    private fun visible(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            current = current.parent as? View
        }
        return true
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
