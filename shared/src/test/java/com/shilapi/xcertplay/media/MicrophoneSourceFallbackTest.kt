package com.shilapi.xcertplay.media

import android.Manifest
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.network.GeelyHeadUnit
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAudioEffect
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.util.ReflectionHelpers

/** Exercises the real sink/uplink, including creation, teardown and exported diagnostics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE, shadows = [MicrophoneSourceFallbackTest.RecorderBuilder::class, ShadowAudioModeRequests::class])
class MicrophoneSourceFallbackTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val id = AudioStreamId(100, "speechrecognition")
    private val diagnostics = CopyOnWriteArrayList<String>()
    private val captured = AtomicReference<AudioRecord?>()
    private val captureStarted = CountDownLatch(1)
    private lateinit var sink: AndroidMediaSink

    @Before fun setUp() {
        RecorderBuilder.attempts.clear()
        RecorderBuilder.built.clear()
        RecorderBuilder.rejectSources = emptySet()
        RecorderBuilder.uninitializedSources = emptySet()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS)
        ShadowAudioModeRequests.clearRequests()
        ShadowAudioModeRequests.otherProcess(AudioManager.MODE_RINGTONE)
        sink = AndroidMediaSink(context = context, onAudioDiagnostic = diagnostics::add, siriCommunicationCapture = false)
        ShadowAudioRecord.setSourceProvider { recorder ->
            captured.set(recorder)
            captureStarted.countDown()
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                    Thread.sleep(5)
                    return 0
                }
            }
        }
    }

    @After fun tearDown() {
        sink.close()
        ShadowAudioRecord.clearSource()
    }

    @Test fun siriCreationFailureRetriesCommunicationOnceWithoutCallEffectsOrModeChanges() {
        RecorderBuilder.rejectSources = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)

        sink.onMicrophoneStarted(id, config("speechrecognition"))

        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        val recorder = requireNotNull(captured.get())
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_COMMUNICATION), RecorderBuilder.attempts)
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, recorder.audioSource)
        assertTrue(diagnostics.any { it.contains("actualSource=VOICE_COMMUNICATION") })
        assertEquals(AudioManager.MODE_RINGTONE, context.getSystemService(AudioManager::class.java).mode)
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
        assertTrue(diagnostics.any { it.contains("failure type=speechrecognition source=VOICE_RECOGNITION") && it.contains("stage=RECORDER_CREATION") })
        assertTrue(diagnostics.any { it.startsWith("Microphone: start type=speechrecognition source=VOICE_COMMUNICATION") })
        sink.onMicrophoneStopped(id)
        assertEquals(AudioRecord.STATE_UNINITIALIZED, recorder.state)
        assertTrue(diagnostics.any { it.contains("stats type=speechrecognition source=VOICE_COMMUNICATION") && it.endsWith("ended=true") })
    }

    @Test
    @Config(shadows = [RecorderBuilder::class, GeelyDetection::class],
        instrumentedPackages = ["com.shilapi.xcertplay.network"])
    fun geelySiriReportsDirectCommunicationSourceWithoutChangingRouteOrEffects() {
        assertTrue(GeelyHeadUnit.detected)

        sink.onMicrophoneStarted(id, config("speechrecognition"))

        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION), RecorderBuilder.attempts)
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, requireNotNull(captured.get()).audioSource)
        val start = diagnostics.single { it.startsWith("Microphone: start") }
        assertTrue(start.contains("source=VOICE_COMMUNICATION"))
        assertTrue(start.contains("actualSource=VOICE_COMMUNICATION"))
        assertFalse(start.contains("VOICE_RECOGNITION"))
        assertEquals(AudioManager.MODE_RINGTONE, context.getSystemService(AudioManager::class.java).mode)
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
        sink.onMicrophoneStopped(id)
        assertTrue(diagnostics.any { it.contains("actualSource=VOICE_COMMUNICATION") && it.endsWith("ended=true") })
    }

    @Test
    @Config(shadows = [DifferentReportedSource::class])
    fun recorderReportedSourceIsNotReplacedByTheRequestedSource() {
        sink.onMicrophoneStarted(id, config("speechrecognition"))

        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION), RecorderBuilder.attempts)
        val recorder = requireNotNull(captured.get())
        assertEquals(MediaRecorder.AudioSource.MIC, recorder.audioSource)
        val start = diagnostics.single { it.startsWith("Microphone: start") }
        assertTrue(start.contains("source=VOICE_RECOGNITION"))
        assertTrue(start.contains("actualSource=MIC"))
        assertEquals(AudioManager.MODE_RINGTONE, context.getSystemService(AudioManager::class.java).mode)
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())

        sink.onMicrophoneStopped(id)
        assertEquals(AudioRecord.STATE_UNINITIALIZED, recorder.state)
        val ended = diagnostics.single { it.contains("stats type=speechrecognition") && it.endsWith("ended=true") }
        assertTrue(ended.contains("source=VOICE_RECOGNITION"))
        assertTrue(ended.contains("actualSource=MIC"))
    }

    @Test fun siriInitializationFailureReleasesRejectedRecorderBeforeFallbackCapture() {
        RecorderBuilder.uninitializedSources = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)

        sink.onMicrophoneStarted(id, config("speechrecognition"))

        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        assertEquals(2, RecorderBuilder.built.size)
        assertTrue((RecorderBuilder.built.first() as RejectedRecorder).released)
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, RecorderBuilder.built.last().recordingState)
        assertTrue(diagnostics.any { it.contains("source=VOICE_RECOGNITION") && it.contains("stage=RECORDER_INITIALIZATION") })
    }

    @Test fun bothUnsupportedSourcesFailOnceAndALaterStreamCanStart() {
        RecorderBuilder.rejectSources = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        sink.onMicrophoneStarted(id, config("speechrecognition"))
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_COMMUNICATION), RecorderBuilder.attempts)
        assertNull(captured.get())
        assertEquals(2, diagnostics.count { it.contains("stage=RECORDER_CREATION") })
        assertTrue(diagnostics.none { it.startsWith("Microphone: start") })

        RecorderBuilder.rejectSources = emptySet()
        sink.onMicrophoneStarted(id, config("speechrecognition"))
        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, requireNotNull(captured.get()).audioSource)
    }

    @Test fun telephonyCreationFailureDoesNotRetryOrLeaveTheCallModeActive() {
        RecorderBuilder.rejectSources = setOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        sink.onMicrophoneStarted(AudioStreamId(101, "telephony"), config("telephony"))
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION), RecorderBuilder.attempts)
        assertNull(captured.get())
        assertEquals(AudioManager.MODE_RINGTONE, context.getSystemService(AudioManager::class.java).mode)
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
    }

    private fun config(type: String) = MicrophoneConfig(audioType = type, sampleRate = 16_000,
        channels = 1, payloadType = 100, frameMillis = 20, host = InetAddress.getLoopbackAddress(),
        port = 9, key = ByteArray(32))

    /**
     * This fixture tests source selection/reporting, not Build-name detection. The detector caches
     * its result during object initialization, before a test method can change Build.PRODUCT.
     * Shadow that device boundary in this test's sandbox instead of changing production detection
     * or relying on class-initialization order. The real sink, recorder and uplink still run.
     */
    @Implements(value = GeelyHeadUnit::class, isInAndroidSdk = false)
    class GeelyDetection {
        @Implementation fun getDetected(): Boolean = true
    }

    /** Only this test changes recorder metadata; capture and the production uplink remain real. */
    @Implements(AudioRecord.Builder::class)
    class DifferentReportedSource {
        @RealObject private lateinit var builder: AudioRecord.Builder

        @Implementation fun build(): AudioRecord {
            val attributes = ReflectionHelpers.getField<AudioAttributes>(builder, "mAttributes")
            val source = ReflectionHelpers.callInstanceMethod<Int>(attributes, "getCapturePreset")
            RecorderBuilder.attempts += source
            return DifferentSourceRecorder(source).also { RecorderBuilder.built += it }
        }
    }

    class DifferentSourceRecorder(source: Int) : AudioRecord(source, 16_000,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 4096) {
        override fun getAudioSource(): Int = MediaRecorder.AudioSource.MIC
    }

    @Implements(AudioRecord.Builder::class)
    class RecorderBuilder {
        @RealObject private lateinit var builder: AudioRecord.Builder

        @Implementation fun build(): AudioRecord {
            val attributes = ReflectionHelpers.getField<AudioAttributes>(builder, "mAttributes")
            val source = ReflectionHelpers.callInstanceMethod<Int>(attributes, "getCapturePreset")
            attempts += source
            if (source in rejectSources) throw UnsupportedOperationException("Unsupported test capture source")
            return (if (source in uninitializedSources) RejectedRecorder(source)
                else Shadow.directlyOn<AudioRecord, AudioRecord.Builder>(builder, AudioRecord.Builder::class.java, "build")).also {
                built += it
            }
        }

        companion object {
            val attempts = CopyOnWriteArrayList<Int>()
            val built = CopyOnWriteArrayList<AudioRecord>()
            var rejectSources = emptySet<Int>()
            var uninitializedSources = emptySet<Int>()
        }
    }

    class RejectedRecorder(source: Int) : AudioRecord(source, 16_000,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 4096) {
        var released = false
            private set
        override fun getState(): Int = STATE_UNINITIALIZED
        override fun release() {
            released = true
            super.release()
        }
    }
}
