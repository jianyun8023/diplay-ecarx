package com.shihab.diplay

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class HySiriKeysTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val keys = HySiriKeys()
    private var requests = 0

    @Before fun reset() {
        app.getSharedPreferences("diplay_media_session", 0).edit().clear().commit()
    }

    @Test @Config(sdk = [28]) fun passiveInputAcceptsOnlyTheSelectedSystemKeyDownSinceStart() {
        val line = "I/Input   ( 1000: 2969): injectKeyEvent: KeyEvent { action=ACTION_DOWN, " +
            "keyCode=KEYCODE_NUMPAD_MULTIPLY, scanCode=0, metaState=0, flags=0x0, repeatCount=0, " +
            "eventTime=194485, downTime=194485, deviceId=-1, source=0x101 }"
        for ((group, codes) in listOf(HySiriKey.TOGGLE_155_156 to listOf("KEYCODE_NUMPAD_MULTIPLY", "KEYCODE_NUMPAD_SUBTRACT"),
            HySiriKey.TOGGLE_312_303 to listOf("312", "303"))) {
            for (code in codes) {
                val input = line.replace("KEYCODE_NUMPAD_MULTIPLY", code)
                assertEquals(194485L, HySiriInputLog.parse(input, 194485, group))
                assertNull(HySiriInputLog.parse(input, 194486, group))
                assertNull(HySiriInputLog.parse(input, 0, HySiriKey.entries.single { it != group }))
            }
        }
        for ((from, to) in listOf("1000:" to "10001:", "ACTION_DOWN" to "ACTION_UP",
            "repeatCount=0" to "repeatCount=1", "deviceId=-1" to "deviceId=1")) {
            assertNull(HySiriInputLog.parse(line.replace(from, to), 0, HySiriKey.TOGGLE_155_156))
        }
    }

    @Test fun eitherCodeInTheSelectedPairTriggersOnceWithoutLearningDeviceOrScanCodes() {
        for (pair in HySiriKey.entries) {
            MediaSessionSettings.setHySiriKey(app, pair)
            for (code in pair.codes) {
                assertEquals(true, handle(code))
                assertEquals(true, handle(code, repeat = 1))
                assertEquals(true, handle(code, action = KeyEvent.ACTION_UP))
            }
        }
        assertEquals(4, requests)
    }

    @Test fun unselectedKeysAndMediaKeysDoNotTriggerSiri() {
        assertEquals(false, handle(312))
        assertNull(handle(85))
        assertNull(handle(87))
        assertNull(handle(88))
        assertNull(handle(200231))
        assertEquals(0, requests)
    }

    @Test fun disabledOrInCallPassesTheKeyAndConsumedReleaseSurvivesSwitchOff() {
        assertNull(handle(156, enabled = false))
        assertEquals(false, handle(156, calling = true))
        handle(156, action = KeyEvent.ACTION_UP)
        assertEquals(0, requests)
        assertEquals(true, handle(155))
        assertEquals(true, handle(155, repeat = 1, enabled = false))
        assertEquals(true, handle(155, action = KeyEvent.ACTION_UP, enabled = false))
        assertEquals(1, requests)
    }

    @Test fun changingTheSelectionTakesEffectOnTheNextDistinctPress() {
        assertEquals(true, handle(155))
        handle(155, action = KeyEvent.ACTION_UP)
        MediaSessionSettings.setHySiriKey(app, HySiriKey.TOGGLE_312_303)
        assertEquals(false, handle(156))
        assertEquals(true, handle(303))
        assertEquals(2, requests)
    }

    private fun handle(code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0,
        enabled: Boolean = true, calling: Boolean = false): Boolean? =
        keys.handle(app, KeyEvent(1, 2, action, code, repeat, 0, -1, 99), enabled, calling) {
            requests++
            true
        }
}
