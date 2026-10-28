package com.shilapi.xcertplay

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.hud.MediaOutputBackendSettings
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaOutputBackendScreenshotsTest {
    @Test @Config(qualifiers = "zh-rCN-w320dp-h480dp-port-hdpi")
    fun compactChineseLargeText() = capture("compact-zh")
    @Test @Config(qualifiers = "zh-rTW-w320dp-h480dp-port-hdpi")
    fun compactTraditionalChineseLargeText() = capture("compact-zh-tw")
    @Test @Config(qualifiers = "en-w960dp-h540dp-land-hdpi")
    fun fullEnglishLargeText() = capture("full-en")
    @Test @Config(qualifiers = "ar-w320dp-h480dp-port-hdpi")
    fun compactRtlSystemUsesEnglishFallback() = capture("compact-en-rtl")

    private fun capture(name: String) {
        val info = RuntimeEnvironment.getApplication().applicationInfo
        val originalFlags = info.flags
        info.flags = originalFlags or ApplicationInfo.FLAG_SUPPORTS_RTL
        RuntimeEnvironment.setFontScale(1.5f)
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val backend = object : MediaOutputBackendControls.Backend {
            override fun load() = MediaOutputBackendSettings.Backend.UNI_MEDIACENTER
            override fun save(value: MediaOutputBackendSettings.Backend) = error("Screenshot must never save")
            override fun diagnostics() = "IDLE / WAITING_FOR_CARPLAY_MEDIA"
            override fun probe() = error("Screenshot must never inspect")
            override fun executeProbe(block: () -> Unit) = error("Screenshot must never queue work")
        }
        // Use the same app widgets as the production Connection card, not platform test substitutes.
        val buttonMethod = activity.javaClass.getDeclaredMethod("button", String::class.java,
            Boolean::class.javaPrimitiveType, kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
        val labelMethod = activity.javaClass.getDeclaredMethod("label", String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val controls = MediaOutputBackendControls(activity, {}, backend,
            labelFactory = { labelMethod.invoke(activity, it, 15, 0xffdddddd.toInt(), false, false) as TextView },
            buttonFactory = { title, click -> buttonMethod.invoke(activity, title, false, click) as Button })
        try {
            val root = controls.build().apply {
                layoutDirection = activity.resources.configuration.layoutDirection
                setBackgroundColor(DiPlayPalette.DARK.surface)
            }
            val width = activity.resources.displayMetrics.widthPixels
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            root.layout(0, 0, width, root.measuredHeight)
            val file = File("build/reports/media-backend-ui/$name.png").apply { parentFile.mkdirs() }
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
            if (name.endsWith("rtl")) assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        } finally {
            controls.close()
            info.flags = originalFlags
            activity.finish()
        }
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
