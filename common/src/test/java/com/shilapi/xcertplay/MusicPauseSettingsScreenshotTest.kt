package com.shilapi.xcertplay

import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** The two music pause switches in Audio on the current 1920×1080 ECARX head unit. */
@RunWith(RobolectricTestRunner::class)
// SDK 33 ships the platform car service, like the head unit.
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicPauseSettingsScreenshotTest {
    @Test @Config(qualifiers = "zh-rCN-w1920dp-h1080dp-land-mdpi")
    fun switchesAreOffByDefaultAndFitTheCurrentHeadUnit() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        shadowOf(activity.packageManager).installPackage(PackageInfo().apply { packageName = "com.ecarx.xui.service" })
        val output = File("build/reports/music-pause-settings-ui").apply { mkdirs() }
        try {
            val page = render(activity)
            val title = views(page).filterIsInstance<TextView>()
                .single { it.text == activity.getString(R.string.settings_music_pause_door) }
            assertTrue(visible(title))
            for ((trigger, name) in TITLES) {
                val switch = views(page).filterIsInstance<Switch>()
                    .single { it.contentDescription == activity.getString(name) }
                assertFalse("$trigger off by default", switch.isChecked)
                switch.performClick()
                assertTrue(MusicPauseSettings.enabled(activity, trigger))
                switch.performClick()
                assertFalse(MusicPauseSettings.enabled(activity, trigger))
            }

            val width = page.width
            val top = Rect(0, 0, title.width, title.height).also { page.offsetDescendantRectToMyCoords(title, it) }.top
            val frameTop = (top - 400).coerceIn(0, maxOf(0, page.height - 1080))
            val bitmap = Bitmap.createBitmap(width, 1080, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).apply { drawColor(DiPlayPalette.DARK.surface); translate(0f, -frameTop.toFloat()) }.also(page::draw)
                File(output, "music-pause-current-head-unit.png").outputStream().use {
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

    @Test fun otherHeadUnitsDoNotOfferThem() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            val page = render(activity)
            val shown = TITLES.filter { (_, name) -> views(page).any { it is TextView && it.text == activity.getString(name) } }
            assertEquals(emptyList<MusicPauseTrigger>(), shown.map { it.first })
        } finally {
            activity.finish()
        }
    }

    private companion object {
        val TITLES = listOf(
            MusicPauseTrigger.DOOR to R.string.settings_music_pause_door,
            MusicPauseTrigger.ENGINE_OFF to R.string.settings_music_pause_engine_off,
        )
    }

    private fun render(activity: DiPlayActivity): LinearLayout {
        val width = activity.resources.displayMetrics.widthPixels
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DiPlayPalette.DARK.surface)
        }
        ReflectionHelpers.setField(activity, "page", "settings")
        ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.MEDIA_OUTPUT))
        activity.javaClass.getDeclaredMethod("allSettingsSections", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, page)
        page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        page.layout(0, 0, width, page.measuredHeight)
        return page
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
