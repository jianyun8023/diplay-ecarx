package com.shihab.diplay.media

import android.media.AudioAttributes
import android.util.Log

/** ECARX navigation attributes with standard speech on ROMs that reject the vendor content type. */
object NavigationAudioAttributes {
    private const val ECARX_CONTENT_TYPE = 20

    fun create(): AudioAttributes {
        val usage = AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        val attributes = try {
            AudioAttributes.Builder().setContentType(ECARX_CONTENT_TYPE).setUsage(usage).build()
        } catch (_: IllegalArgumentException) {
            null
        }
        if (attributes?.contentType == ECARX_CONTENT_TYPE) return attributes
        Log.w("DiPlayAudio", "Navigation content type $ECARX_CONTENT_TYPE is unsupported, using speech")
        return AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(usage).build()
    }
}
