package com.shihab.diplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shihab.diplay.host.R
import com.shihab.diplay.orchestration.MfiTarget
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** The local identity status and import button in Advanced on the current 1920×1080 ECARX head unit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MfiIdentitySettingsScreenshotTest {
    @Test @Config(qualifiers = "zh-rCN-w1920dp-h1080dp-land-mdpi")
    fun localTargetShowsIdentityStatusAndImport() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.LOCAL)
        val output = File("build/reports/mfi-identity-settings-ui").apply { mkdirs() }
        try {
            var page = render(activity)
            assertTrue(texts(page).contains(activity.getString(R.string.settings_mfi_identity_missing)))
            assertTrue(texts(page).contains(activity.getString(R.string.settings_mfi_identity_import)))
            screenshot(page, File(output, "mfi-identity-missing-current-head-unit.png"))

            File(activity.noBackupFilesDir, "offline-mfi").mkdirs()
            page = render(activity)
            assertTrue(texts(page).contains(activity.getString(R.string.settings_mfi_identity_installed)))
            screenshot(page, File(output, "mfi-identity-installed-current-head-unit.png"))
            for (text in views(page).filterIsInstance<TextView>().filter { it.text.isNotEmpty() }) {
                text.layout?.let { layout ->
                    for (line in 0 until layout.lineCount) assertEquals("${text.text} ellipsized", 0, layout.getEllipsisCount(line))
                }
            }
        } finally {
            File(activity.noBackupFilesDir, "offline-mfi").deleteRecursively()
            activity.finish()
        }
    }

    @Test fun adapterTargetHidesTheImport() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.USB_CH341)
        try {
            assertFalse(texts(render(activity)).contains(activity.getString(R.string.settings_mfi_identity_import)))
        } finally {
            AirPlayPersistence.saveMfiTarget(activity, MfiTarget.LOCAL)
            activity.finish()
        }
    }

    private fun screenshot(page: View, file: File) {
        val bitmap = Bitmap.createBitmap(page.width, 1080, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply { drawColor(DiPlayPalette.DARK.surface) }.also(page::draw)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun render(activity: DiPlayActivity): LinearLayout {
        val width = activity.resources.displayMetrics.widthPixels
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DiPlayPalette.DARK.surface)
        }
        ReflectionHelpers.setField(activity, "page", "settings")
        ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.AUTHENTICATION))
        activity.javaClass.getDeclaredMethod("allSettingsSections", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, page)
        page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        page.layout(0, 0, width, page.measuredHeight)
        return page
    }

    private fun texts(view: View) = views(view).filterIsInstance<TextView>().map { it.text.toString() }.toList()

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
