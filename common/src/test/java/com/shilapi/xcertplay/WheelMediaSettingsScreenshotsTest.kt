package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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

/** Optional media-key settings on the current 1920×1080 head unit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w1920dp-h1080dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WheelMediaSettingsScreenshotsTest {
    @Test fun controlsFitTheCurrentHeadUnit() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        WheelZoomSettings.setMediaPlayPause(activity, true)
        try {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            DiPlayActivity::class.java.getDeclaredMethod("wheelKeysSettings", LinearLayout::class.java)
                .apply { isAccessible = true }.invoke(activity, row)
            assertTrue(allViews(row).filterIsInstance<TextView>().any {
                it.text.toString() == activity.getString(R.string.wheel_media_play_pause)
            })
            val width = 1920
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            row.layout(0, 0, width, row.measuredHeight)
            val bitmap = Bitmap.createBitmap(width, row.measuredHeight, Bitmap.Config.ARGB_8888)
            try {
                row.draw(Canvas(bitmap))
                val directory = File("build/reports/wheel-media-ui").apply { mkdirs() }
                FileOutputStream(File(directory, "current-head-unit.png")).use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            activity.finish()
        }
    }

    private fun allViews(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(allViews(view.getChildAt(i)))
    }
}
