package com.shihab.diplay.media

import android.content.Intent
import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EcarxCarPlayAudioHandshakeTest {
    private val app = RuntimeEnvironment.getApplication()
    private val audio = app.getSystemService(AudioManager::class.java)
    private val sent = mutableListOf<Intent>()
    private var mode: Boolean? = false
    private var acceptsMode = true

    private fun handshake() = EcarxCarPlayAudioHandshake(
        app, broadcast = { sent += it }, alternateAudioOn = {
            if (mode == false && shadowOf(audio).lastAudioFocusRequest != null &&
                shadowOf(audio).lastAudioFocusRequest.audioFocusRequest.audioAttributes.contentType == 33 &&
                acceptsMode) mode = true
            mode
        },
    )

    @Test fun aPromptOpensTheVendorModeAndMarksWithoutAttenuating() {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val handshake = handshake()
        assertTrue(handshake.onPrompt(true))
        val request = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        assertEquals(33, request.audioAttributes.contentType)
        assertEquals(1, request.audioAttributes.usage)
        // The first marker is preceded by one unduck: a crashed earlier session can leave the
        // receiver's duck flag set with no firmware timeout to clear it, and the duck that
        // immediately follows bounds any misbehaving restore of that sweep.
        assertEquals(listOf("unduckAudio", "duckAudio"), sent.map { it.getStringExtra("command") })
        assertTrue(sent.all { it.getIntExtra("volume", -1) == 0 })
        assertTrue(handshake.isSignalling())
        handshake.close()
        assertSame(request, shadowOf(audio).lastAbandonedAudioFocusRequest)
        assertFalse(handshake.isSignalling())
    }

    @Test fun anAlreadyOpenModeIsAdoptedWithoutTogglingOrAbandoningIt() {
        mode = true
        val handshake = handshake()
        assertTrue(handshake.onPrompt(true))
        assertNull(shadowOf(audio).lastAudioFocusRequest)
        handshake.close()
        assertNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun repeatedPromptMarksDoNotRepeatTheDuckBroadcast() {
        mode = true
        val handshake = handshake()
        repeat(3) { assertTrue(handshake.onPrompt(true)) }
        assertEquals(listOf("unduckAudio", "duckAudio"), sent.map { it.getStringExtra("command") })
        handshake.onPrompt(false)
        assertEquals(
            listOf("unduckAudio", "duckAudio", "duckAudio", "unduckAudio"),
            sent.map { it.getStringExtra("command") },
        )
        assertTrue(sent.all { it.getIntExtra("volume", -1) == 0 })
    }

    @Test fun unduckKeepsTheNavigationVolumeAdjustedByTheWheel() {
        mode = true
        // The vendor snapshots and restores the current focus group's index even at volume=0.
        val groups = intArrayOf(20, 10)
        var focusGroup = 1
        var savedGroup = -1
        var savedVolume = 0
        val handshake = EcarxCarPlayAudioHandshake(app, alternateAudioOn = { mode }, broadcast = { intent ->
            val volume = intent.getIntExtra("volume", -1)
            assertEquals(0, volume)
            if (intent.getStringExtra("command") == "duckAudio") {
                savedGroup = focusGroup
                savedVolume = groups[focusGroup]
                groups[focusGroup] = maxOf(1, ((100 - volume) * groups[focusGroup]) / 100)
            } else if (savedGroup >= 0) {
                groups[savedGroup] = savedVolume
                savedGroup = -1
            }
        })
        handshake.onPrompt(true)
        groups[1] = 16
        handshake.onPrompt(false)
        assertArrayEquals(intArrayOf(20, 16), groups)
        handshake.close()
    }

    @Test fun closePreventsLateAudioFromReopeningTheMode() {
        mode = true
        val handshake = handshake()
        handshake.onPrompt(true)
        handshake.close()
        val count = sent.size
        assertFalse(handshake.onPrompt(true))
        assertEquals(count, sent.size)
        handshake.close()
        assertEquals(count, sent.size)
    }

    @Test fun anUnreadableModeDoesNotAttemptAToggle() {
        mode = null
        val handshake = handshake()
        assertFalse(handshake.onPrompt(true))
        assertNull(shadowOf(audio).lastAudioFocusRequest)
        assertTrue(sent.isEmpty())
    }

    @Test fun aRejectedModeDoesNotSendAPromptMarker() {
        acceptsMode = false
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val handshake = handshake()
        assertFalse(handshake.onPrompt(true))
        assertTrue(sent.isEmpty())
    }

    @Test fun aGrantedRequestDoesNotProveThatTheModeIsOn() {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val handshake = EcarxCarPlayAudioHandshake(app, alternateAudioOn = { false }, broadcast = { sent += it })
        assertFalse(handshake.onPrompt(true))
        assertTrue(sent.isEmpty())
        assertTrue(handshake.state().contains("ecarxAudioMode=off"))
        assertTrue(handshake.state().contains("modeProbe=unsupported"))
        // The probe failed after a grant: the out-of-stack request goes back, not left behind.
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        handshake.close()
    }

    @Test fun anUnsupportedProbeDisablesTheMarkerForTheSession() {
        mode = null
        val handshake = handshake()
        assertFalse(handshake.onPrompt(true))
        assertTrue(sent.isEmpty())
        // The vendor mode appearing later does not rearm the disabled session.
        mode = true
        assertFalse(handshake.onPrompt(true))
        assertTrue(sent.isEmpty())
        assertTrue(handshake.state().contains("modeProbe=unsupported"))
        handshake.close()
        assertFalse(handshake.onPrompt(false))
    }

    @Test fun aVerifiedModeReadFailingLaterIsTransientNotUnsupported() {
        mode = true
        val handshake = handshake()
        assertTrue(handshake.onPrompt(true))
        mode = null
        // A verified session keeps its sent marker; the unreadable state is a transient refusal.
        assertFalse(handshake.onPrompt(true))
        assertTrue(handshake.state().contains("modeProbe=verified"))
        handshake.onPrompt(false)
        assertEquals(
            listOf("unduckAudio", "duckAudio", "duckAudio", "unduckAudio"),
            sent.map { it.getStringExtra("command") },
        )
    }
}
