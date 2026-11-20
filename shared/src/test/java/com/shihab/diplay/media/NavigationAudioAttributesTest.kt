package com.shihab.diplay.media

import android.media.AudioAttributes
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NavigationAudioAttributesTest {
    @Test fun stockAndroidFallsBackToStandardNavigationSpeech() {
        val attributes = NavigationAudioAttributes.create()
        assertEquals(12, attributes.usage)
        assertEquals(1, attributes.contentType)
    }

    @Test @Config(shadows = [OemBuilder::class])
    fun ecarxNavigationAttributesAreUsedForPlaybackAndFocus() {
        val attributes = NavigationAudioAttributes.create()
        assertEquals(12, attributes.usage)
        assertEquals(20, attributes.contentType)
        val focus = AudioRouteAttributes.navigation(0)
        assertEquals(attributes, focus)
        assertEquals(1, AudioRouteAttributes.navigation(3).usage)
    }

    @Implements(AudioAttributes.Builder::class)
    class OemBuilder {
        @RealObject private lateinit var builder: AudioAttributes.Builder
        @Implementation fun setContentType(value: Int): AudioAttributes.Builder {
            ReflectionHelpers.setField(builder, "mContentType", value)
            return builder
        }
    }
}
