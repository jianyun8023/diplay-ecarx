package com.shilapi.xcertplay.media

import android.media.AudioAttributes

/** Configured route attributes shared by playback, prompt focus and the Settings sound test. */
object AudioRouteAttributes {
    /** A supported legacy stream takes precedence over the route's usage-based attributes. */
    fun resolve(streamType: Int, usageAttributes: () -> AudioAttributes): AudioAttributes =
        LegacyAudioFallback.attributes(streamType) ?: usageAttributes()

    fun navigation(streamType: Int, oemContent: Boolean = false): AudioAttributes =
        resolve(streamType) { NavigationAudioAttributes.create(streamType == 0 && oemContent) }
}
