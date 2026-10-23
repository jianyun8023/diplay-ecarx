package com.shilapi.xcertplay.airplay

import android.view.KeyEvent

/**
 * Hardware media keys → CarPlay media HID presses (indices into [AirPlayHid]'s media report).
 *
 * Hardware play and pause keys both map to the toggle: the head unit picks PLAY or PAUSE from its
 * own idea of the play state, and a wrong guess would make the button do nothing. Explicit play and
 * pause commands from media controllers use [PLAY] and [PAUSE].
 */
object CarPlayMediaButton {
    const val PLAY = 1
    const val PAUSE = 2
    const val PLAY_PAUSE = 3
    const val NEXT = 4
    const val PREVIOUS = 5

    /** Telephony HID report indices (AirPlayHid.telephonyDescriptor), as CarPlayController sends them. */
    const val ANSWER = 1   // Hook Switch
    const val HANGUP = 3   // Drop

    /**
     * ECARX (领克 / 亿咖通) steering wheels report keys in three code spaces, not just the standard
     * Android set. The reference app normalises all three before dispatching, so DiPlay must
     * recognise them too, otherwise the voice / media keys reach no CarPlay action on these head units.
     *
     * 1. Standard Android codes (KEYCODE_VOICE_ASSIST=219, media keys, etc.).
     * 2. ECARX private 0x1Fxx space: 0x1F41(8001)=voice, 0x1F43(8003)=answer.
     * 3. ECARX 200000-offset space (standard keyCode + 200000): 0x30E27(200231)=voice,
     *    0x30D95(200085)=play/pause, 0x30D97(200087)=next, 0x30D98(200088)=prev.
     */
    const val ECARX_VOICE_8001 = 0x1F41
    const val ECARX_ANSWER_8003 = 0x1F43
    const val ECARX_OFFSET = 200_000

    /** Whether [keyCode] is a voice key that opens Siri, in any of the three code spaces above. */
    fun opensSiri(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
        keyCode == KeyEvent.KEYCODE_ASSIST ||
        keyCode == ECARX_VOICE_8001 ||
        keyCode == ECARX_OFFSET + KeyEvent.KEYCODE_VOICE_ASSIST

    /** The voice key a short press of the wheel's voice button sends. */
    fun isShortVoiceKey(keyCode: Int): Boolean = opensSiri(keyCode)

    /** The CarPlay press for [keyCode], or null when the key is not a media key CarPlay handles. */
    fun forKeyCode(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_NEXT, ECARX_OFFSET + KeyEvent.KEYCODE_MEDIA_NEXT -> NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, ECARX_OFFSET + KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PREVIOUS
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        ECARX_OFFSET + KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> PLAY_PAUSE
        else -> null
    }

    /**
     * The CarPlay telephony press for [keyCode], or null when the key is not a call key. The
     * reference app routes the steering wheel's answer key (ECARX private 0x1F43=8003 and the
     * standard KEYCODE_CALL) to "answer" and KEYCODE_ENDCALL to "hangup", mirroring its SWKeyActivity
     * answer/hangup actions. The 200000-offset call keys are recognised too.
     */
    fun forCallKeyCode(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_CALL, ECARX_ANSWER_8003, ECARX_OFFSET + KeyEvent.KEYCODE_CALL -> ANSWER
        KeyEvent.KEYCODE_ENDCALL, ECARX_OFFSET + KeyEvent.KEYCODE_ENDCALL -> HANGUP
        else -> null
    }

    /**
     * The CarPlay press the CarPlay screen itself should claim for [keyCode], or null to let the key
     * go on to the car.
     *
     * The head unit hands the steering wheel's next/previous keys to whichever window is in front,
     * and DiPlay's CarPlay screen is that window whenever the driver is looking at it. Claiming them
     * here is what makes the wheel change tracks without the accessibility service, which a head unit
     * with no ADB cannot turn on.
     *
     * Only next and previous are claimed. Play/pause is left alone: the car acts on it itself, and
     * the wheel key service owns it for the media-session route. Nothing is claimed outside a CarPlay
     * session, or during a call, where the car's own call handling is what the driver expects.
     */
    fun forCarPlayWindow(
        keyCode: Int,
        sessionActive: Boolean,
        inCall: Boolean,
    ): Int? {
        if (!sessionActive || inCall) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PREVIOUS
            else -> null
        }
    }
}
