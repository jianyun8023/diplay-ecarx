package com.shilapi.xcertplay.media

import android.Manifest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.ecarx.sdk.policy.PolicyAPI
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import org.robolectric.shadows.ShadowAudioEffect
import org.robolectric.shadows.ShadowAudioRecord

/** Real sink/uplink and policy reflection: P1 changes routing, not source, packets or call effects. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE,
    shadows = [SiriCommunicationCaptureTest.CaptureAudioManager::class,
        MicrophoneSourceFallbackTest.RecorderBuilder::class,
        MicrophoneSourceFallbackTest.GeelyDetection::class],
    instrumentedPackages = ["com.shilapi.xcertplay.network"])
class SiriCommunicationCaptureTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(AudioManager::class.java)
    private val policy get() = PolicyAPI.audio
    private val siri = AudioStreamId(100, "speechrecognition")
    private val reports = CopyOnWriteArrayList<String>()
    private val captureStarted = CountDownLatch(1)
    private var sink: AndroidMediaSink? = null

    @Before fun setUp() {
        policy.reset()
        policy.needed = true
        CaptureAudioManager.rejectMode = null
        CaptureAudioManager.rejectParameter = null
        CaptureAudioManager.rejectRecordings = false
        MicrophoneSourceFallbackTest.RecorderBuilder.attempts.clear()
        MicrophoneSourceFallbackTest.RecorderBuilder.built.clear()
        MicrophoneSourceFallbackTest.RecorderBuilder.rejectSources = emptySet()
        MicrophoneSourceFallbackTest.RecorderBuilder.uninitializedSources = emptySet()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS)
        ShadowAudioModeRequests.clearRequests()
        ShadowAudioModeRequests.otherProcess(AudioManager.MODE_RINGTONE)
        policy.events.clear()
        ShadowAudioRecord.setSourceProvider {
            policy.events.add("recorder")
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
        CaptureAudioManager.rejectMode = null
        sink?.close()
        ShadowAudioRecord.clearSource()
        policy.reset()
    }

    @Test fun baselineKeepsSiriOutOfCommunicationModeButReportsItsEnvironment() {
        start(experiment = false)
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
        assertEquals(0, policy.requests)
        assertTrue(policy.events.none { it.startsWith("parameter:") })
        assertTrue(reports.any { it.contains("phase=capture mode=1 microphoneMute=false") &&
            it.contains("siriCommunicationCapture=false") })
        assertTrue(reports.any { it.contains("actualSource=VOICE_COMMUNICATION") })
    }

    @Test fun experimentOpensAllThreeCallParametersBeforeCaptureAndReleasesOnStop() {
        start()
        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertEquals(2, policy.requestType)
        assertEquals(1, policy.requests)
        assertEquals(listOf("mode:3", "parameter:hicar_enable=true", "parameter:AudioControlMode=01121001",
            "parameter:ASR_AEC=ON", "requestMic", "recorder"), policy.events.toList())
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION), MicrophoneSourceFallbackTest.RecorderBuilder.attempts)
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
        assertTrue(reports.any { it.contains("phase=before-start mode=1") })
        assertTrue(reports.any { it.contains("phase=capture mode=3") })
        assertTrue(reports.any { it.contains("isNeedRequestMic=true requestType=CALL(2)") })
        assertTrue(reports.any { it.contains("onResume") && it.contains("granted=true") })

        sink!!.onMicrophoneStopped(siri)
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
        assertEquals(1, policy.releases)
        assertNull(policy.held)
        assertTrue(policy.events.containsAll(listOf("parameter:hicar_enable=false",
            "parameter:AudioControlMode=01121000", "parameter:ASR_AEC=OFF")))
        assertTrue(reports.any { it.contains("mode restored requested=0 now=1") })
        assertTrue(reports.any { it.contains("mic released held=true ok=true") })
        assertTrue(reports.any { it.contains("phase=stopped mode=1") })
        ShadowAudioModeRequests.otherProcess(AudioManager.MODE_NORMAL)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        sink!!.close()
        assertEquals(1, policy.releases)
    }

    @Test fun callCaptureStillUsesTheSameCommunicationPathWithTheExperimentOff() {
        val call = AudioStreamId(101, "telephony")
        start(experiment = false, id = call)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertEquals(2, policy.requestType)
        assertEquals(1, policy.requests)
        sink!!.onMicrophoneStopped(call)
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
        assertEquals(1, policy.releases)
    }

    @Test fun recorderCreationFailureRestoresModeAndReleasesTheGrant() {
        MicrophoneSourceFallbackTest.RecorderBuilder.rejectSources = setOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        start()
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
        assertEquals(1, policy.releases)
        assertNull(policy.held)
        assertTrue(reports.any { it.contains("stage=RECORDER_CREATION") })
    }

    @Test fun deniedAndAbortedRequestsStillStartDirectCapture() {
        policy.reject = true
        start()
        assertTrue(reports.any { it.startsWith("Microphone: start") })
        assertTrue(reports.any { it.contains("accepted=false granted=false") })
        sink!!.onMicrophoneStopped(siri)
        assertEquals(0, policy.releases)
        sink!!.close()

        policy.reject = false
        policy.abort = true
        start()
        assertTrue(reports.any { it.contains("onAbort") && it.contains("granted=false reason=7") })
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, MicrophoneSourceFallbackTest.RecorderBuilder.built.last().recordingState)
        sink!!.onMicrophoneStopped(siri)
        assertEquals(1, policy.releases)
    }

    @Test fun unavailableArbitrationDoesNotBlockCapture() {
        policy.needed = false
        start()
        assertEquals(0, policy.requests)
        assertTrue(reports.any { it.startsWith("Microphone: start") })
        sink!!.onMicrophoneStopped(siri)
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
        assertTrue(reports.any { it.contains("mic released held=false ok=true") })
    }

    @Test fun modeEntryFailureFallsBackToDirectRecording() {
        CaptureAudioManager.rejectMode = AudioManager.MODE_IN_COMMUNICATION
        start()
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
        assertEquals(0, policy.requests)
        assertTrue(reports.any { it.contains("Siri communication capture failed") })
        assertTrue(reports.any { it.startsWith("Microphone: start") })
    }

    @Test fun vendorParameterFailureDoesNotPreventDirectCaptureOrResourceRelease() {
        CaptureAudioManager.rejectParameter = "ASR_AEC=ON"
        start()
        assertTrue(reports.any { it.contains("ASR_AEC=ON writeOk=false") })
        assertTrue(reports.any { it.startsWith("Microphone: start") })
        sink!!.onMicrophoneStopped(siri)
        assertEquals(1, policy.releases)
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
    }

    @Test fun inaccessibleRecordingClientsAreUnknownRatherThanReportedAbsent() {
        CaptureAudioManager.rejectRecordings = true
        start()
        assertTrue(reports.any { it.contains("activeRecordings=unknown") })
        assertTrue(reports.any { it.startsWith("Microphone: start") })
    }

    @Test fun failedModeRestoreStillClosesVendorParametersAndReleasesTheMic() {
        start()
        CaptureAudioManager.rejectMode = AudioManager.MODE_NORMAL
        sink!!.onMicrophoneStopped(siri)
        assertEquals(1, policy.releases)
        assertTrue(policy.events.contains("parameter:ASR_AEC=OFF"))
        assertTrue(reports.any { it.contains("mode restore failed") })
        assertTrue(reports.any { it.contains("mic released held=true ok=true") })
    }

    @Test fun declinedReleaseIsNotReportedAsSuccessful() {
        policy.declineRelease = true
        start()
        sink!!.onMicrophoneStopped(siri)
        assertTrue(reports.any { it.contains("mic released held=true ok=false") })
    }

    @Test fun tenSessionsDoNotLeakModeOrMicAndStoppingAnotherStreamDoesNotReleaseIt() {
        sink = AndroidMediaSink(context = context, onAudioDiagnostic = reports::add, siriCommunicationCapture = true)
        repeat(10) {
            sink!!.onMicrophoneStarted(siri, config(siri))
            sink!!.onMicrophoneStopped(AudioStreamId(999, "other"))
            assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
            assertEquals(it, policy.releases)
            sink!!.onMicrophoneStopped(siri)
            assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
            assertEquals(it + 1, policy.releases)
            assertNull(policy.held)
        }
        assertEquals(10, policy.requests)
    }

    @Test fun sinkCloseReleasesAnActiveCaptureEvenWhenDiagnosticsThrow() {
        sink = AndroidMediaSink(context = context, siriCommunicationCapture = true,
            onAudioDiagnostic = { throw IllegalStateException("diagnostic consumer failed") })
        sink!!.onMicrophoneStarted(siri, config(siri))
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        sink!!.close()
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
        assertEquals(1, policy.releases)
    }

    @Test fun recordingClientsAndMuteStateAreReportedWithoutClaimingFullVisibility() {
        manager.isMicrophoneMute = true
        val shadow = shadowOf(manager)
        shadow.setActiveRecordingConfigurations(listOf(
            shadow.createActiveRecordingConfiguration(1, MediaRecorder.AudioSource.VOICE_COMMUNICATION, "com.shihab.diplay.hudtest"),
            shadow.createActiveRecordingConfiguration(2, MediaRecorder.AudioSource.MIC, "com.ecarx.assistant")), false)
        start()
        assertTrue(reports.any { it.contains("microphoneMute=true") && it.contains("activeRecordings=2") &&
            it.contains("visibility=anonymized-or-filtered") })
        assertTrue(reports.any { it.contains("package=com.ecarx.assistant source=1") })
    }

    private fun start(experiment: Boolean = true, id: AudioStreamId = siri) {
        sink = AndroidMediaSink(context = context, onAudioDiagnostic = reports::add, siriCommunicationCapture = experiment)
        sink!!.onMicrophoneStarted(id, config(id))
    }

    private fun config(id: AudioStreamId) = MicrophoneConfig(audioType = id.audioType,
        sampleRate = 16_000, channels = 1, payloadType = 100, frameMillis = 20,
        host = InetAddress.getLoopbackAddress(), port = 9, key = ByteArray(32))

    @Implements(AudioManager::class)
    class CaptureAudioManager : ShadowAudioModeRequests() {
        @Implementation override fun setMode(mode: Int) {
            if (mode == rejectMode) throw SecurityException("Test mode refusal")
            PolicyAPI.audio.events.add("mode:$mode")
            super.setMode(mode)
        }
        @Implementation override fun setParameters(parameters: String) {
            PolicyAPI.audio.events.add("parameter:$parameters")
            if (parameters == rejectParameter) throw SecurityException("Test vendor refusal")
            super.setParameters(parameters)
        }
        @Implementation override fun getActiveRecordingConfigurations(): List<android.media.AudioRecordingConfiguration> {
            if (rejectRecordings) throw SecurityException("Test visibility refusal")
            return super.getActiveRecordingConfigurations()
        }
        companion object {
            var rejectMode: Int? = null
            var rejectParameter: String? = null
            var rejectRecordings = false
        }
    }
}
