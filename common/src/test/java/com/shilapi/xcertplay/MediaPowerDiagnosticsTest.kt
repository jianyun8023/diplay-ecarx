package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import android.view.Display
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MediaPowerDiagnosticsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun reset() = MediaPowerDiagnostics.resetForTest()

    @Test fun keepsEventsForTheProcessAndMirrorsThemOnlyToTheAttachedHost() {
        val first = mutableListOf<String>()
        val firstSink: (String) -> Unit = { first += it }
        val second = mutableListOf<String>()
        val secondSink: (String) -> Unit = { second += it }
        MediaPowerDiagnostics.attach(firstSink)
        MediaPowerDiagnostics.note("one", nowMillis = 0)
        MediaPowerDiagnostics.attach(secondSink)
        // An older host going away must not cut off the newer one.
        MediaPowerDiagnostics.detach(firstSink)
        MediaPowerDiagnostics.note("two", nowMillis = 0)
        MediaPowerDiagnostics.detach(secondSink)
        MediaPowerDiagnostics.note("three", nowMillis = 0)

        assertEquals(listOf("Media power: one"), first)
        assertEquals(listOf("Media power: two"), second)
        val report = MediaPowerDiagnostics.report()
        assertTrue(report.first().startsWith("--- Screen, car power and media commands"))
        assertEquals(listOf("one", "two", "three"), report.drop(2).map { it.substringAfter("  ") })
    }

    @Test fun retainsOnlyTheNewestEntries() {
        repeat(MediaPowerDiagnostics.ENTRIES + 5) { MediaPowerDiagnostics.note("event $it") }
        val events = MediaPowerDiagnostics.report().drop(2)
        assertEquals(MediaPowerDiagnostics.ENTRIES, events.size)
        assertTrue(events.first().endsWith("event 5"))
    }

    @Test fun recordsScreenBroadcastsAfterStart() {
        MediaPowerDiagnostics.start(context)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        val events = MediaPowerDiagnostics.report()
        assertTrue(events.toString(), events.any { it.contains("Media power diagnostics started display=") })
        assertTrue(events.toString(), events.any { it.contains("Screen broadcast SCREEN_OFF display=") })
        // Robolectric is not an ECARX car service.
        assertTrue(events.toString(), events.any { it.contains("Vehicle signal probe not started") })
    }

    @Test fun namesDisplayPowerStates() {
        assertEquals("OFF", MediaPowerDiagnostics.displayStateName(Display.STATE_OFF))
        assertEquals("ON", MediaPowerDiagnostics.displayStateName(Display.STATE_ON))
        assertEquals("unknown", MediaPowerDiagnostics.displayStateName(null))
        assertEquals("state42", MediaPowerDiagnostics.displayStateName(42))
    }
}
