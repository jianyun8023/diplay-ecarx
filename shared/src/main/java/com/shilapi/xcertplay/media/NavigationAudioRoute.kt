package com.shilapi.xcertplay.media

import android.media.AudioManager

/**
 * Where a CarPlay navigation stream plays when navigation sound follows the iPhone (ECARX).
 *
 * The ECARX dcy11 head unit plays the navigation usage on a sub-channel of its audio controller,
 * which then lowers the music so far that it is barely heard, and nothing in Android sets how far.
 * On the music stream the prompt mixes into CarPlay music inside Android instead. The iPhone's
 * navigation app chooses: it sends duckAudio before a prompt when set to lower other audio, and
 * nothing when set to play over it. Its duckAudio arrives before the prompt's audio stream (car
 * test 2026-11-07: 80 and 210 ms), so the stream picks its route when it opens.
 */
internal object NavigationAudioRoute {
    /** The car's navigation channel: usage routing to its navigation bus. */
    const val CAR_CHANNEL = 0
    const val MUSIC_STREAM = AudioManager.STREAM_MUSIC

    fun choose(followsIphone: Boolean, configured: Int, iphoneDucking: Boolean): Int = when {
        !followsIphone -> configured
        iphoneDucking -> CAR_CHANNEL
        else -> MUSIC_STREAM
    }

    fun label(route: Int): String = when (route) {
        CAR_CHANNEL -> "car"
        MUSIC_STREAM -> "music"
        else -> "stream$route"
    }
}
