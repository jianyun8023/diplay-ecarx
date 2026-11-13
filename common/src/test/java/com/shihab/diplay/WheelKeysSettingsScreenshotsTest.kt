package com.shihab.diplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shihab.diplay.host.R
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The steering-wheel card on the current 1920×1080 head unit: the one switch DiPlay needs (Siri on the
 * voice key) and none of the key learning it does not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w1920dp-h1080dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WheelKeysSettingsScreenshotsTest {
    @Test fun cardOffersTheSiriKeyAndNoLearningList() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        // Identify the head unit as ECARX, so the voice-key takeover row is part of the screenshot.
        org.robolectric.Shadows.shadowOf(activity.packageManager).installPackage(
            android.content.pm.PackageInfo().apply { packageName = "com.ecarx.xui.service" })
        MediaSessionSettings.setMode(activity, MediaSessionMode.ECARX)
        MediaSessionSettings.setWheelSiriEnabled(activity, true)
        try {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            DiPlayActivity::class.java.getDeclaredMethod("wheelKeysSettings", LinearLayout::class.java)
                .apply { isAccessible = true }.invoke(activity, row)
            val texts = allViews(row).filterIsInstance<TextView>()
                .map { it.contentDescription?.toString() ?: it.text.toString() }
                .toList()
            assertTrue(texts.contains(activity.getString(R.string.wheel_siri_key)))
            assertTrue(texts.any { it.startsWith(activity.getString(R.string.settings_voice_key_press)) })
            assertFalse(texts.contains(activity.getString(R.string.wheel_media_play_pause)))
            assertFalse(texts.any { it.startsWith(activity.getString(R.string.settings_custom_wheel_keys)) })
            assertFalse(texts.any { it.contains("点按以学习") })
            val width = 1920
            row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            row.layout(0, 0, width, row.measuredHeight)
            val bitmap = Bitmap.createBitmap(width, row.measuredHeight, Bitmap.Config.ARGB_8888)
            try {
                row.draw(Canvas(bitmap))
                val directory = File("build/reports/wheel-keys-ui").apply { mkdirs() }
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

    @Test fun hyAndDisabledLayoutsShowOnlyTheApplicableChoices() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        MediaSessionSettings.setMode(activity, MediaSessionMode.ANDROID)
        try {
            for (enabled in listOf(true, false)) {
                MediaSessionSettings.setWheelSiriEnabled(activity, enabled)
                val row = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                DiPlayActivity::class.java.getDeclaredMethod("wheelKeysSettings", LinearLayout::class.java)
                    .apply { isAccessible = true }.invoke(activity, row)
                val texts = allViews(row).filterIsInstance<TextView>().map { it.text.toString() }.toList()
                assertTrue(texts.any { it.contains(activity.getString(R.string.wheel_siri_key)) })
                assertTrue(texts.any { it.startsWith(activity.getString(R.string.settings_hy_siri_key) + " · ") } == enabled)
                assertFalse(texts.any { it.startsWith(activity.getString(R.string.settings_voice_key_press)) })
                assertFalse(texts.any { it.contains("学习") || it.contains("双击") || it.contains("MEDIA_") })
                row.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                row.layout(0, 0, 1920, 1080)
                val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
                try {
                    row.draw(Canvas(bitmap))
                    val file = File("build/reports/wheel-keys-ui/hy-${if (enabled) "on" else "off"}.png")
                    requireNotNull(file.parentFile).mkdirs()
                    file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
        } finally { activity.finish() }
    }

    @Test @Config(sdk = [28]) fun hyMissingPlatformPermissionIsExplainedOnlyWhenNeeded() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val app = activity.application
        MediaSessionSettings.setMode(activity, MediaSessionMode.ANDROID)
        MediaSessionSettings.setWheelSiriEnabled(activity, true)
        for (granted in listOf(false, true)) {
            if (granted) org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.READ_LOGS)
            else org.robolectric.Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.READ_LOGS)
            val row = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            DiPlayActivity::class.java.getDeclaredMethod("wheelKeysSettings", LinearLayout::class.java)
                .apply { isAccessible = true }.invoke(activity, row)
            val buttonExists = allViews(row).filterIsInstance<TextView>()
                .any { it.text == activity.getString(R.string.settings_hy_siri_grant_failed) }
            assertTrue(buttonExists == !granted)
            if (!granted) {
                row.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
                row.layout(0, 0, 1920, 1080)
                val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
                row.draw(Canvas(bitmap))
                val file = File("build/reports/wheel-keys-ui/hy-background-permission.png")
                requireNotNull(file.parentFile).mkdirs()
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }
        }
        activity.finish()
    }

    private fun allViews(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(allViews(view.getChildAt(i)))
    }
}
