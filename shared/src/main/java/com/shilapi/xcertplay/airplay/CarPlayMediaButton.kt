package com.shilapi.xcertplay.airplay

import android.view.KeyEvent

/**
 * Hardware media keys → CarPlay media HID presses (indices into [AirPlayHid]'s media report).
 *
 * Hardware play and pause keys both map to the toggle: BYD picks PLAY or PAUSE from its own idea of
 * the play state, and a wrong guess would make the button do nothing. Explicit play and pause
 * commands from media controllers use [PLAY] and [PAUSE].
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

    /** BYD's steering-wheel play/pause key; the firmware normally rewrites it to MEDIA_PLAY/PAUSE. */
    const val KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE = 353

    /** BYD's steering-wheel voice key: a short press, and the code the wheel sends for a long press. */
    const val KEYCODE_BYD_AUTO_MEDIA_VOICE = 304
    const val KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG = 312

    /** DiLink 3's play/pause key (KEYCODE_AUTO_MEDIA_PLAY_PAUSE), when the firmware does not rewrite it. */
    const val KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE_DILINK3 = 331

    /**
     * DiLink 3's CarPlay voice key, short and long. BYD's window manager keeps it for its own CarPlay
     * app, so only the wheel key service sees it (see [opensSiriWhileCarPlay]).
     */
    const val KEYCODE_BYD_CARPLAY_VOICE = 327
    const val KEYCODE_BYD_CARPLAY_VOICE_LONG = 328

    /**
     * Whether [keyCode] is a voice key that opens Siri. The BYD wheel sends each press as an
     * instant down/up pair, so a long press arrives as its own key rather than as a held one.
     */
    fun opensSiri(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
        keyCode == KeyEvent.KEYCODE_ASSIST ||
        keyCode == ECARX_VOICE_8001 ||
        keyCode == ECARX_OFFSET + KeyEvent.KEYCODE_VOICE_ASSIST ||
        keyCode == KEYCODE_BYD_AUTO_MEDIA_VOICE || keyCode == KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG

    /** The voice keys BYD sends only for CarPlay; they open Siri while a CarPlay session runs. */
    fun opensSiriWhileCarPlay(keyCode: Int): Boolean =
        keyCode == KEYCODE_BYD_CARPLAY_VOICE || keyCode == KEYCODE_BYD_CARPLAY_VOICE_LONG

    /** The two voice keys the wheel sends for a short press of its voice button. */
    fun isShortVoiceKey(keyCode: Int): Boolean =
        keyCode == KEYCODE_BYD_CARPLAY_VOICE || keyCode == KEYCODE_BYD_AUTO_MEDIA_VOICE

    /** The CarPlay press for [keyCode], or null when the key is not a media key CarPlay handles. */
    fun forKeyCode(keyCode: Int, experimentalDiLink3Keys: Boolean = false): Int? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_NEXT, ECARX_OFFSET + KeyEvent.KEYCODE_MEDIA_NEXT -> NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, ECARX_OFFSET + KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PREVIOUS
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        ECARX_OFFSET + KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> PLAY_PAUSE
        KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE -> PLAY_PAUSE
        KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE_DILINK3 -> if (experimentalDiLink3Keys) PLAY_PAUSE else null
        else -> null
    }

    /**
     * The CarPlay press the CarPlay screen itself should claim for [keyCode], or null to let the key
     * go on to the car.
     *
     * BYD hands the steering wheel's next/previous keys to whichever window is in front, and DiPlay's
     * CarPlay screen is that window whenever the driver is looking at it. Claiming them here is what
     * makes the wheel change tracks without the accessibility service, which a head unit with no ADB
     * cannot turn on.
     *
     * Only next and previous are claimed. Play/pause is left alone: BYD acts on it itself, and the
     * wheel key service owns it for DiLink 3. Nothing is claimed outside a CarPlay session, or during
     * a call, where the car's own call handling is what the driver expects from these keys.
     */
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
