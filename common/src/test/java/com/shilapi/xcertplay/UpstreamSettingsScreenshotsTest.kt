package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.LinearLayout
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h1080dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpstreamSettingsScreenshotsTest {
    @Test fun updatedControlsFitTheCurrentHeadUnit() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        AirPlayPersistence.saveAudioFocusEnabled(activity, true)
        try {
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
                setBackgroundColor(DiPlayPalette.DARK.surface)
            }
            for (method in listOf("carPlaySizeControl", "frameRateControl", "audioFocusControls")) {
                DiPlayActivity::class.java.getDeclaredMethod(method, LinearLayout::class.java)
                    .apply { isAccessible = true }.invoke(activity, root)
            }
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 1080)
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                assertTrue("Control outside the head-unit viewport", child.bottom <= 1080)
                assertTrue("Control has no space", child.width > 0 && child.height > 0)
            }
            val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val file = File("build/reports/upstream-settings-ui/current-head-unit.png")
                file.parentFile.mkdirs()
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        } finally { activity.finish() }
    }
}
