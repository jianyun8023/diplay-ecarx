package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.CarHotspotSettings
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "en", manifest = Config.NONE)
class HotspotProvisioningLogTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        ShadowLog.clear()
        CarHotspotSettings.setConfigWrite(app, true)
    }

    @Test fun openingConnectionSettingsDoesNotGenerateHotspotCredentials() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        AirPlayPersistence.saveWirelessHotspotMode(app, com.shilapi.xcertplay.orchestration.WirelessHotspotMode.MANUAL)
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        DiPlayActivity::class.java.getDeclaredMethod("wirelessLinkControls", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, LinearLayout(activity))
        val ssid = AirPlayPersistence.loadManualHotspotSsid(app)
        assertTrue(ssid.isBlank())
        val lines = ShadowLog.getLogsForTag("DiPlay-Hotspot")
        assertTrue(lines.none { it.msg.startsWith("provisioned") })
    }

    @Test fun anExistingHotspotIsNotProvisionedOrLogged() {
        AirPlayPersistence.saveManualHotspotSsid(app, "Car hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "12345678")
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        AirPlayPersistence.saveWirelessHotspotMode(app, com.shilapi.xcertplay.orchestration.WirelessHotspotMode.MANUAL)
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        DiPlayActivity::class.java.getDeclaredMethod("wirelessLinkControls", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, LinearLayout(activity))
        assertEquals("Car hotspot", AirPlayPersistence.loadManualHotspotSsid(app))
        assertTrue(ShadowLog.getLogsForTag("DiPlay-Hotspot").none { it.msg.startsWith("provisioned") })
    }
}
