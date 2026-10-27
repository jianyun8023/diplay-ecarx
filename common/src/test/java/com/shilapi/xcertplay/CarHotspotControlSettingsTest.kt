package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import com.shilapi.xcertplay.hud.EcarxOutputSettings
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "en")
class CarHotspotControlSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        app.getSharedPreferences("diplay_car_hotspot", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun automaticHotspotIsReachableOnlyInManualConnectionSettings() {
        assertTrue(SettingsSection.CAR_HOTSPOT in SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.CONNECTION))
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.setField(activity, "page", "settings")
        val render = activity.javaClass.getDeclaredMethod("connectionSettings", LinearLayout::class.java).apply { isAccessible = true }
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        val root = LinearLayout(activity)
        render.invoke(activity, root)
        fun switches(view: View): List<CompoundButton> =
            listOfNotNull(view as? CompoundButton) + if (view is ViewGroup)
                (0 until view.childCount).flatMap { switches(view.getChildAt(it)) } else emptyList()
        val toggle = switches(root).single { it.contentDescription.toString() == activity.getString(com.shilapi.xcertplay.host.R.string.auto_car_hotspot_title) }
        assertFalse(toggle.isChecked)
        toggle.isChecked = true
        assertTrue(CarHotspotSettings.enabled(app))
        toggle.isChecked = false
        assertFalse(CarHotspotSettings.enabled(app))
        assertTrue(ReflectionHelpers.getField(activity, "startupHotspotCancelled"))
        toggle.isChecked = true
        assertFalse(ReflectionHelpers.getField(activity, "startupHotspotCancelled"))
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        root.removeAllViews()
        render.invoke(activity, root)
        assertTrue(switches(root).none { it.contentDescription.toString() == toggle.contentDescription.toString() })
    }
}
