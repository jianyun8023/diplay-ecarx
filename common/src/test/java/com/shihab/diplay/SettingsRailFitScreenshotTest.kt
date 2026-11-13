package com.shihab.diplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import com.shihab.diplay.host.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every settings destination stays in view on the current head unit, whose own dock leaves
 * DiPlay 1920x944 of the 1920x1080 screen, and again when the bars are hidden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsRailFitScreenshotTest {
    // The head unit's dock leaves a 1920x944 window, shown at the 150% its automatic size picks there.
    @Test @Config(qualifiers = "zh-rCN-w1920dp-h944dp-land-mdpi")
    fun everyDestinationFitsAboveTheHeadUnitDockAndRefitsWhenTheHeightChanges() {
        val app = RuntimeEnvironment.getApplication()
        InterfaceSize.save(app, 150)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        val activity = controller.get()
        try {
            val root = activity.window.decorView
            layout(root, 1920, 944)
            val rail = views(root).filterIsInstance<ScrollView>().first { scroll ->
                views(scroll).any { it.contentDescription == activity.getString(R.string.settings_open_category,
                    activity.getString(R.string.settings_advanced)) }
            }
            assertAllVisible(activity, rail)
            File("build/reports/settings-rail-ui").apply { mkdirs() }.let { capture(root, File(it, "settings-rail-head-unit-dock.png")) }

            // Bars hidden: the rail gets more room and goes back towards the normal spacing.
            layout(root, 1920, 1080)
            assertAllVisible(activity, rail)
            layout(root, 1920, 944)
            assertAllVisible(activity, rail)
        } finally {
            activity.finish()
        }
    }

    private fun assertAllVisible(activity: DiPlayActivity, rail: ScrollView) {
        val card = (rail.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
        val destinations = (0 until card.childCount).map(card::getChildAt)
        assertEquals(activity.getString(R.string.settings_open_category, activity.getString(R.string.settings_advanced)),
            destinations[SettingsCategory.entries.indexOf(SettingsCategory.ADVANCED)].contentDescription)
        assertEquals(SettingsCategory.entries.size, destinations.size)
        assertEquals("rail must not need scrolling", rail.height, rail.getChildAt(0).height.coerceAtMost(rail.height))
        for (destination in destinations) {
            val box = Rect(0, 0, destination.width, destination.height).also { rail.offsetDescendantRectToMyCoords(destination, it) }
            assertTrue("${destination.contentDescription} ends at ${box.bottom} below ${rail.height}", box.bottom <= rail.height)
        }
    }

    /** Lays out at [height], lets the rail refit, then lays out again so the checks see [height]. */
    private fun layout(root: View, width: Int, height: Int) {
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            if (it == 0) shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(height, root.height)
    }

    private fun capture(root: View, file: File) {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
