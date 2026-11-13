package com.shihab.diplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.content.pm.PackageInfo
import android.text.Spanned
import android.text.style.URLSpan
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shihab.diplay.host.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OpenSourceNoticesTest {
    @Test fun fullLicenseTextsAndSourceLinksAreAvailableOffline() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val content = LinearLayout(activity)
        activity.javaClass.getDeclaredMethod("about", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, content)
        val open = views(content).filterIsInstance<Button>().single {
            it.text.toString() == activity.getString(R.string.settings_open_source_licenses)
        }
        val files = activity.assets.list("licenses")!!.toSet()
        assertTrue("Packaged license files: $files", files.containsAll(setOf(
            "GPL-3.0.txt", "AGPL-3.0.txt", "NOTICE.md", "THIRD_PARTY_NOTICES.md", "SOURCE.txt",
            "Apache-2.0.txt", "Bouncy-Castle-LICENSE.txt", "SLF4J-LICENSE.txt",
            "SpeexDSP-COPYING.txt", "KISS-FFT-LICENSE.txt", "Concentus-LICENSE.txt", "Opus-COPYING.txt", "NATIVE-NOTICE.md",
        )))
        val documents = mapOf(
            "GPL-3.0.txt" to File("../LICENSE"),
            "AGPL-3.0.txt" to File("../docs/licenses/DiAuto-AGPL-3.0.txt"),
            "NOTICE.md" to File("../NOTICE.md"),
            "SOURCE.txt" to File("../docs/licenses/SOURCE.txt"),
        )
        for ((name, source) in documents) {
            open.performClick()
            val menu = ShadowAlertDialog.getLatestAlertDialog()
            val list = menu.listView
            val index = (0 until list.count).single { list.adapter.getItem(it) == name }
            shadowOf(menu).clickOnItem(index)
            val document = ShadowAlertDialog.getLatestAlertDialog()
            val body = views(document.window!!.decorView).filterIsInstance<TextView>()
                .single { it.text.toString() == source.readText() }
            assertTrue(body.isTextSelectable)
            if (name == "SOURCE.txt") {
                val spans = (body.text as Spanned).getSpans(0, body.text.length, URLSpan::class.java)
                assertTrue(spans.any { it.url == "https://github.com/jianyun8023/diplay-ecarx" })
            }
            document.dismiss()
            menu.dismiss()
        }
        activity.finish()
    }

    @Test
    @Config(sdk = [29], qualifiers = "zh-rCN-w1920dp-h1080dp-land-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aboutEntryFitsTheCurrentHeadUnit() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        shadowOf(activity.packageManager).installPackage(PackageInfo().apply {
            packageName = activity.packageName
            versionName = "0.2"
            setLongVersionCode(144)
        })
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DiPlayPalette.DARK.surface)
        }
        activity.javaClass.getDeclaredMethod("about", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, content)
        val root = ScrollView(activity).apply { addView(content) }
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 1080)
        val version = views(content).filterIsInstance<TextView>().single {
            it.text.toString() == activity.getString(R.string.about_ecarx_edition_version, "0.2 (v144)")
        }
        assertTrue(version.getLocalVisibleRect(Rect()))
        val entry = views(content).filterIsInstance<Button>().single {
            it.text.toString() == activity.getString(R.string.settings_open_source_licenses)
        }
        assertTrue(entry.isEnabled && entry.isClickable)
        assertTrue(entry.width > 0 && entry.height > 0)
        root.scrollTo(0, maxOf(0, content.height - root.height))
        val visible = Rect()
        assertTrue(entry.getLocalVisibleRect(visible))
        assertEquals(Rect(0, 0, entry.width, entry.height), visible)
        for (text in views(content).filterIsInstance<TextView>()) {
            val layout = text.layout ?: continue
            for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
            assertTrue(layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
        }
        val folder = File("build/reports/open-source-ui").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        root.scrollTo(0, 0)
        root.draw(Canvas(bitmap))
        File(folder, "about-current-head-unit.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
        activity.finish()
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
