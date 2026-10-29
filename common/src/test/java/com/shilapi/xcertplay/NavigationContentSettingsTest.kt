package com.shilapi.xcertplay

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
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
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h1080dp-land-mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationContentSettingsTest {
    @Test fun selectorRejectsUnsupportedOemModeAndBothTestButtonsFitTheHeadUnit() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        activity.javaClass.getDeclaredMethod("navigationContentControl", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, panel)
        (panel.getChildAt(0) as Button).performClick()
        val selector = ShadowDialog.getLatestDialog() as AlertDialog
        selector.listView.performItemClick(selector.listView.getChildAt(1), 1, 1)
        selector.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse(AirPlayPersistence.loadNavigationOemContent(activity))
        activity.javaClass.getDeclaredMethod("showSoundOutputTest").apply { isAccessible = true }.invoke(activity)
        val dialog = ShadowDialog.getLatestDialog()
        shadowOf(Looper.getMainLooper()).idle()
        val content = dialog.findViewById<View>(android.R.id.content)
        content.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, 1920, 1080)
        val matches = arrayListOf<View>()
        content.findViewsWithText(matches, activity.getString(R.string.sound_output_test_auto_navigation), View.FIND_VIEWS_WITH_TEXT)
        val standard = matches.single() as Button
        matches.clear()
        content.findViewsWithText(matches, activity.getString(R.string.settings_navigation_test_oem), View.FIND_VIEWS_WITH_TEXT)
        val oem = matches.single() as Button
        assertEquals(standard.height, oem.height)
        assertTrue(oem.bottom <= 1080)
        val file = File("build/reports/navigation-content-ui/sound-test-1920x1080.png")
        file.parentFile.mkdirs()
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        content.draw(Canvas(bitmap))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        dialog.dismiss()
    }
}
