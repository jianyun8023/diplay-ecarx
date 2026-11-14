package com.shihab.diplay.media

import android.Manifest
import android.media.AudioRecord
import com.shihab.diplay.airplay.AudioCodecKind
import com.shihab.diplay.airplay.MicrophoneConfig
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

/**
 * Real uplink start/capture/close with a gated SDK recorder and an observing fake AEC. Echo rings
 * and their retained lifetimes use the host Rust core; this is not Speex or device-AEC acceptance.
 * Lifetime/rate-boundary tests also pause SDK reads and invoke the private read adapter for exact
 * timestamps and a controlled producer-close race, independently of platform timestamp calibration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MicrophoneEchoReferenceRecoveryTest {
    private val source = AtomicReference<EchoReference?>()
    private val afterSourceSnapshot = AtomicReference<(() -> Unit)?>()
    private val producers = mutableListOf<EchoReference>()
    private val captures = mutableListOf<Capture>()

    @Before fun setUp() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @After fun tearDown() {
        captures.forEach(Capture::close)
        producers.forEach(EchoReference::close)
        ShadowAudioRecord.clearSource()
    }

    @Test fun activeCaptureFollowsTheReplacementWithoutRecreatingItsRecorderOrSharingOldWrites() {
        val original = constantReference(111)
        source.set(original)
        val capture = startCapture()
        val originalRetained = retainedReference(capture.uplink)!!
        assertNotSame("Capture owns a retained copy, not the producer's wrapper", original, originalRetained)
        assertFrame(111, capture.frame())

        val replacement = constantReference(222)
        source.set(replacement)
        assertFrame(222, capture.frame())
        val replacementRetained = retainedReference(capture.uplink)!!
        assertNotSame(originalRetained, replacementRetained)
        assertClosed(originalRetained)
        assertOpen(original)

        // An old output may finish a blocking write after its replacement has been published.
        appendConstant(original, 999)
        anchorNow(replacement)
        assertFrame(222, capture.frame())
        capture.assertSameRecorder()

        original.close()
        capture.close()
        assertClosed(replacementRetained)
        assertNull(retainedReference(capture.uplink))
        assertOpen(replacement)
    }

    @Test fun missingAndAlreadyClosedSourcesClearThePreviousFrameAndCanRecoverAgain() {
        val original = constantReference(111)
        source.set(original)
        val capture = startCapture()
        assertFrame(111, capture.frame())
        val firstRetained = retainedReference(capture.uplink)!!

        source.set(null)
        assertFrame(0, capture.frame())
        assertClosed(firstRetained)
        assertNull(retainedReference(capture.uplink))

        val alreadyClosed = constantReference(888).apply { close() }
        source.set(alreadyClosed)
        assertFrame(0, capture.frame())
        assertNull(retainedReference(capture.uplink))

        val recovered = constantReference(333)
        source.set(recovered)
        assertFrame(333, capture.frame())
        val recoveredRetained = retainedReference(capture.uplink)!!

        // A live but prebuffering replacement also clears, rather than reusing the last frame.
        source.set(reference())
        assertFrame(0, capture.frame())
        assertClosed(recoveredRetained)

        val next = constantReference(444)
        source.set(next)
        assertFrame(444, capture.frame())
        val lastRetained = retainedReference(capture.uplink)!!
        capture.assertSameRecorder()
        capture.close()
        assertClosed(lastRetained)
        assertOpen(next)
    }

    @Test fun incompatibleRateClearsTheAdapterAndFallsBackWithoutRestartingTheRecorder() {
        source.set(constantReference(111))
        val capture = startCapture()
        assertFrame(111, capture.frame())
        val previous = retainedReference(capture.uplink)!!
        source.set(constantReference(777, rate = SAMPLE_RATE / 2))
        val out = ShortArray(SAMPLE_RATE * 20 / 1_000) { 99 }
        assertFalse("A rate change requests the existing platform-AEC fallback",
            readEchoReference(capture.uplink, out, System.nanoTime()))
        assertFrame(0, out)
        assertClosed(previous)
        val incompatibleRetained = retainedReference(capture.uplink)!!

        capture.frameWithoutSoftwareAec()
        assertNull(field(capture.uplink, "echoCanceller"))
        capture.assertSameRecorder()
        capture.close()
        assertClosed(incompatibleRetained)
    }

    @Test fun producerClosePreservesItsAlreadyRetainedTailUntilTheSourceIsReplaced() {
        val producer = reference().apply { appendExact(this, 111, 222) }
        source.set(producer)
        val capture = startCapture()
        val tail = retainedReference(capture.uplink)!!
        producer.close()
        val out = ShortArray(2) { 99 }
        assertTrue(readEchoReference(capture.uplink, out, 0L))
        assertArrayEquals("Closing the producer must not invalidate the capture's retained tail",
            shortArrayOf(111, 222), out)
        assertOpen(tail)

        source.set(reference().apply { appendExact(this, 333, 444) })
        assertTrue(readEchoReference(capture.uplink, out, 0L))
        assertArrayEquals(shortArrayOf(333, 444), out)
        assertClosed(tail)
        val replacementRetained = retainedReference(capture.uplink)!!
        capture.close()
        assertClosed(replacementRetained)
    }

    @Test fun closeBetweenSourceSnapshotAndRetainCannotReviveAnOwnerOrReuseOldPcm() {
        source.set(constantReference(111))
        val capture = startCapture() // Its real AudioRecord worker is blocked before its first frame.
        val originalRetained = retainedReference(capture.uplink)!!
        val candidate = reference().apply { appendExact(this, 333, 444) }
        source.set(candidate)
        val fetched = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val out = ShortArray(2) { 99 }
        afterSourceSnapshot.set {
            fetched.countDown()
            check(resume.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Source snapshot gate timed out" }
        }
        val reader = Thread({
            try { readEchoReference(capture.uplink, out, 0L) }
            catch (failure: Throwable) { error.set(failure) }
            finally { finished.countDown() }
        }, "echo-source-close-replay")
        try {
            reader.start()
            assertTrue("Read must hold a snapshot before the producer closes",
                fetched.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            candidate.close()
            resume.countDown()
            assertTrue("A closed candidate must finish without a native lifetime failure",
                finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            error.get()?.let { throw AssertionError("Echo-reference read failed", it) }
            assertArrayEquals(shortArrayOf(0, 0), out)
            assertClosed(originalRetained)
            assertNull(retainedReference(capture.uplink))

            val replacement = reference().apply { appendExact(this, 555, 666) }
            source.set(replacement)
            out.fill(99)
            readEchoReference(capture.uplink, out, 0L)
            assertArrayEquals(shortArrayOf(555, 666), out)
            val replacementRetained = retainedReference(capture.uplink)!!
            capture.assertSameRecorder()
            capture.close()
            assertClosed(replacementRetained)
            assertOpen(replacement)

            // A late source/read after close may not allocate another retained native owner.
            source.set(reference().apply { appendExact(this, 777, 888) })
            out.fill(99)
            readEchoReference(capture.uplink, out, 0L)
            assertArrayEquals(shortArrayOf(0, 0), out)
            assertNull(retainedReference(capture.uplink))
        } finally {
            resume.countDown()
            reader.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            assertFalse("Source-close replay thread must finish", reader.isAlive)
        }
    }

    private fun startCapture(): Capture = Capture().also {
        captures += it
        it.start()
    }

    private inner class Capture : AutoCloseable {
        private val readPermits = LinkedBlockingQueue<Boolean>()
        private val readEntered = LinkedBlockingQueue<Unit>()
        private val references = LinkedBlockingQueue<ShortArray>()
        private val encodedFrames = LinkedBlockingQueue<Unit>()
        private val recorders = CopyOnWriteArrayList<AudioRecord>()
        private val cancellerCloses = AtomicInteger()
        private var closed = false
        private lateinit var originalRecorder: AudioRecord
        private lateinit var worker: Thread
        private val config = MicrophoneConfig(
            audioType = "telephony", sampleRate = SAMPLE_RATE, channels = 1, payloadType = 100,
            frameMillis = 20, host = InetAddress.getLoopbackAddress(), port = 9,
            key = ByteArray(32), codec = AudioCodecKind.OPUS,
        )
        val uplink = MicrophoneUplink(
            config,
            // Skip SDK effects: the observer, not Speex or a vendor AEC implementation, is under test.
            speakerphoneCall = true,
            echoReferenceSource = {
                val snapshot = source.get()
                afterSourceSnapshot.getAndSet(null)?.invoke()
                snapshot
            },
            echoCancellerFactory = { samples, rate, _ ->
                assertEquals(SAMPLE_RATE, rate)
                object : CallEchoCanceller {
                    override val frameSamples = samples
                    override fun process(frame: ByteArray, reference: ShortArray): Boolean {
                        references.add(reference.copyOf())
                        return true
                    }
                    override fun close() { cancellerCloses.incrementAndGet() }
                }
            },
            // Avoid native socket ownership and Opus/Speex libraries; capture still runs normally.
            nativeSenderFactory = { _, _ -> null },
            opusEncoderFactory = {
                object : MicrophoneOpusEncoder {
                    override val implementation = "echo-reference-observer"
                    override val available = true
                    override fun encode(pcm: ByteArray): List<ByteArray> {
                        encodedFrames.add(Unit)
                        return emptyList()
                    }
                    override fun close() = Unit
                }
            },
        )

        fun start() {
            ShadowAudioRecord.setSourceProvider { recorder ->
                recorders.addIfAbsent(recorder)
                object : ShadowAudioRecord.AudioRecordSource {
                    override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                        readEntered.add(Unit)
                        val permit = checkNotNull(readPermits.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            "Timed out waiting for a controlled AudioRecord read"
                        }
                        if (!permit) return AudioRecord.ERROR_DEAD_OBJECT
                        val count = minOf(config.frameBytes, size)
                        buffer.fill(1, offset, offset + count)
                        return count
                    }
                }
            }
            assertTrue("Real uplink startup must succeed", uplink.start())
            worker = field(uplink, "thread") as Thread
            awaitRead()
            originalRecorder = field(uplink, "recorder") as AudioRecord
            assertSameRecorder()
        }

        fun frame(): ShortArray {
            readPermits.add(true)
            val actual = checkNotNull(references.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "The active software-AEC frame did not reach the observer"
            }
            awaitEncodedFrame()
            awaitRead() // The frame has completed; source switching happens at the next read boundary.
            assertSameRecorder()
            return actual
        }

        fun frameWithoutSoftwareAec() {
            readPermits.add(true)
            awaitEncodedFrame()
            awaitRead()
            assertNull("An incompatible source must bypass the software canceller", references.poll())
            assertEquals("The old canceller is released for platform fallback", 1, cancellerCloses.get())
            assertSameRecorder()
        }

        private fun awaitEncodedFrame() {
            assertNotNull("Capture must continue to the sender/encoder boundary",
                encodedFrames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }

        fun assertSameRecorder() {
            assertSame("Echo-source recovery must keep the existing AudioRecord", originalRecorder, field(uplink, "recorder"))
            assertEquals("Only one SDK recorder may supply this capture", 1, recorders.size)
            assertSame(originalRecorder, recorders.single())
            assertEquals(AudioRecord.RECORDSTATE_RECORDING, originalRecorder.recordingState)
        }

        private fun awaitRead() {
            assertNotNull("Capture worker must reach the controlled read boundary",
                readEntered.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }

        override fun close() {
            if (closed) return
            closed = true
            readPermits.offer(false)
            uplink.close()
            if (::worker.isInitialized) {
                worker.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
                assertFalse("Capture worker must finish", worker.isAlive)
            }
            assertNull(field(uplink, "echoReference"))
            assertNull(field(uplink, "recorder"))
            if (::originalRecorder.isInitialized) assertEquals(AudioRecord.STATE_UNINITIALIZED, originalRecorder.state)
            assertEquals("The one software canceller is released once", 1, cancellerCloses.get())
        }
    }

    private fun reference(rate: Int = SAMPLE_RATE): EchoReference =
        EchoReference(rate, WINDOW_MILLIS).also(producers::add)

    private fun constantReference(value: Int, rate: Int = SAMPLE_RATE): EchoReference =
        reference(rate).also { appendConstant(it, value) }

    private fun appendConstant(reference: EchoReference, value: Int) {
        val frames = reference.sampleRate * WINDOW_MILLIS / 1_000
        val pcm = ByteArray(frames * 2)
        for (at in 0 until frames) {
            pcm[at * 2] = value.toByte()
            pcm[at * 2 + 1] = (value shr 8).toByte()
        }
        reference.append(pcm, 0, pcm.size, 1, (frames / 2).toLong(), System.nanoTime())
    }

    private fun anchorNow(reference: EchoReference) {
        reference.append(ByteArray(0), 0, 0, 1,
            reference.sampleRate.toLong() * WINDOW_MILLIS / 2_000, System.nanoTime())
    }

    private fun appendExact(reference: EchoReference, first: Int, second: Int) {
        val pcm = byteArrayOf(first.toByte(), (first shr 8).toByte(), second.toByte(), (second shr 8).toByte())
        reference.append(pcm, 0, pcm.size, 1, 0L, 0L)
    }

    private fun assertFrame(value: Int, actual: ShortArray) {
        assertEquals(SAMPLE_RATE * 20 / 1_000, actual.size)
        assertArrayEquals("AEC must see only the current source, or explicit silence", ShortArray(actual.size) { value.toShort() }, actual)
    }

    private fun assertClosed(reference: EchoReference) {
        assertEquals("Retired capture wrapper must release its native handle", 0L, field(reference, "handle"))
    }

    private fun assertOpen(reference: EchoReference) {
        assertNotEquals("Capture teardown must not close a producer's independent owner", 0L, field(reference, "handle"))
    }

    private fun retainedReference(uplink: MicrophoneUplink): EchoReference? = field(uplink, "echoReference") as EchoReference?

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)

    private fun readEchoReference(uplink: MicrophoneUplink, out: ShortArray, endNs: Long): Boolean =
        MicrophoneUplink::class.java.getDeclaredMethod("readEchoReference", ShortArray::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(uplink, out, endNs) as Boolean

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val WINDOW_MILLIS = 10_000
        const val TIMEOUT_SECONDS = 5L
    }
}
