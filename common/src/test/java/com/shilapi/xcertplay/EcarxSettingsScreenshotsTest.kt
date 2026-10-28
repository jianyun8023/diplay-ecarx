package com.shilapi.xcertplay

import android.app.Dialog
import android.os.Looper
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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** Production settings and editor on the current 1920×1080 head unit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EcarxSettingsScreenshotsTest {
    @Test @Config(qualifiers = "zh-rCN-w1920dp-h1080dp-land-mdpi")
    fun settingsAndEditorFitTheCurrentHeadUnit() {
        val name = "current-head-unit"
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        AirPlayPersistence.clearClusterSafeAreaRect(activity)
        val output = File("build/reports/ecarx-settings-ui").apply { mkdirs() }
        try {
            val configuration = activity.resources.configuration
            val width = activity.resources.displayMetrics.widthPixels
            val navigation = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = configuration.layoutDirection
                setBackgroundColor(DiPlayPalette.DARK.surface)
            }
            // Build the actual card so its normal spacing pass is included in the screenshot.
            ReflectionHelpers.setField(activity, "page", "settings")
            activity.javaClass.getDeclaredMethod("section", LinearLayout::class.java, String::class.java,
                Int::class.javaObjectType, kotlin.jvm.functions.Function1::class.java)
                .apply { isAccessible = true }.invoke(activity, navigation,
                    activity.getString(R.string.ecarx_navigation), R.drawable.ic_dp_navigation,
                    { card: LinearLayout ->
                        activity.javaClass.getDeclaredMethod("ecarxNavigationOutputs", LinearLayout::class.java)
                            .apply { isAccessible = true }.invoke(activity, card)
                        Unit
                    })
            val cluster = navigation.getChildAt(0) as ViewGroup
            render(cluster, width, File(output, "cluster-controls-$name.png"))
            views(cluster).filterIsInstance<Button>().single {
                it.text.toString().startsWith(activity.getString(R.string.settings_ecarx_cluster_safe_area) + " · ")
            }.performClick()
            val dialog = ReflectionHelpers.getField<Dialog>(activity, "clusterSafeAreaDialog")
            val panel = dialog.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
            shadowOf(Looper.getMainLooper()).idle()
            val window = dialog.window!!
            val decor = window.decorView as ViewGroup
            val available = Rect().also(decor::getWindowVisibleDisplayFrame)
            assertTrue("$name: attached window must report available bounds", !available.isEmpty)
            val windowWidth = minOf(window.attributes.width, available.width())
            val windowHeight = minOf(window.attributes.height, available.height())
            assertTrue("$name: production dialog must request a bounded size", windowWidth > 0 && windowHeight > 0)
            // Measure the complete themed window at its actual request, capped by system bounds.
            // Its decor/content padding stays in the measurement; never enlarge the inner panel.
            decor.measure(View.MeasureSpec.makeMeasureSpec(windowWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(windowHeight, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, windowWidth, windowHeight)
            capture(decor, File(output, "cluster-editor-$name.png"))
            for (control in views(panel).filter { it is Button || it is SafeAreaEditorView }) {
                val visible = Rect()
                assertTrue("$name: ${control.javaClass.simpleName} must be reachable", control.getLocalVisibleRect(visible))
                assertEquals("$name: control clipped by its real window", Rect(0, 0, control.width, control.height), visible)
                assertTrue("$name: control must be enabled", control.isEnabled && control.isClickable)
            }
            val editor = views(panel).filterIsInstance<SafeAreaEditorView>().single()
            assertTrue("$name: editor must remain usable", editor.width > 0 && editor.height > 0)
            assertEquals(editor.width * 720 / 1920, editor.height)
            val actions = views(panel).filterIsInstance<Button>().filter {
                it.text.toString() == activity.getString(R.string.save) ||
                    it.text.toString() == activity.getString(R.string.cancel)
            }.toList()
            assertEquals(2, actions.size)
            assertEquals("$name: actions in one row keep equal heights", actions[0].height, actions[1].height)
            dialog.dismiss()

        } finally {
            ReflectionHelpers.getField<Dialog?>(activity, "clusterSafeAreaDialog")?.dismiss()
            activity.finish()
        }
    }

    private fun render(root: ViewGroup, width: Int, file: File) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, root.measuredHeight)
        capture(root, file)
    }

    private fun capture(root: ViewGroup, file: File) {
        // Keep the failing layout available for visual inspection as well.
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        for (text in views(root).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE && it.text.isNotEmpty() }) {
            val bounds = Rect(0, 0, text.width, text.height)
            root.offsetDescendantRectToMyCoords(text, bounds)
            assertTrue("${file.name}: ${text.text} outside $bounds", bounds.left >= 0 && bounds.top >= 0 &&
                bounds.right <= root.width && bounds.bottom <= root.height)
            text.layout?.let { layout ->
                for (line in 0 until layout.lineCount) {
                    assertEquals("${file.name}: ${text.text} ellipsized", 0, layout.getEllipsisCount(line))
                }
                assertTrue("${file.name}: ${text.text} clipped vertically",
                    layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
            }
        }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
