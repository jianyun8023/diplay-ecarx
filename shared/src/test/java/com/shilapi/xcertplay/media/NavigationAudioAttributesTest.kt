package com.shilapi.xcertplay.media

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
    @Test fun standardUsesTheExistingNavigationAttributes() {
        val attributes = NavigationAudioAttributes.create()
        assertEquals(12, attributes.usage)
        assertEquals(1, attributes.contentType)
    }

    @Test fun stockAndroidMustNotSilentlyPassAsOemContent20() {
        val error = assertThrows(IllegalArgumentException::class.java) { NavigationAudioAttributes.create(true) }
        assertTrue(error.message!!, error.message!!.contains("requested=12/20 resolved=12/0"))
    }

    @Test @Config(shadows = [OemBuilder::class])
    fun oemModeRetainsBothAttributesAndMatchesNavigationFocus() {
        val attributes = NavigationAudioAttributes.create(true)
        assertEquals(12, attributes.usage)
        assertEquals(20, attributes.contentType)
        val focus = navigationRouteAttributes(0, true)
        assertEquals(attributes, focus)
        assertEquals(1, navigationRouteAttributes(3, true).usage)
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
