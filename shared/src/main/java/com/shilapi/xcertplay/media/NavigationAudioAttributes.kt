package com.shilapi.xcertplay.media

import android.media.AudioAttributes

/** Builds the selected navigation attributes without silently accepting a ROM's rewritten content type. */
object NavigationAudioAttributes {
    fun create(oemContent: Boolean = false): AudioAttributes {
        val content = if (oemContent) 20 else AudioAttributes.CONTENT_TYPE_SPEECH
        val attributes = AudioAttributes.Builder().setContentType(content)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build()
        require(attributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE &&
            attributes.contentType == content) {
            "Navigation attributes rejected: requested=12/$content resolved=${attributes.usage}/${attributes.contentType}"
        }
        return attributes
    }
}
