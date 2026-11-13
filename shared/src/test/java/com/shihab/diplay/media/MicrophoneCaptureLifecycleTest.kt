package com.shihab.diplay.media

import android.Manifest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Looper
import com.ecarx.sdk.policy.PolicyAPI
import com.shihab.diplay.hud.EcarxMicPolicy
import com.shihab.diplay.airplay.AudioStreamId
import com.shihab.diplay.airplay.MicrophoneConfig
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioEffect
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.util.ReflectionHelpers

/** Siri keeps direct capture; calls acquire and release the communication path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE,
    shadows = [MicrophoneCaptureLifecycleTest.CaptureAudioManager::class,
        MicrophoneSourceFallbackTest.RecorderBuilder::class,
        MicrophoneSourceFallbackTest.GeelyDetection::class],
    instrumentedPackages = ["com.shihab.diplay.network"])
class MicrophoneCaptureLifecycleTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(AudioManager::class.java)
    private val policy get() = PolicyAPI.audio
    private val siri = AudioStreamId(100, "speechrecognition")
    private val call = AudioStreamId(101, "telephony")
    private val reports = CopyOnWriteArrayList<String>()
    private val captureStarted = CountDownLatch(1)
    private var sink: AndroidMediaSink? = null

    @Before fun setUp() {
        policy.reset()
        ReflectionHelpers.setStaticField(EcarxMicPolicy::class.java, "policyApi", null)
        ReflectionHelpers.setStaticField(EcarxMicPolicy::class.java, "ready", null)
        PolicyAPI.enabled = true
        CaptureAudioManager.rejectMode = null
        CaptureAudioManager.rejectParameter = null
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
        shadowOf(Looper.getMainLooper()).idle()
        ShadowAudioRecord.clearSource()
        policy.reset()
    }

    @Test fun baselineKeepsSiriOutOfCommunicationModeButReportsItsEnvironment() {
        start(id = siri)
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
        assertEquals(0, policy.requests)
        assertTrue(policy.events.none { it.startsWith("parameter:") })
        assertTrue(reports.any { it.contains("phase=capture mode=1 microphoneMute=false") })
        assertTrue(reports.any { it.contains("actualSource=VOICE_COMMUNICATION") })
    }

    @Test fun callOpensVendorParametersBeforeCaptureAndReleasesOnStop() {
        start()
        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertEquals(2, policy.requestType)
        assertEquals(1, policy.requests)
        assertEquals(listOf("init", "getAudioPolicy", "requestMic", "mode:3", "parameter:hicar_enable=true",
            "parameter:AudioControlMode=01121001", "parameter:ASR_AEC=ON", "recorder"), policy.events.toList())
        assertEquals(1, PolicyAPI.initCalls)
        assertEquals(1, PolicyAPI.audioLookups)
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION), MicrophoneSourceFallbackTest.RecorderBuilder.attempts)
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
        assertTrue(reports.any { it.contains("phase=before-start mode=1") })
        assertTrue(reports.any { it.contains("phase=capture mode=3") })
        assertTrue(reports.any { it.contains("policyAvailable=true requestType=CALL(2)") })
        assertTrue(reports.any { it.contains("onResume") && it.contains("granted=true") })

        stop()
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

    @Test fun recorderCreationFailureRestoresModeAndReleasesTheGrant() {
        MicrophoneSourceFallbackTest.RecorderBuilder.rejectSources = setOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        start()
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
        assertEquals(1, policy.releases)
        assertNull(policy.held)
        assertTrue(reports.any { it.contains("stage=RECORDER_CREATION") })
    }

    @Test fun declinedRequestsFallBackButAbortedGrantsWaitForResume() {
        policy.reject = true
        start()
        assertTrue(reports.any { it.startsWith("Microphone: start") })
        assertTrue(reports.any { it.contains("accepted=false granted=false") })
        stop()
        assertEquals(0, policy.releases)
        sink!!.close()

        policy.reject = false
        policy.abort = true
        start()
        assertTrue(reports.any { it.contains("onAbort requestType=2 granted=false") })
        assertEquals(AudioRecord.RECORDSTATE_STOPPED, MicrophoneSourceFallbackTest.RecorderBuilder.built.last().recordingState)
        policy.held!!.onResume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, MicrophoneSourceFallbackTest.RecorderBuilder.built.last().recordingState)
        stop()
        assertEquals(1, policy.releases)
    }

    @Test fun unavailableArbitrationDoesNotBlockCapture() {
        PolicyAPI.enabled = false
        start()
        assertEquals(0, policy.requests)
        assertTrue(reports.any { it.startsWith("Microphone: start") })
        stop()
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
        assertTrue(reports.any { it.contains("mic released held=false ok=true") })
    }

    @Test fun vendorParameterFailureDoesNotPreventDirectCaptureOrResourceRelease() {
        CaptureAudioManager.rejectParameter = "ASR_AEC=ON"
        start()
        assertTrue(reports.any { it.contains("ASR_AEC=ON failed=SecurityException") })
        assertTrue(reports.any { it.startsWith("Microphone: start") })
        stop()
        assertEquals(1, policy.releases)
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
    }

    @Test fun failedModeRestoreStillClosesVendorParametersAndReleasesTheMic() {
        start()
        CaptureAudioManager.rejectMode = AudioManager.MODE_NORMAL
        stop()
        assertEquals(1, policy.releases)
        assertTrue(policy.events.contains("parameter:ASR_AEC=OFF"))
        assertTrue(reports.any { it.contains("mode restore failed") })
        assertTrue(reports.any { it.contains("mic released held=true ok=true") })
    }

    @Test fun declinedReleaseIsNotReportedAsSuccessful() {
        policy.declineRelease = true
        start()
        stop()
        assertTrue(reports.any { it.contains("mic released held=true ok=false") })
    }

    @Test fun tenSessionsDoNotLeakModeOrMicAndStoppingAnotherStreamDoesNotReleaseIt() {
        sink = AndroidMediaSink(context = context, onAudioDiagnostic = reports::add)
        repeat(10) {
            sink!!.onMicrophoneStarted(call, config(call))
            shadowOf(Looper.getMainLooper()).idle()
            sink!!.onMicrophoneStopped(AudioStreamId(999, "other"))
            assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
            assertEquals(it, policy.releases)
            stop()
            assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
            assertEquals(it + 1, policy.releases)
            assertNull(policy.held)
        }
        assertEquals(10, policy.requests)
        assertEquals(1, PolicyAPI.initCalls)
    }

    @Test fun sinkCloseReleasesAnActiveCaptureEvenWhenDiagnosticsThrow() {
        sink = AndroidMediaSink(context = context,
            onAudioDiagnostic = { throw IllegalStateException("diagnostic consumer failed") })
        sink!!.onMicrophoneStarted(call, config(call))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        sink!!.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
        assertEquals(1, policy.releases)
    }

    @Test fun microphoneMuteStateIsReported() {
        manager.isMicrophoneMute = true
        start(id = siri)
        assertTrue(reports.any { it.contains("phase=capture") && it.contains("microphoneMute=true") })
    }

    private fun start(id: AudioStreamId = call) {
        sink = AndroidMediaSink(context = context, onAudioDiagnostic = reports::add)
        sink!!.onMicrophoneStarted(id, config(id))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun stop() {
        sink!!.onMicrophoneStopped(call)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun nonNullPolicyWaitsForInitializationAndAnActualGrant() {
        PolicyAPI.autoReady = false
        policy.deferGrant = true
        start()
        assertEquals(1, PolicyAPI.initCalls)
        assertEquals(0, PolicyAPI.audioLookups)
        assertEquals(0, policy.requests)
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
        assertTrue(policy.events.none { it.startsWith("parameter:") })
        assertTrue(MicrophoneSourceFallbackTest.RecorderBuilder.built.isEmpty())
        PolicyAPI.deliverReady(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, policy.requests)
        assertTrue(MicrophoneSourceFallbackTest.RecorderBuilder.built.isEmpty())
        policy.held!!.onResume()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        stop()
        assertEquals(1, policy.releases)
    }

    @Test fun revokedGrantStopsCaptureAndResumeRestartsIt() {
        start()
        val callback = checkNotNull(policy.held)
        val recorder = MicrophoneSourceFallbackTest.RecorderBuilder.built.last()
        callback.onAbort(2)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AudioRecord.RECORDSTATE_STOPPED, recorder.recordingState)
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
        assertTrue(policy.events.contains("parameter:hicar_enable=false"))
        assertEquals(0, policy.releases)
        callback.onResume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, MicrophoneSourceFallbackTest.RecorderBuilder.built.last().recordingState)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        stop()
        assertEquals(1, policy.releases)
    }

    @Test fun stopBeforeApiReadinessDoesNotRequestOrStartCapture() {
        PolicyAPI.autoReady = false
        start()
        stop()
        PolicyAPI.deliverReady(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, policy.requests)
        assertTrue(MicrophoneSourceFallbackTest.RecorderBuilder.built.isEmpty())
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
    }

    @Test fun lateGrantAfterCloseCannotResurrectCaptureOrReleaseTwice() {
        policy.deferGrant = true
        start()
        val callback = checkNotNull(policy.held)
        sink!!.close()
        callback.onResume()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(MicrophoneSourceFallbackTest.RecorderBuilder.built.isEmpty())
        assertEquals(1, policy.releases)
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
        sink!!.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, policy.releases)
    }

    @Test fun unsupportedTargetServerKeepsDirectCaptureAfterReady() {
        policy.unsupported = true
        start()
        assertEquals(1, policy.requests)
        assertNull(policy.held)
        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        assertTrue(reports.any { it.contains("fallback reason=request declined or unsupported") })
        stop()
        assertEquals(0, policy.releases)
    }

    @Test fun initializationTimeoutFallsBackAndLateReadinessDoesNotAcquireMic() {
        PolicyAPI.autoReady = false
        start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        PolicyAPI.deliverReady(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, policy.requests)
        assertTrue(reports.any { it.contains("fallback reason=API/grant timeout") })
        stop()
    }

    @Test fun grantTimeoutReleasesAcceptedRequestBeforeDirectCapture() {
        policy.deferGrant = true
        start()
        val callback = checkNotNull(policy.held)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        assertEquals(1, policy.releases)
        assertTrue(policy.events.indexOf("releaseMic") < policy.events.indexOf("recorder"))
        callback.onResume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, MicrophoneSourceFallbackTest.RecorderBuilder.built.size)
        stop()
        assertEquals(1, policy.releases)
    }

    @Test fun initFailureOrNotReadyFallsBackWithoutFetchingAnAudioHandle() {
        PolicyAPI.failInit = true
        start()
        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        assertEquals(0, PolicyAPI.audioLookups)
        stop()
        sink!!.close()
        PolicyAPI.failInit = false
        PolicyAPI.readyResult = false
        start()
        assertEquals(0, PolicyAPI.audioLookups)
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, MicrophoneSourceFallbackTest.RecorderBuilder.built.last().recordingState)
        stop()
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
        companion object {
            var rejectMode: Int? = null
            var rejectParameter: String? = null
        }
    }
}
