package com.shihab.diplay.media

import android.media.AudioManager
import com.shihab.diplay.airplay.AudioCodecKind
import com.shihab.diplay.airplay.AudioFormat
import com.shihab.diplay.airplay.AudioStreamId
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AndroidMediaSinkStateTest {
    @Test fun unsupportedCarAudioModeKeepsTheCoexistPromptOnTheMusicRoute() {
        val app = RuntimeEnvironment.getApplication()
        val reports = CopyOnWriteArrayList<String>()
        val sink = AndroidMediaSink(
            context = app, navigationFollowsIphone = true, navigationPromptAudioFocus = true,
            audioFocusEnabled = true, ecarxNavigationWheelHandshake = true, onAudioDiagnostic = reports::add,
        )
        try {
            sink.onAudioStarted(AudioStreamId(100, "alert"), AudioFormat(AudioCodecKind.LPCM, 48000, 2, 100, "alert"), 0)
            assertTrue(sink.diagnosticReport().contains("modeProbe=unsupported"))
            assertTrue(reports.contains("Audio: navigation route=music iphoneDuck=false"))
            assertFalse(reports.any { "prompt signal sent" in it })
        } finally {
            sink.close()
        }
    }

    @Test fun unsupportedCarAudioModeDoesNotForceFocusOn() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(AudioManager::class.java)
        val sink = AndroidMediaSink(
            context = app, audioFocusEnabled = false, navigationPromptAudioFocus = false,
            ecarxNavigationWheelHandshake = true,
        )
        try {
            sink.onAudioDuck(true)
            assertNull(shadowOf(manager).lastAudioFocusRequest)
            assertTrue(sink.diagnosticReport().contains("state=off"))
        } finally {
            sink.close()
        }
    }

    @Test fun recreatingTheScreenRestoresItsActiveVideoState() {
        val sink = AndroidMediaSink()
        sink.onScreenStreamActive(110, true)
        sink.onScreenStreamActive(111, true)
        sink.onScreenStreamActive(111, false)
        val events = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> events.add(type to active) }
        assertEquals(listOf(110 to true), events)
        sink.close()
        assertEquals(listOf(110 to true, 110 to false), events)
        val afterClose = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> afterClose.add(type to active) }
        assertTrue(afterClose.isEmpty())
    }
}
