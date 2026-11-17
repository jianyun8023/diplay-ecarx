package com.shihab.diplay.media

import android.content.Intent
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test fun aPromptVolumeBecomesTheCarPlayDuckPercentage() {
        assertEquals(80, EcarxCarPlayAudioHandshake.duckPercent(-13.9794f))
        assertEquals(50, EcarxCarPlayAudioHandshake.duckPercent(-6.0206f))
        assertEquals(0, EcarxCarPlayAudioHandshake.duckPercent(0f))
        assertEquals(0, EcarxCarPlayAudioHandshake.duckPercent(null))
        assertEquals(100, EcarxCarPlayAudioHandshake.duckPercent(-120f))
        assertEquals(0, EcarxCarPlayAudioHandshake.duckPercent(Float.NaN))
    }

    @Test fun anEnabledSinkOpensTheVendorModeAndSendsBothDuckCommands() {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val sent = mutableListOf<Intent>()
        val reports = mutableListOf<String>()
        val handshake = EcarxCarPlayAudioHandshake(
            app, enabled = { true }, report = { reports += it }, broadcast = { sent += it },
        )
        handshake.start()
        val request = shadowOf(audio).lastAudioFocusRequest.audioFocusRequest
        assertEquals(33, request.audioAttributes.contentType)
        assertEquals(1, request.audioAttributes.usage)
        assertTrue(reports.toString(), reports.any { it.contains("CarPlay audio mode opened") })

        handshake.onDuck(true, -13.9794f)
        val duck = sent.single { it.getStringExtra("command") == "duckAudio" }
        assertEquals(80, duck.getIntExtra("volume", -1))
        assertEquals(EcarxCarPlayAudioHandshake.ACTION_CAR_PLAY_DUCK_COMMAND, duck.action)

        handshake.onDuck(false, 0f)
        val unduck = sent.last()
        assertEquals("unduckAudio", unduck.getStringExtra("command"))
        assertEquals(0, unduck.getIntExtra("volume", -1))

        handshake.close()
        assertSame(request, shadowOf(audio).lastAbandonedAudioFocusRequest)
        assertTrue(reports.toString(), reports.any { it.contains("CarPlay duck=duckAudio percent=80") })
    }

    @Test fun aClosedPromptIsClearedEvenWhenThePhoneDisconnects() {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val sent = mutableListOf<Intent>()
        val handshake = EcarxCarPlayAudioHandshake(
            app, enabled = { true }, broadcast = { sent += it },
        )
        handshake.start()
        handshake.onDuck(true, -13.9794f)
        handshake.close()
        assertEquals("unduckAudio", sent.last().getStringExtra("command"))
        assertEquals(0, sent.last().getIntExtra("volume", -1))
    }

    @Test fun aDisabledSettingTouchesNeitherFocusNorTheCar() {
        val sent = mutableListOf<Intent>()
        val handshake = EcarxCarPlayAudioHandshake(
            app, enabled = { false }, broadcast = { sent += it },
        )
        handshake.start()
        handshake.onDuck(true, -13.9794f)
        handshake.close()
        assertNull(shadowOf(audio).lastAudioFocusRequest)
        assertTrue(sent.toString(), sent.isEmpty())
    }

    @Test fun aRejectedModeDoesNotChangeTheCarVolume() {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val sent = mutableListOf<Intent>()
        val reports = mutableListOf<String>()
        val handshake = EcarxCarPlayAudioHandshake(
            app, enabled = { true }, report = { reports += it }, broadcast = { sent += it },
        )
        handshake.start()
        handshake.onDuck(true, -13.9794f)
        assertTrue(sent.toString(), sent.isEmpty())
        assertTrue(reports.toString(), reports.any { it.contains("CarPlay duck signal skipped") })
    }
}
