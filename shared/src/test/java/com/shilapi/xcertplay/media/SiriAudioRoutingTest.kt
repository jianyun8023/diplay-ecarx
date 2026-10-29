package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Closeable

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SiriAudioRoutingTest {
    @Test fun siriUsesTheSelectedMediaOutputWithoutChangingNavigationOrCalls() {
        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        for (advanced in listOf(false, true)) {
            for (media in listOf(0, 3, 11)) {
                val renderer = type.declaredConstructors.single { it.parameterCount == 12 }
                    .apply { isAccessible = true }.newInstance(
                        AudioFormat(AudioCodecKind.LPCM, 48_000, 1, 100, "speechrecognition"),
                        advanced, false, media, 14, AudioFocusCoordinator(null, false, false),
                        14, 500, { _: String -> }, false, null, false,
                    ) as Closeable
                renderer.use {
                    val selection = type.getDeclaredMethod("mappedSelection").apply { isAccessible = true }
                        .invoke(renderer) as AudioChannelSelection
                    assertEquals(AudioChannel.ASSISTANT, selection.channel)
                    assertEquals(AudioContentType.SPEECH, selection.contentType)
                    val route = type.getDeclaredMethod("channelOverride", AudioChannel::class.java)
                        .apply { isAccessible = true }
                    assertEquals(media, route.invoke(renderer, selection.channel))
                    assertEquals(media, route.invoke(renderer, AudioChannel.MEDIA))
                    assertEquals(14, route.invoke(renderer, AudioChannel.NAVIGATION))
                    assertEquals(0, route.invoke(renderer, AudioChannel.PHONE))
                }
            }
        }
    }

    @Test fun siriCanCreateItsMediaTrackBeforeAnyMusicHasPlayed() {
        for (mediaChannel in listOf(0, 3)) {
            val reports = mutableListOf<String>()
            val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
            val renderer = type.declaredConstructors.single { it.parameterCount == 12 }
                .apply { isAccessible = true }.newInstance(
                    AudioFormat(AudioCodecKind.LPCM, 48_000, 1, 100, "speechrecognition"),
                    false, false, mediaChannel, 14, AudioFocusCoordinator(null, false, false),
                    14, 500, { line: String -> reports.add(line); Unit }, false, null, false,
                ) as Closeable
            try {
                type.getDeclaredMethod("createTrack").apply { isAccessible = true }.invoke(renderer)
                val route = if (mediaChannel == 0) "usage" else "streamType=3"
                assertTrue(reports.any { it.contains("route=$route ") && it.contains("trackState=1") })
                val attributes = type.getDeclaredField("trackAttributes").apply { isAccessible = true }
                    .get(renderer) as AudioAttributes
                assertEquals(AudioAttributes.USAGE_MEDIA, attributes.usage)
                if (mediaChannel == 0) assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, attributes.contentType)
            } finally {
                type.getDeclaredMethod("release").apply { isAccessible = true }.invoke(renderer)
                renderer.close()
            }
        }
    }
}
