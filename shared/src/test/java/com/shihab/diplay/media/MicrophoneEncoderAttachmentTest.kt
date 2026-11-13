package com.shihab.diplay.media

import android.Manifest
import android.media.AudioRecord
import com.shihab.diplay.airplay.AudioCodecKind
import com.shihab.diplay.airplay.MicrophoneConfig
import java.net.InetAddress
import java.net.DatagramSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord

/**
 * SDK attachment replay only: sender and encoder are fakes, not Opus/JNI encoding acceptance.
 * A shareable socket selects the sender before any separate encoder; Java socket I/O needs one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class MicrophoneEncoderAttachmentTest {
    private val diagnostics = CopyOnWriteArrayList<String>()
    private val factoryCalls = AtomicInteger()
    private var uplink: MicrophoneUplink? = null

    @Before fun setUp() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowAudioRecord.setSourceProvider {
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                    Thread.sleep(5)
                    return 0
                }
            }
        }
    }

    @After fun tearDown() {
        uplink?.close()
        ShadowAudioRecord.clearSource()
    }

    @Test fun nativeSenderPathNeverBuildsTheKotlinEncoder() {
        val next = MicrophoneUplink(
            config(),
            onDiagnostic = diagnostics::add,
            opusEncoderFactory = { factoryCalls.incrementAndGet(); fakeEncoder() },
            nativeSenderFactory = { _, _ -> fakeNativeSender() },
        )
        uplink = next

        assertTrue(next.start())

        assertEquals("Kotlin encoder must not be built when the native sender encodes", 0, factoryCalls.get())
        assertTrue(
            diagnostics.joinToString("\n"),
            diagnostics.any { it.contains("implementation=native encoder=libopus") },
        )
    }

    @Test fun unshareableSocketBuildsExactlyOneKotlinEncoder() {
        val next = MicrophoneUplink(
            config(),
            onDiagnostic = diagnostics::add,
            opusEncoderFactory = { factoryCalls.incrementAndGet(); fakeEncoder() },
            nativeSenderFactory = { _, _ -> null },
        )
        uplink = next

        assertTrue(next.start())

        assertEquals(1, factoryCalls.get())
        assertTrue(
            diagnostics.joinToString("\n"),
            diagnostics.any { it.contains("implementation=kotlin encoder=fake") },
        )
    }

    @Test fun missingEncoderFailsStartOnlyWhenThereIsNoNativeSender() {
        lateinit var recorder: AudioRecord
        lateinit var socket: DatagramSocket
        val next = MicrophoneUplink(
            config(),
            onDiagnostic = diagnostics::add,
            opusEncoderFactory = { factoryCalls.incrementAndGet(); null },
            nativeSenderFactory = { created, _ ->
                socket = created
                recorder = MicrophoneUplink::class.java.getDeclaredField("recorder")
                    .apply { isAccessible = true }.get(uplink) as AudioRecord
                null
            },
        )
        uplink = next

        assertFalse(next.start())
        assertEquals(1, factoryCalls.get())
        assertReleased(next, recorder, socket)
    }

    @Test fun encoderFactoryExceptionReleasesSdkResourcesAndPreservesTheFailure() {
        val failure = IllegalStateException("encoder creation failed")
        lateinit var recorder: AudioRecord
        lateinit var socket: DatagramSocket
        val next = MicrophoneUplink(
            config(),
            opusEncoderFactory = { throw failure },
            nativeSenderFactory = { created, _ ->
                socket = created
                recorder = MicrophoneUplink::class.java.getDeclaredField("recorder")
                    .apply { isAccessible = true }.get(uplink) as AudioRecord
                null
            },
        )
        uplink = next
        assertSame(failure, assertThrows(IllegalStateException::class.java) { next.start() })
        assertReleased(next, recorder, socket)
    }

    @Test fun senderFactoryExceptionReleasesSdkResourcesWithoutCreatingAnEncoder() {
        val failure = IllegalStateException("sender creation failed")
        lateinit var recorder: AudioRecord
        lateinit var socket: DatagramSocket
        val next = MicrophoneUplink(
            config(),
            opusEncoderFactory = { factoryCalls.incrementAndGet(); fakeEncoder() },
            nativeSenderFactory = { created, _ ->
                socket = created
                recorder = MicrophoneUplink::class.java.getDeclaredField("recorder")
                    .apply { isAccessible = true }.get(uplink) as AudioRecord
                throw failure
            },
        )
        uplink = next
        assertSame(failure, assertThrows(IllegalStateException::class.java) { next.start() })
        assertEquals(0, factoryCalls.get())
        assertReleased(next, recorder, socket)
    }

    private fun assertReleased(next: MicrophoneUplink, recorder: AudioRecord, socket: DatagramSocket) {
        assertEquals(AudioRecord.STATE_UNINITIALIZED, recorder.state)
        assertTrue(socket.isClosed)
        for (field in listOf("recorder", "socket", "opusEncoder", "nativeSender")) {
            assertNull(MicrophoneUplink::class.java.getDeclaredField(field).apply { isAccessible = true }.get(next))
        }
        next.close()
    }

    private fun fakeEncoder(): MicrophoneOpusEncoder = object : MicrophoneOpusEncoder {
        override val implementation = "fake"
        override val available = true
        override fun encode(pcm: ByteArray): List<ByteArray> = emptyList()
        override fun close() = Unit
    }

    private fun fakeNativeSender(): NativeUplinkSender {
        val constructor = NativeUplinkSender::class.java.getDeclaredConstructor(Long::class.javaPrimitiveType)
        constructor.isAccessible = true
        return constructor.newInstance(0L)
    }

    private fun config() = MicrophoneConfig(
        audioType = "media",
        sampleRate = 16_000,
        channels = 1,
        payloadType = 100,
        frameMillis = 20,
        host = InetAddress.getLoopbackAddress(),
        port = 9,
        key = ByteArray(32),
        codec = AudioCodecKind.OPUS,
    )
}
