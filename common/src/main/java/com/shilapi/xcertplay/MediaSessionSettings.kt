package com.shilapi.xcertplay

import android.content.Context

// Keep the stored boolean and enum order: ANDROID leaves vehicle media to a standard-session reader
// such as HY; ECARX is direct. Both publish a standard Android session, and the choice takes effect at
// the next connection.
internal enum class MediaSessionMode { ANDROID, ECARX }

/**
 * Which press of the factory voice key DiPlay takes over on an ECARX head unit.
 *
 * The key service broadcasts both presses with `ecarx.extra.ECARX_KEY_ACTION_TYPE`: 0 for the short
 * press and 1 for the long one (the same two values HY's own receiver splits into key 6 and key 7).
 * DiPlay aborts only the chosen one, so the other still reaches the factory assistant. It takes effect
 * immediately: the receiver decides per press, so no reconnect is needed.
 */
internal enum class VoiceKeyPress(val type: Int) { SHORT(0), LONG(1) }

internal enum class HySiriKey(val value: Int, val label: String, vararg val codes: Int) {
    TOGGLE_155_156(0, "155/156", 155, 156),
    TOGGLE_312_303(1, "312/303", 312, 303),
}

internal object MediaSessionSettings {
    const val PREFS = "diplay_media_session"
    private const val KEY_VOICE_PRESS = "voice_key_press"
    private const val KEY_HY_ARTWORK = "hy_artwork_broadcast"

    fun wheelSiriEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("wheel_siri_enabled", true)

    fun setWheelSiriEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("wheel_siri_enabled", enabled).apply()
    }

    fun hySiriKey(context: Context): HySiriKey {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("hy_siri_key", 0)
        return HySiriKey.entries.firstOrNull { it.value == value } ?: HySiriKey.TOGGLE_155_156
    }

    fun setHySiriKey(context: Context, key: HySiriKey) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("hy_siri_key", key.value).apply()
    }

    fun mode(context: Context): MediaSessionMode =
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ecarx", true))
            MediaSessionMode.ECARX else MediaSessionMode.ANDROID

    fun setMode(context: Context, mode: MediaSessionMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("ecarx", mode == MediaSessionMode.ECARX).apply()
    }

    /** Whether ANDROID mode also sends HY its dashboard broadcast; read per publish, so it applies at once. */
    fun hyArtworkBroadcast(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HY_ARTWORK, true)

    fun setHyArtworkBroadcast(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HY_ARTWORK, enabled).apply()
    }

    fun voiceKeyPress(context: Context): VoiceKeyPress {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_VOICE_PRESS, VoiceKeyPress.SHORT.type)
        return VoiceKeyPress.entries.firstOrNull { it.type == stored } ?: VoiceKeyPress.SHORT
    }

    fun setVoiceKeyPress(context: Context, press: VoiceKeyPress) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_VOICE_PRESS, press.type).apply()
    }
}
