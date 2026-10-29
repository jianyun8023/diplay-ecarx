package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], manifest = Config.NONE)
class AudioRouteAttributesTest {
    @Test fun legacyMusicOverridesTheUsageAttributesWithoutEvaluatingThem() {
        val attributes = AudioRouteAttributes.resolve(AudioManager.STREAM_MUSIC) {
            error("A supported legacy stream must not evaluate the usage fallback")
        }
        assertEquals(AudioAttributes.USAGE_MEDIA, attributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_MUSIC, attributes.contentType)
        assertEquals(attributes, AudioRouteAttributes.navigation(AudioManager.STREAM_MUSIC, oemContent = true))
    }

    @Test fun automaticAndUnsupportedLegacyStreamsKeepTheirUsageFallback() {
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        for (channel in listOf(0, 13)) {
            assertSame(media, AudioRouteAttributes.resolve(channel) { media })
            val navigation = AudioRouteAttributes.navigation(channel)
            assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, navigation.usage)
            assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, navigation.contentType)
            assertEquals(navigation, navigationRouteAttributes(channel))
        }
    }
}
