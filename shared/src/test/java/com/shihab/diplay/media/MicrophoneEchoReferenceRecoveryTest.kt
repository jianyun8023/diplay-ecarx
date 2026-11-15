package com.shihab.diplay.media

import android.Manifest
import android.media.AudioRecord
import com.shihab.diplay.airplay.AudioCodecKind
import com.shihab.diplay.airplay.MicrophoneConfig
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord

/** Real Rust/Speex/UDP pipeline with a gated SDK recorder; exact ring contents are checked in Rust. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MicrophoneEchoReferenceRecoveryTest {
    private val source = AtomicReference<EchoReference?>()
    private var afterSnapshot: (() -> Unit)? = null
    private val snapshots = CopyOnWriteArrayList<EchoReference?>()
    private val producers = mutableListOf<EchoReference>()
    private var capture: Capture? = null

    @Before fun setUp() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @After fun tearDown() {
        capture?.close()
        producers.forEach(EchoReference::close)
        ShadowAudioRecord.clearSource()
    }

    @Test fun activeCaptureRefreshesEveryFrameEvenWithinOneSdkRead() {
        val original = reference()
        val replacement = reference()
        source.set(original)
        val live = startCapture()
        afterSnapshot = { source.set(replacement) }
        live.frames(2)
        assertEquals(listOf(original, replacement), snapshots.toList())
        assertTrue(field(live.uplink, "softwareEcho") as Boolean)
        live.assertSameRecorder()
        live.close()
        assertNotEquals(0L, field(replacement, "handle"))
    }

    @Test fun missingClosedAndPrebufferingSourcesRecoverWithoutReopeningTheRecorder() {
        source.set(reference())
        val live = startCapture()
        live.frames()
        source.set(null)
        live.frames()
        source.set(reference().apply { close() })
        live.frames()
        source.set(reference())
        live.frames()
        assertTrue(field(live.uplink, "softwareEcho") as Boolean)
        live.assertSameRecorder()
    }

    @Test fun incompatibleRateRestoresPlatformEffectsAndKeepsSending() {
        source.set(reference())
        val live = startCapture()
        live.frames()
        source.set(reference(RATE / 2))
        live.frames()
        assertFalse(field(live.uplink, "softwareEcho") as Boolean)
        snapshots.clear()
        live.frames()
        assertTrue("Disabled Speex must stop looking up output rings", snapshots.isEmpty())
        live.assertSameRecorder()
    }

    @Test fun closedProducerAndCloseBetweenLookupAndRetainCannotEndCapture() {
        val original = reference()
        source.set(original)
        val live = startCapture()
        original.close()
        live.frames()
        val candidate = reference()
        source.set(candidate)
        afterSnapshot = { candidate.close() }
        live.frames()
        source.set(reference())
        live.frames()
        assertTrue(field(live.uplink, "softwareEcho") as Boolean)
        live.assertSameRecorder()
    }

    private fun startCapture() = Capture().also { capture = it; it.start(); snapshots.clear() }

    private inner class Capture : AutoCloseable {
        private val permits = LinkedBlockingQueue<Int>()
        private val reads = LinkedBlockingQueue<Unit>()
        private val recorders = CopyOnWriteArrayList<AudioRecord>()
        private val receiver = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5_000 }
        private val config = MicrophoneConfig(
            audioType = "telephony", sampleRate = RATE, channels = 1, payloadType = 100, frameMillis = 20,
            host = receiver.localAddress, port = receiver.localPort, key = ByteArray(32), codec = AudioCodecKind.LPCM,
        )
        val uplink = MicrophoneUplink(config, speakerphoneCall = true, echoReferenceSource = {
            val current = source.get()
            val action = afterSnapshot
            afterSnapshot = null
            action?.invoke()
            snapshots.add(current)
            current
        })
        private lateinit var recorder: AudioRecord
        private lateinit var worker: Thread
        private var closed = false

        fun start() {
            ShadowAudioRecord.setSourceProvider { owner ->
                recorders.addIfAbsent(owner)
                object : ShadowAudioRecord.AudioRecordSource {
                    override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                        reads.add(Unit)
                        val count = checkNotNull(permits.poll(5, TimeUnit.SECONDS))
                        if (count < 0) return count
                        check(count <= size)
                        buffer.fill(1, offset, offset + count)
                        return count
                    }
                }
            }
            assertTrue(uplink.start())
            worker = field(uplink, "thread") as Thread
            awaitRead()
            recorder = field(uplink, "recorder") as AudioRecord
            assertTrue(field(uplink, "softwareEcho") as Boolean)
        }

        fun frames(count: Int = 1) {
            permits.add(config.frameBytes * count)
            repeat(count) {
                val packet = DatagramPacket(ByteArray(4096), 4096)
                receiver.receive(packet)
                assertEquals(config.frameBytes + 36, packet.length)
            }
            awaitRead()
        }

        fun assertSameRecorder() {
            assertSame(recorder, field(uplink, "recorder"))
            assertEquals(listOf(recorder), recorders.toList())
            assertEquals(AudioRecord.RECORDSTATE_RECORDING, recorder.recordingState)
        }

        private fun awaitRead() { assertNotNull(reads.poll(5, TimeUnit.SECONDS)) }

        override fun close() {
            if (closed) return
            closed = true
            permits.offer(AudioRecord.ERROR_DEAD_OBJECT)
            uplink.close()
            if (::worker.isInitialized) {
                worker.join(5_000)
                assertFalse(worker.isAlive)
            }
            receiver.close()
            assertNull(field(uplink, "nativeSender"))
            assertNull(field(uplink, "recorder"))
            if (::recorder.isInitialized) assertEquals(AudioRecord.STATE_UNINITIALIZED, recorder.state)
        }
    }

    private fun reference(rate: Int = RATE) = EchoReference(rate).also(producers::add)
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)

    private companion object { const val RATE = 16_000 }
}
