package com.shilapi.xcertplay

import android.os.Looper
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-w1920dp-h1080dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CarHotspotLaunchFlowTest {
    @Test fun firstConnectionJoinsLaunchPreparationAndContinuesWithoutAnotherStart() {
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
