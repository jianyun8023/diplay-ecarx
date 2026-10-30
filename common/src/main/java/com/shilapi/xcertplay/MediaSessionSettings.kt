package com.shilapi.xcertplay

import android.content.Context

// Keep the stored boolean and enum order: ANDROID delegates vehicle media to HY; ECARX is direct.
// Both publish a standard Android session, and the choice takes effect at the next connection.
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
