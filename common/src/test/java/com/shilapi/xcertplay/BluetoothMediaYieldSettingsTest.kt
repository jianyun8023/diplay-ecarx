package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.bluetooth.CarBluetoothProfileGuard
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BluetoothMediaYieldSettingsTest {
    private lateinit var activity: DiPlayActivity

    @Before fun setup() {
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.getSharedPreferences("diplay_bt_profile_guard", 0).edit().clear().commit()
        AirPlayPersistence.saveCarBluetoothAudio(activity, false)
        ReflectionHelpers.setField(activity, "page", "settings")
        ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.BLUETOOTH_AUDIO))
    }

    @After fun cleanup() {
        activity.getSharedPreferences("diplay_bt_profile_guard", 0).edit().clear().commit()
        AirPlayPersistence.saveCarBluetoothAudio(activity, false)
        activity.finish()
    }

    private fun settings(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        activity.javaClass.getDeclaredMethod("allSettingsSections", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, this)
    }

    private fun mediaSwitch(root: View): Switch = views(root).filterIsInstance<Switch>().single {
        it.contentDescription == activity.getString(R.string.settings_bluetooth_media_yield)
    }

    @Test fun realAudioSettingDefaultsOnAndSavesWithoutCreatingSession() {
        val control = mediaSwitch(settings())
        assertTrue(control.isChecked)
        assertTrue(control.isEnabled)
        (control.parent as View).performClick()
        assertFalse(CarBluetoothProfileGuard.mediaOnlyEnabled(activity))
        assertFalse(CarPlayBackgroundSession.hasSession())
        (control.parent as View).performClick()
        assertTrue(CarBluetoothProfileGuard.mediaOnlyEnabled(activity))
    }

    @Test fun bluetoothAudioConflictDisablesControlAndUpdatesWithoutReopeningSettings() {
        AirPlayPersistence.saveCarBluetoothAudio(activity, true)
        val root = settings()
        val control = mediaSwitch(root)
        assertFalse(control.isEnabled)
        (control.parent as View).performClick()
        assertTrue(CarBluetoothProfileGuard.mediaOnlyEnabled(activity))
        val bluetoothAudio = views(root).filterIsInstance<Switch>().single {
            it.contentDescription == activity.getString(R.string.settings_car_bluetooth_audio)
        }
        bluetoothAudio.toggle()
        assertTrue(control.isEnabled)
        bluetoothAudio.toggle()
        assertFalse(control.isEnabled)
    }

    @Test @Config(qualifiers = "zh-rCN-w1920dp-h1080dp-land-mdpi")
    fun controlsFitTheCurrentHeadUnit() {
        val name = "current-head-unit"
        val content = settings()
        val control = mediaSwitch(content)
        // Capture the production row from the actual Audio section, without unrelated codecs.
        val row = control.parent as View
        (row.parent as ViewGroup).removeView(row)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = activity.resources.configuration.layoutDirection
            setBackgroundColor(DiPlayPalette.DARK.surface)
            setPadding(24, 16, 24, 16)
            addView(row)
        }
        val width = activity.resources.displayMetrics.widthPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, root.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            val file = File("build/reports/bluetooth-media-yield-ui/$name.png").apply { parentFile.mkdirs() }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        for (text in views(root).filterIsInstance<TextView>()) {
            val bounds = Rect(0, 0, text.width, text.height)
            root.offsetDescendantRectToMyCoords(text, bounds)
            assertTrue("${text.text} outside row", bounds.left >= 0 && bounds.right <= width &&
                bounds.top >= 0 && bounds.bottom <= root.height)
            text.layout?.let { layout ->
                for (line in 0 until layout.lineCount) assertEquals("${text.text} ellipsized", 0, layout.getEllipsisCount(line))
                assertTrue("${text.text} clipped", layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
            }
        }
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
