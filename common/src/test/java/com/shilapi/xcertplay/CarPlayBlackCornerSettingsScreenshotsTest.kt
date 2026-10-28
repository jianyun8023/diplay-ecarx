package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** One native-graphics sandbox for all screenshots, separate from the large host settings suite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w1920dp-h1080dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarPlayBlackCornerSettingsScreenshotsTest {
    @Test fun controlsFitTheCurrentHeadUnit() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            val section = CarPlayHostActivity::class.java.getDeclaredMethod("buildSafeAreaSection")
                .apply { isAccessible = true }.invoke(activity) as ViewGroup
            val labels = descendants(section).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            assertTrue(labels.any { it == activity.getString(R.string.black_corner_overlay) })
            assertTrue(labels.any { it == activity.getString(R.string.black_corner_overlay_description) })
            assertTrue(descendants(section).filterIsInstance<SeekBar>().any {
                it.max == CarPlayBlackCornerOverlay.RADII_DP.lastIndex
            })
            assertTrue(CarPlayBlackCornerOverlay.RADII_DP.contains(CarPlayBlackCornerOverlay.IPHONE_MASK))
            val width = 1920
            section.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            section.layout(0, 0, width, section.measuredHeight)
            val bitmap = Bitmap.createBitmap(width, section.measuredHeight, Bitmap.Config.ARGB_8888)
            try {
                section.draw(Canvas(bitmap))
                val folder = File("build/reports/black-corner-ui").apply { mkdirs() }
                FileOutputStream(File(folder, "current-head-unit.png")).use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            activity.finish()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
