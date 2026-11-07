package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], manifest = Config.NONE)
class NavigationPromptFocusTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val reports = mutableListOf<String>()
    private val helpers = mutableListOf<NavigationPromptFocus>()

    @Before fun grantFocus() {
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }
    @After fun closeHelpers() = helpers.forEach(NavigationPromptFocus::close)

    @Test fun duckRequestsDuckableTransientFocusForTheNavigationRoute() {
        val focus = focus()
        focus.onDuck()
        val sent = shadowOf(manager).lastAudioFocusRequest
        assertNotNull(sent)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, sent.durationHint)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, sent.audioFocusRequest.audioAttributes.usage)
        }
        assertEquals(listOf("Audio: navigation prompt focus requested trigger=duck gain=3 usage=12 contentType=1 granted=1"), reports)
    }

    @Test fun unduckReleasesAfterTheBufferedTailAndANewPromptKeepsTheRequest() {
        val focus = focus()
        focus.onDuck()
        val sent = shadowOf(manager).lastAudioFocusRequest
        focus.onUnduck()
        idle(NavigationPromptFocus.RELEASE_DELAY_MILLIS - 1)
        assertFalse(abandoned(sent))
        // 08:54:27 in #946: unduckAudio, then duckAudio 67 ms later for the next prompt.
        focus.onDuck()
        idle(NavigationPromptFocus.RELEASE_DELAY_MILLIS)
        assertSame(sent, shadowOf(manager).lastAudioFocusRequest)
        assertFalse(abandoned(sent))
        focus.onUnduck()
        idle(NavigationPromptFocus.RELEASE_DELAY_MILLIS)
        assertTrue(abandoned(sent))
        assertEquals(2, reports.size)
        assertTrue(reports.last(), reports.last().startsWith("Audio: navigation prompt focus released reason=unduck result="))
    }

    @Test fun missingUnduckReleasesAfterTheTimeoutCountedFromTheLastDuck() {
        val focus = focus()
        focus.onDuck()
        val sent = shadowOf(manager).lastAudioFocusRequest
        idle(NavigationPromptFocus.TIMEOUT_MILLIS - 1_000)
        focus.onDuck()
        idle(NavigationPromptFocus.TIMEOUT_MILLIS - 1)
        assertFalse(abandoned(sent))
        idle(1)
        assertTrue(abandoned(sent))
        assertTrue(reports.last().contains("released reason=timeout"))
        focus.onUnduck()
        idle(NavigationPromptFocus.RELEASE_DELAY_MILLIS)
        assertEquals(2, reports.size)
    }

    @Test fun deniedRequestIsNotHeldAndTheNextPromptAsksAgain() {
        val focus = focus()
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        focus.onDuck()
        val denied = shadowOf(manager).lastAudioFocusRequest
        assertTrue(reports.single().endsWith("granted=0"))
        focus.onUnduck()
        idle(NavigationPromptFocus.TIMEOUT_MILLIS)
        assertEquals(1, reports.size)
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        focus.onDuck()
        assertNotSame(denied, shadowOf(manager).lastAudioFocusRequest)
        assertTrue(reports.last().endsWith("granted=1"))
    }

    @Test fun closeReleasesAtOnceAndIgnoresLaterPrompts() {
        val focus = focus()
        focus.onDuck()
        val sent = shadowOf(manager).lastAudioFocusRequest
        focus.close()
        assertTrue(abandoned(sent))
        assertTrue(reports.last().contains("released reason=closed"))
        focus.onDuck()
        idle(NavigationPromptFocus.TIMEOUT_MILLIS)
        assertSame(sent, shadowOf(manager).lastAudioFocusRequest)
        assertEquals(2, reports.size)
    }

    @Test fun optionOffOrAudioFocusOffNeverRequests() {
        focus(enabled = false).onDuck()
        val focusOff = focus(audioFocus = false)
        focusOff.onDuck()
        focusOff.onUnduck()
        focusOff.onDuck()
        idle(NavigationPromptFocus.TIMEOUT_MILLIS)
        assertNull(shadowOf(manager).lastAudioFocusRequest)
        assertEquals(listOf("Audio: navigation prompt focus skipped audioFocus=off"), reports)
    }

    @Test fun focusChangesWhileHeldAreReported() {
        focus().onDuck()
        shadowOf(manager).lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        idle(0)
        assertEquals("Audio: navigation prompt focus change=-2", reports.last())
    }

    @Test fun routeAttributesFollowTheNavigationAudioStreamSetting() {
        val automatic = navigationRouteAttributes(0)
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, automatic.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, automatic.contentType)
        assertEquals(AudioAttributes.USAGE_MEDIA, navigationRouteAttributes(AudioManager.STREAM_MUSIC).usage)
        // BYD's stream 15 is not an Android legacy stream; the track keeps usage attributes for it.
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, navigationRouteAttributes(15).usage)
    }

    @Test fun sinkAsksOnlyWithBothSettingsOnAndUsesTheChosenNavigationStream() {
        val focusOff = AndroidMediaSink(context = context, audioFocusEnabled = false,
            navigationPromptAudioFocus = true, onAudioDiagnostic = reports::add)
        focusOff.onAudioDuck(true)
        focusOff.close()
        val optionOff = AndroidMediaSink(context = context, audioFocusEnabled = true, onAudioDiagnostic = reports::add)
        optionOff.onAudioDuck(true)
        optionOff.close()
        assertNull(shadowOf(manager).lastAudioFocusRequest)

        // #946: the Harman head unit plays navigation on the media stream (3).
        val sink = AndroidMediaSink(context = context, audioFocusEnabled = true,
            navigationChannel = AudioManager.STREAM_MUSIC, navigationPromptAudioFocus = true,
            onAudioDiagnostic = reports::add)
        sink.onAudioDuck(true)
        val sent = shadowOf(manager).lastAudioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, sent.durationHint)
        assertEquals(AudioManager.STREAM_MUSIC, sent.streamType)
        assertTrue(reports.toString(), reports.any { it.startsWith("Audio: navigation prompt focus requested") && it.contains("usage=1 ") })
        sink.close()
        assertTrue(abandoned(sent))
        assertTrue(reports.toString(), reports.any { it.startsWith("Audio: navigation prompt focus released reason=closed") })
    }

    @Test fun soundWithoutDuckAudioHoldsFocusUntilItFallsSilent() {
        // AMap set to mix with music: the iPhone sends no duckAudio, only the navigation sound.
        val prompt = focus()
        prompt.onNavigationAudio(true)
        val sent = shadowOf(manager).lastAudioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, sent.durationHint)
        assertEquals("held", prompt.state())
        assertTrue(reports.toString(), reports.single().startsWith("Audio: navigation prompt focus requested trigger=audio "))
        idle(NavigationPromptFocus.TIMEOUT_MILLIS + 1)
        assertFalse("sound that still plays outlives the duck timeout", abandoned(sent))
        prompt.onNavigationAudio(false)
        idle(NavigationPromptFocus.AUDIBLE_RELEASE_DELAY_MILLIS - 1)
        assertFalse(abandoned(sent))
        idle(2)
        assertTrue(abandoned(sent))
        assertEquals("none", prompt.state())
        assertTrue(reports.last(), Regex("released reason=silence result=\\S+ heldMs=\\d+$").containsMatchIn(reports.last()))
    }

    @Test fun soundAgainBeforeTheReleaseKeepsTheSameRequest() {
        val prompt = focus()
        prompt.onNavigationAudio(true)
        val sent = shadowOf(manager).lastAudioFocusRequest
        prompt.onNavigationAudio(false)
        idle(NavigationPromptFocus.AUDIBLE_RELEASE_DELAY_MILLIS - 1)
        prompt.onNavigationAudio(true)
        idle(NavigationPromptFocus.AUDIBLE_RELEASE_DELAY_MILLIS + 1)
        assertSame(sent, shadowOf(manager).lastAudioFocusRequest)
        assertFalse(abandoned(sent))
    }

    @Test fun duckAndSoundShareOneRequestAndTheLastOneToEndReleasesIt() {
        val prompt = focus()
        prompt.onDuck()
        val sent = shadowOf(manager).lastAudioFocusRequest
        prompt.onNavigationAudio(true)
        assertSame(sent, shadowOf(manager).lastAudioFocusRequest)
        prompt.onUnduck()
        idle(NavigationPromptFocus.RELEASE_DELAY_MILLIS + 1)
        assertFalse("the prompt is still playing", abandoned(sent))
        prompt.onNavigationAudio(false)
        idle(NavigationPromptFocus.AUDIBLE_RELEASE_DELAY_MILLIS + 1)
        assertTrue(abandoned(sent))
    }

    @Test fun silenceWhileTheIphoneStillDucksWaitsForUnduck() {
        val prompt = focus()
        prompt.onDuck()
        val sent = shadowOf(manager).lastAudioFocusRequest
        prompt.onNavigationAudio(true)
        prompt.onNavigationAudio(false)
        idle(NavigationPromptFocus.RELEASE_DELAY_MILLIS + 1)
        assertFalse(abandoned(sent))
        prompt.onUnduck()
        idle(NavigationPromptFocus.RELEASE_DELAY_MILLIS + 1)
        assertTrue(abandoned(sent))
    }

    @Test fun soundThatNeverEndsIsBounded() {
        val prompt = focus()
        prompt.onNavigationAudio(true)
        val sent = shadowOf(manager).lastAudioFocusRequest
        idle(NavigationPromptFocus.STREAM_TIMEOUT_MILLIS + 1)
        assertTrue(abandoned(sent))
    }

    @Test fun soundRespectsTheOptionAndTheAudioFocusSetting() {
        focus(enabled = false).onNavigationAudio(true)
        focus(audioFocus = false).onNavigationAudio(true)
        assertNull(shadowOf(manager).lastAudioFocusRequest)
    }

    @Test fun audibleDetectionIgnoresDigitalSilenceAndDither() {
        fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { bytes ->
            samples.forEachIndexed { i, v -> bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte() }
        }
        assertFalse(isAudiblePcm16(pcm(0, 0, 0, 0), 0, 8, 64))
        assertFalse(isAudiblePcm16(pcm(3, -2, 63, -63), 0, 8, 64))
        assertTrue(isAudiblePcm16(pcm(0, 0, -64, 0), 0, 8, 64))
        assertTrue(isAudiblePcm16(pcm(0, 12000), 0, 4, 64))
        // Only the given range counts.
        assertFalse(isAudiblePcm16(pcm(12000, 0, 0), 2, 4, 64))
    }

    private fun focus(enabled: Boolean = true, audioFocus: Boolean = true, channel: Int = 0) =
        NavigationPromptFocus(context, enabled, audioFocus, { navigationRouteAttributes(channel) }, reports::add)
            .also(helpers::add)

    private fun idle(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private fun abandoned(sent: ShadowAudioManager.AudioFocusRequest): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            shadowOf(manager).lastAbandonedAudioFocusRequest === sent.audioFocusRequest
        } else {
            shadowOf(manager).lastAbandonedAudioFocusListener === sent.listener
        }
}
