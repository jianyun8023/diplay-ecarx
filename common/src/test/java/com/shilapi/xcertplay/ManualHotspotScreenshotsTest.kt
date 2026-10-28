package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.network.ManualHotspotControl
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManualHotspotScreenshotsTest {
    @Test @Config(qualifiers = "zh-rCN-w1920dp-h1080dp-land-mdpi")
    fun controlsFitTheCurrentHeadUnit() {
        val name = "current-head-unit"
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val backend = object : ManualHotspotControls.Backend {
            override fun observe() = ManualHotspotControl.State.ON
            override fun capability() = ManualHotspotControl.Capability.AVAILABLE
            override fun observeWifi() = ManualHotspotControl.State.OFF
            override fun wifiCapability() = ManualHotspotControl.Capability.AVAILABLE
            override fun requestWifi(enabled: Boolean, cancelled: () -> Boolean): ManualHotspotControl.Result = error("Screenshot must never write")
            override fun request(enabled: Boolean, cancelled: () -> Boolean): ManualHotspotControl.Result = error("Screenshot must never write")
            override fun execute(block: () -> Unit) = error("Screenshot must never queue work")
        }
        // Use the same app widgets as the production Connection card, not platform test substitutes.
        val buttonMethod = activity.javaClass.getDeclaredMethod("button", String::class.java,
            Boolean::class.javaPrimitiveType, kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
        val labelMethod = activity.javaClass.getDeclaredMethod("label", String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val controls = ManualHotspotControls(activity, { false }, {}, {}, backend,
            labelFactory = { labelMethod.invoke(activity, it, 15, 0xffdddddd.toInt(), false, false) as TextView },
            buttonFactory = { title, click -> buttonMethod.invoke(activity, title, false, click) as Button })
        try {
            val root = controls.build().apply {
                layoutDirection = activity.resources.configuration.layoutDirection
                setBackgroundColor(DiPlayPalette.DARK.surface)
            }
            val width = activity.resources.displayMetrics.widthPixels
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, root.measuredHeight)
            val file = File("build/reports/manual-hotspot-ui/$name.png").apply { parentFile.mkdirs() }
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            for (text in views(root).filterIsInstance<TextView>()) {
                val bounds = Rect(0, 0, text.width, text.height)
                root.offsetDescendantRectToMyCoords(text, bounds)
                assertTrue("${text.text} outside card", bounds.left >= 0 && bounds.right <= root.width && bounds.top >= 0 && bounds.bottom <= root.height)
                text.layout?.let { layout ->
                    for (line in 0 until layout.lineCount) assertEquals("${text.text} ellipsized", 0, layout.getEllipsisCount(line))
                    assertTrue("${text.text} clipped", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                }
            }
        } finally {
            controls.close()
            activity.finish()
        }
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
