package com.shihab.diplay

import android.os.Looper
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.ResultReceiver
import com.shihab.diplay.network.ManualHotspotControl
import com.shihab.diplay.network.CarHotspotSettings
import com.shihab.diplay.network.CarHotspotTethering
import com.shihab.diplay.orchestration.WirelessHotspotMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-w1920dp-h1080dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CarHotspotLaunchFlowTest {
    @Test @Config(shadows = [HotspotWritePermissionShadow::class, PlatformApState::class])
    fun platformPermissionStartsAndStopsWithoutWriteSettingsOrAdb() {
        val app = RuntimeEnvironment.getApplication()
        HotspotWritePermissionShadow.allowed = false
        PlatformApState.enabled = false
        shadowOf(app).grantPermissions("android.permission.TETHER_PRIVILEGED")
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val field = ConnectivityManager::class.java.getDeclaredField("mService").apply { isAccessible = true }
        val original = field.get(manager)
        var starts = 0
        var stops = 0
        val service = Proxy.newProxyInstance(field.type.classLoader, arrayOf(field.type)) { _, method, args ->
            when (method.name) {
                "startTethering" -> {
                    starts++
                    PlatformApState.enabled = true
                    (args!![1] as ResultReceiver).send(0, null)
                }
                "stopTethering" -> {
                    stops++
                    PlatformApState.enabled = false
                }
            }
            null
        }
        field.set(manager, service)
        try {
            assertTrue(CarHotspotTethering.permitted(app))
            assertEquals(ManualHotspotControl.Capability.AVAILABLE, ManualHotspotControl(app).capability())
            assertEquals(CarHotspotTethering.Result.READY, CarHotspotSetup.prepare(app, { false }, {}, {}))
            assertEquals(ManualHotspotControl.Result.OFF, ManualHotspotControl(app).request(false) { false })
            assertEquals(1, starts)
            assertEquals(1, stops)
            assertFalse("Startup must not load an ADB key", File(app.noBackupFilesDir, "adb").exists())
            shadowOf(app).denyPermissions("android.permission.TETHER_PRIVILEGED")
            assertFalse(CarHotspotTethering.permitted(app))
            assertEquals(ManualHotspotControl.Capability.PERMISSION_REQUIRED, ManualHotspotControl(app).capability())
        } finally {
            field.set(manager, original)
            shadowOf(app).denyPermissions("android.permission.TETHER_PRIVILEGED")
        }
    }

    @Implements(WifiManager::class)
    class PlatformApState {
        @Implementation fun getWifiApState(): Int = if (enabled) 13 else 11
        @Implementation fun isWifiApEnabled(): Boolean = enabled
        companion object { var enabled = false }
    }

    @Test fun firstConnectionJoinsLaunchPreparationAndContinuesWithoutAnotherStart() {
        CarHotspotSettings.setEnabled(RuntimeEnvironment.getApplication(), false)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        CarHotspotSettings.setEnabled(activity, true)
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.WIFI_P2P)
        ReflectionHelpers.setField(activity, "pendingCarHotspotSetup", true)
        ReflectionHelpers.setField(activity, "setupError", null)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var starts = 0
        activity.prepareHotspot = { _, _ ->
            starts++
            started.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            CarHotspotTethering.Result.READY
        }
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "startCarHotspotOnLaunch",
                ReflectionHelpers.ClassParameter.from(ConnectionDiagnostics.Source::class.java, null))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "connectWithSource",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
                ReflectionHelpers.ClassParameter.from(ConnectionDiagnostics.Source::class.java, ConnectionDiagnostics.Source.APP_LAUNCH))
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (ReflectionHelpers.getField<Boolean>(activity, "hotspotStartupRunning") && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, starts)
            assertFalse(ReflectionHelpers.getField(activity, "hotspotStartupRunning"))
            assertTrue(ConnectionDiagnostics.report(activity).contains("source=APP_LAUNCH event=HOTSPOT_SETUP_REQUIRED"))
        } finally {
            release.countDown()
            CarHotspotSettings.setEnabled(activity, false)
            controller.pause().stop().destroy()
        }
    }
}
