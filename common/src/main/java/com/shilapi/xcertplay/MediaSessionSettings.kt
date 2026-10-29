package com.shilapi.xcertplay

import android.content.Context

internal enum class MediaSessionMode { ANDROID, ECARX }

internal object MediaSessionSettings {
    fun mode(context: Context): MediaSessionMode =
        if (context.getSharedPreferences("diplay_media_session", Context.MODE_PRIVATE).getBoolean("ecarx", false))
            MediaSessionMode.ECARX else MediaSessionMode.ANDROID

    fun setMode(context: Context, mode: MediaSessionMode) {
        context.getSharedPreferences("diplay_media_session", Context.MODE_PRIVATE)
            .edit().putBoolean("ecarx", mode == MediaSessionMode.ECARX).apply()
    }
}
