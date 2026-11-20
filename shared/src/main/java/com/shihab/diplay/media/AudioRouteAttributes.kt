package com.shihab.diplay.media

import android.media.AudioAttributes

/** Configured route attributes shared by playback and prompt focus. */
object AudioRouteAttributes {
    /** A supported legacy stream takes precedence over the route's usage-based attributes. */
    fun resolve(streamType: Int, usageAttributes: () -> AudioAttributes): AudioAttributes =
        LegacyAudioFallback.attributes(streamType) ?: usageAttributes()

    fun navigation(streamType: Int): AudioAttributes =
        resolve(streamType, NavigationAudioAttributes::create)
}
