package com.shilapi.xcertplay.airplay

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayMediaButtonTest {
    @Test
    fun steeringWheelKeysMapToCarPlayMediaPresses() {
        assertEquals(CarPlayMediaButton.NEXT, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(CarPlayMediaButton.PREVIOUS, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        // A head unit rewrites its play/pause key into PLAY or PAUSE from the session state; both toggle.
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_HEADSETHOOK))
    }

    @Test
    fun theVoiceKeysOpenSiri() {
        // ECARX wheels send the voice key as either the plain assist key or the voice-assist key.
        assertTrue(CarPlayMediaButton.opensSiri(KeyEvent.KEYCODE_ASSIST))
        assertTrue(CarPlayMediaButton.opensSiri(KeyEvent.KEYCODE_VOICE_ASSIST))
        assertFalse(CarPlayMediaButton.opensSiri(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertNull(CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_ASSIST))
        assertNull(CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_VOICE_ASSIST))
    }

    @Test
    fun ecarxPrivateVoiceCodesOpenSiri() {
        // ECARX's private 0x1F41(8001) voice code and the 200000-offset voice code (200231).
        assertTrue(CarPlayMediaButton.opensSiri(CarPlayMediaButton.ECARX_VOICE_8001))
        assertTrue(CarPlayMediaButton.opensSiri(CarPlayMediaButton.ECARX_OFFSET + KeyEvent.KEYCODE_VOICE_ASSIST))
        assertFalse(CarPlayMediaButton.opensSiri(CarPlayMediaButton.ECARX_ANSWER_8003))
    }

    @Test
    fun ecarxOffsetMediaKeysMapToCarPlayPresses() {
        // The reference app reads the steering wheel's media keys in a 200000-offset code space.
        assertEquals(CarPlayMediaButton.NEXT, CarPlayMediaButton.forKeyCode(CarPlayMediaButton.ECARX_OFFSET + KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(CarPlayMediaButton.PREVIOUS, CarPlayMediaButton.forKeyCode(CarPlayMediaButton.ECARX_OFFSET + KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(CarPlayMediaButton.ECARX_OFFSET + KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    @Test
    fun otherKeysAreLeftToTheSystem() {
        assertNull(CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_STOP))
    }

    @Test
    fun callKeysMapToCarPlayTelephonyPresses() {
        // The steering wheel's answer key (standard KEYCODE_CALL, ECARX 8003, and the offset form)
        // all answer; KEYCODE_ENDCALL (and its offset form) hang up.
        assertEquals(CarPlayMediaButton.ANSWER, CarPlayMediaButton.forCallKeyCode(KeyEvent.KEYCODE_CALL))
        assertEquals(CarPlayMediaButton.ANSWER, CarPlayMediaButton.forCallKeyCode(CarPlayMediaButton.ECARX_ANSWER_8003))
        assertEquals(CarPlayMediaButton.ANSWER, CarPlayMediaButton.forCallKeyCode(CarPlayMediaButton.ECARX_OFFSET + KeyEvent.KEYCODE_CALL))
        assertEquals(CarPlayMediaButton.HANGUP, CarPlayMediaButton.forCallKeyCode(KeyEvent.KEYCODE_ENDCALL))
        assertEquals(CarPlayMediaButton.HANGUP, CarPlayMediaButton.forCallKeyCode(CarPlayMediaButton.ECARX_OFFSET + KeyEvent.KEYCODE_ENDCALL))
        assertNull(CarPlayMediaButton.forCallKeyCode(KeyEvent.KEYCODE_MEDIA_NEXT))
    }

    @Test
    fun indicesMatchTheAdvertisedMediaHidReport() {
        // Media report usages: 0 none, 1 play, 2 pause, 3 play/pause, 4 next, 5 previous.
        assertEquals(3, CarPlayMediaButton.PLAY_PAUSE)
        assertEquals(4, CarPlayMediaButton.NEXT)
        assertEquals(5, CarPlayMediaButton.PREVIOUS)
    }
}
