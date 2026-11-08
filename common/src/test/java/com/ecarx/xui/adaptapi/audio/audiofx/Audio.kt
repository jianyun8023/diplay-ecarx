package com.ecarx.xui.adaptapi.audio.audiofx

import android.content.Context

/** Independent read-only endpoint fixture; never packaged in an APK. */
class Audio(private val state: IAudioState) {
    fun getAudioState(): IAudioState = state

    companion object {
        var instance: Audio? = null
        @JvmStatic fun create(context: Context): Audio? = instance
    }
}

interface IAudioState {
    fun getSoundStageOptimizedSeat(): Int
}
