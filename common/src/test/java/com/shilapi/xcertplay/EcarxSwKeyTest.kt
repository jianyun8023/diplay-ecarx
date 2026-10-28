package com.shilapi.xcertplay

import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.EcarxSwKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class EcarxSwKeyTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = EcarxSwKey.prefs(context)

    @Before
    fun reset() {
        prefs.edit().clear().commit()
    }

    @Test
    fun eightActionsMirrorTheVendorBox() {
        assertEquals(8, EcarxSwKey.Action.entries.size)
        assertEquals(
            listOf("voice", "prev", "next", "playpause", "pause", "play", "answer", "hangup"),
            EcarxSwKey.Action.entries.map { it.prefKey },
        )
    }

    @Test
    fun learntKeyIsAssignedAndConflictingActionIsCleared() {
        EcarxSwKey.assign(prefs, EcarxSwKey.Action.NEXT, 200087)
        assertEquals(200087, EcarxSwKey.learntKeyCode(prefs, EcarxSwKey.Action.NEXT))
        // Assign the same key to another action; the reference app removes it from the previous one.
        EcarxSwKey.assign(prefs, EcarxSwKey.Action.PREV, 200087)
        assertEquals(-1, EcarxSwKey.learntKeyCode(prefs, EcarxSwKey.Action.NEXT))
        assertEquals(200087, EcarxSwKey.learntKeyCode(prefs, EcarxSwKey.Action.PREV))
    }

    @Test
    fun learntVoiceKeyOpensSiri() {
        EcarxSwKey.assign(prefs, EcarxSwKey.Action.VOICE, 0x77)
        val press = EcarxSwKey.resolve(prefs, 0x77)!!
        assertTrue(press.isSiri)
        assertEquals(EcarxSwKey.Action.VOICE, press.action)
    }

    @Test
    fun factoryVoiceKeyOpensSiriWithoutLearningOrMediaKeys() {
        EcarxSwKey.setMediaButtonEnabled(prefs, false)
        assertTrue(EcarxSwKey.resolve(prefs, 200231)!!.isSiri)
        EcarxSwKey.assign(prefs, EcarxSwKey.Action.NEXT, 200231)
        assertTrue(EcarxSwKey.resolve(prefs, 200231)!!.isSiri)
    }

    @Test
    fun learntAnswerAndHangupMapToTelephonyPresses() {
        EcarxSwKey.assign(prefs, EcarxSwKey.Action.ANSWER, 200400)
        EcarxSwKey.assign(prefs, EcarxSwKey.Action.HANGUP, 200401)
        val answer = EcarxSwKey.resolve(prefs, 200400)!!
        assertEquals(CarPlayMediaButton.ANSWER, answer.callIndex)
        val hangup = EcarxSwKey.resolve(prefs, 200401)!!
        assertEquals(CarPlayMediaButton.HANGUP, hangup.callIndex)
    }

    @Test
    fun standardCallKeysConvertOnlyWhenTheGateIsOn() {
        // Default: call keys are left to the system (swMediaKeyCallConvert is off).
        assertNull(EcarxSwKey.resolve(prefs, KeyEvent.KEYCODE_CALL))
        EcarxSwKey.setCallKeyConvert(prefs, true)
        assertEquals(CarPlayMediaButton.ANSWER, EcarxSwKey.resolve(prefs, KeyEvent.KEYCODE_CALL)?.callIndex)
        assertEquals(CarPlayMediaButton.HANGUP, EcarxSwKey.resolve(prefs, KeyEvent.KEYCODE_ENDCALL)?.callIndex)
    }

    @Test
    fun mediaKeysRespectTheMediaButtonGate() {
        // swMediaButton defaults on.
        assertEquals(CarPlayMediaButton.NEXT, EcarxSwKey.resolve(prefs, KeyEvent.KEYCODE_MEDIA_NEXT)?.mediaIndex)
        EcarxSwKey.setMediaButtonEnabled(prefs, false)
        assertNull(EcarxSwKey.resolve(prefs, KeyEvent.KEYCODE_MEDIA_NEXT))
    }

    @Test
    fun unknownKeysResolveToNull() {
        assertNull(EcarxSwKey.resolve(prefs, KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(EcarxSwKey.resolve(prefs, KeyEvent.KEYCODE_MEDIA_NEXT)!!.isCall)
    }
}
