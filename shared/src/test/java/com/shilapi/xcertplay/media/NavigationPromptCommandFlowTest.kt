package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.Socket
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAudioManager

/** Real plist command -> session listener -> controller -> media engine -> Android sink. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class NavigationPromptCommandFlowTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Before fun grantFocus() {
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    @Test fun realCommandsJoinConsecutivePromptsAndReleaseAfterTheTail() {
        Fixture().use { f ->
            val phone = f.phone()
            f.command(phone, "duckAudio", mapOf("durationMs" to 200L, "volume" to 0.25))
            val sent = shadowOf(manager).lastAudioFocusRequest
            assertNotNull(sent)
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, sent.durationHint)
            f.command(phone, "unduckAudio")
            idle(67)
            assertFalse(abandoned(sent))
            f.command(phone, "duckAudio")
            idle(1_000)
            assertSame(sent, shadowOf(manager).lastAudioFocusRequest)
            assertFalse(abandoned(sent))
            f.command(phone, "unduckAudio")
            idle(999)
            assertFalse(abandoned(sent))
            idle(1)
            assertTrue(abandoned(sent))
            assertEquals(1, f.reports.count { it.contains("navigation prompt focus requested") })
        }
    }

    @Test fun missingUnduckTimesOutFromTheLatestPhoneDuck() {
        Fixture().use { f ->
            val phone = f.phone()
            f.command(phone, "duckAudio")
            val sent = shadowOf(manager).lastAudioFocusRequest
            idle(29_000)
            f.command(phone, "duckAudio")
            idle(29_999)
            assertFalse(abandoned(sent))
            idle(1)
            assertTrue(abandoned(sent))
            assertTrue(f.reports.last().contains("reason=timeout"))
        }
    }

    @Test fun defaultAndMasterOffKeepPhoneCommandsFromRequestingFocus() {
        Fixture(promptFocus = false).use { f ->
            f.command(f.phone(), "duckAudio")
            assertNull(shadowOf(manager).lastAudioFocusRequest)
        }
        Fixture(masterFocus = false).use { f ->
            val phone = f.phone()
            f.command(phone, "duckAudio")
            f.command(phone, "duckAudio")
            f.command(phone, "unduckAudio")
            idle(30_000)
            assertNull(shadowOf(manager).lastAudioFocusRequest)
            assertEquals(1, f.reports.count { it.contains("skipped audioFocus=off") })
        }
    }

    @Test fun oldSessionUnduckAndCloseCannotReleaseTheCurrentPrompt() {
        Fixture().use { f ->
            val old = f.phone()
            val current = f.phone()
            f.command(old, "duckAudio")
            f.command(current, "duckAudio")
            val sent = shadowOf(manager).lastAudioFocusRequest
            f.command(old, "unduckAudio")
            old.close()
            idle(1_000)
            assertFalse(abandoned(sent))
            current.close()
            assertTrue(abandoned(sent))
            assertTrue(f.reports.last().contains("reason=session closed"))
        }
    }

    @Test fun lateDuckFromReplacedRecordedSessionCannotStealCurrentPrompt() {
        Fixture().use { f ->
            val old = f.phone()
            f.record(old)
            f.command(old, "duckAudio")
            val current = f.phone()
            f.record(current)
            f.command(current, "duckAudio")
            val sent = shadowOf(manager).lastAudioFocusRequest
            // RECORD replaces the active session but leaves the previous control socket alive.
            assertFalse(old.isClosed)
            f.command(old, "duckAudio")
            old.close()
            assertFalse("Closing the replaced session must not release the current prompt", abandoned(sent))
            f.command(current, "unduckAudio")
            idle(999)
            assertFalse(abandoned(sent))
            idle(1)
            assertTrue(abandoned(sent))
        }
    }

    @Test fun sessionCloseCancelsItsUnduckTailAndTheSinkCanServeTheNextPhone() {
        Fixture().use { f ->
            val first = f.phone()
            f.command(first, "duckAudio")
            val sent = shadowOf(manager).lastAudioFocusRequest
            f.command(first, "unduckAudio")
            first.close()
            assertTrue(abandoned(sent))
            val next = f.phone()
            f.command(next, "duckAudio")
            val replacement = shadowOf(manager).lastAudioFocusRequest
            assertNotSame(sent, replacement)
            idle(1_000)
            assertFalse(abandoned(replacement))
            // A control command already queued when the first socket closed must be inert.
            f.command(first, "duckAudio")
            f.command(first, "unduckAudio")
            idle(1_000)
            assertFalse(abandoned(replacement))
            next.close()
            assertTrue(abandoned(replacement))
        }
    }

    private inner class Fixture(masterFocus: Boolean = true, promptFocus: Boolean = true) : AutoCloseable {
        val reports = mutableListOf<String>()
        val sink = AndroidMediaSink(context = context, audioFocusEnabled = masterFocus,
            navigationPromptAudioFocus = promptFocus, onAudioDiagnostic = reports::add)
        private val engine = CarPlayMediaEngine(sink)
        private val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
        private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")
        private val controller = CarPlayController(context,
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            config, identity, PairingStore(), object : AirPlaySessionListener {}, engine, {})
        private val listener = CarPlayController::class.java.getDeclaredField("sessionListener")
            .apply { isAccessible = true }.get(controller) as AirPlaySessionListener
        private val phones = mutableListOf<AirPlaySession>()

        fun phone() = AirPlaySession(Socket(), config, identity, PairingStore(), null, listener, engine)
            .also(phones::add)

        fun record(phone: AirPlaySession) {
            val response = AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java)
                .apply { isAccessible = true }.invoke(phone,
                    RtspMessage.Request("RECORD", "*", "RTSP/1.0", emptyMap(), ByteArray(0))) as RtspMessage.Response
            assertEquals(200, response.status)
            assertSame(phone, CarPlayController::class.java.getDeclaredField("activeSession")
                .apply { isAccessible = true }.get(controller))
        }

        fun command(phone: AirPlaySession, type: String, params: Map<String, Any?> = emptyMap()) {
            val response = AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java)
                .apply { isAccessible = true }.invoke(phone,
                    RtspMessage.Request("POST", "/command", "RTSP/1.0", emptyMap(),
                        BplistCodec.encode(mapOf("type" to type, "params" to params)))) as RtspMessage.Response
            assertEquals(200, response.status)
        }

        override fun close() {
            phones.forEach(AirPlaySession::close)
            sink.close()
            controller.close()
            controller.awaitClosed(2_000)
        }
    }

    private fun idle(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    private fun abandoned(sent: ShadowAudioManager.AudioFocusRequest): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            shadowOf(manager).lastAbandonedAudioFocusRequest === sent.audioFocusRequest
        } else {
            shadowOf(manager).lastAbandonedAudioFocusListener === sent.listener
        }
}
