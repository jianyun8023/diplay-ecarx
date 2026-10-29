package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class EnvironmentBroadcastDiagnosticsTest {
    @After fun stop() = EnvironmentBroadcastDiagnostics.stop()

    @Test fun sessionListenerFiltersActionsAndValuesAndStopsWithoutDuplicatingRegistration() {
        val app = RuntimeEnvironment.getApplication()
        fun count() = Regex("received=(\\d+)").find(EnvironmentBroadcastDiagnostics.report())!!.groupValues[1].toLong()
        EnvironmentBroadcastDiagnostics.stop()
        val before = count()
        EnvironmentBroadcastDiagnostics.start(app, hyCompatibility = true)
        EnvironmentBroadcastDiagnostics.start(app, hyCompatibility = true)
        app.sendBroadcast(Intent("unlisted.private.action").putExtra("private-key", "private-value"))
        app.sendBroadcast(Intent("ECARX_NAVI_SERVICE_ACTION").putExtra("NAVI_STATUS", 1)
            .putExtra("private-key", "private-value"))
        app.sendBroadcast(Intent("com.hyphp.hywebview.HyMediaReceiver")
            .putExtra("getTrackName", "private-title").putExtra("getArtwork", "http://private-artwork"))
        app.sendBroadcast(Intent("ECARX_NAVI_SERVICE_ACTION").putExtra("NAVI_STATUS", "private-invalid-status"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before + 3, count())
        val report = EnvironmentBroadcastDiagnostics.report()
        assertTrue(report.contains("active=true status=registered"))
        assertTrue(report.contains("sender=unknown keys=NAVI_STATUS naviStatus=1"))
        assertTrue(report.contains("getTrackName,getArtwork"))
        assertTrue(report.contains("keys=NAVI_STATUS naviStatus=unknown"))
        assertFalse(report.contains("private-key"))
        assertFalse(report.contains("private-value"))
        assertFalse(report.contains("private-title"))
        assertFalse(report.contains("private-invalid-status"))
        assertFalse(report.contains("http://private-artwork"))
        assertFalse(report.contains("unlisted.private.action"))

        repeat(70) { app.sendBroadcast(Intent("com.autonavi.plus.closemap")) }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(EnvironmentBroadcastDiagnostics.report().contains("retained=64"))
        val after = count()
        EnvironmentBroadcastDiagnostics.stop()
        EnvironmentBroadcastDiagnostics.stop()
        app.sendBroadcast(Intent("ECARX_NAVI_SERVICE_ACTION").putExtra("NAVI_STATUS", 0))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(after, count())
        assertTrue(EnvironmentBroadcastDiagnostics.report().contains("active=false status=stopped"))
    }

    @Test fun independentModeDoesNotRegisterHyMediaButKeepsMapObservation() {
        val app = RuntimeEnvironment.getApplication()
        EnvironmentBroadcastDiagnostics.stop()
        val before = Regex("received=(\\d+)").find(EnvironmentBroadcastDiagnostics.report())!!.groupValues[1].toLong()
        EnvironmentBroadcastDiagnostics.start(app, hyCompatibility = false)
        app.sendBroadcast(Intent("com.hyphp.hywebview.HyMediaReceiver").putExtra("method", "dashboard"))
        app.sendBroadcast(Intent("com.autonavi.plus.showmap"))
        shadowOf(Looper.getMainLooper()).idle()
        val report = EnvironmentBroadcastDiagnostics.report()
        assertTrue(report.contains("received=${before + 1}"))
        assertTrue(report.contains("3 global actions; HY media=false"))
    }
}
