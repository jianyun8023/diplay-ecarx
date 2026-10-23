package com.shilapi.xcertplay.telecom

import android.content.Context

/**
 * The two opt-in switches behind [PhoneAudioRoute]: whether a CarPlay call, and whether another
 * app's voice-over-IP call, is moved onto the head unit's own audio path. Both default off — they
 * change which device the microphone and the earpiece belong to, which is exactly the kind of
 * behaviour that has to be asked for rather than assumed.
 */
object PhoneAudioRouteSettings {
    private const val PREFS = "diplay_phone_audio_route"
    private const val KEY_CALL = "carplay_call"
    private const val KEY_APPS = "voip_apps"

    /** Whether a CarPlay call is routed through the car's audio. */
    fun carPlayCalls(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CALL, false)

    fun setCarPlayCalls(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CALL, enabled).apply()
    }

    /** Whether another app's voice-over-IP call is routed through the car's audio. */
    fun voipApps(context: Context): Boolean =
        prefs(context).getBoolean(KEY_APPS, false)

    fun setVoipApps(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_APPS, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
