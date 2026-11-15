package com.shihab.diplay.media

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.shihab.diplay.airplay.AudioCodecKind
import com.shihab.diplay.airplay.MicrophoneConfig
import com.shihab.diplay.airplay.NativeMedia
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import com.shihab.diplay.telecom.PhoneAudioRoute

/**
 * Captures one PCM microphone stream and sends it back to the phone as sealed CarPlay RTP.
 *
 * The recorder runs only while the matching audio stream is active, so callers start this after
 * the first downlink audio packet and close it on stream teardown.
 */
internal class MicrophoneUplink(
    private val config: MicrophoneConfig,
    private val onDiagnostic: (String) -> Unit = {},
    private val speakerphoneCall: Boolean = false,
    /** Current output owner; queried without sink locks so a recovered renderer can replace its ring. */
    private val echoReferenceSource: () -> EchoReference? = { null },
    private val nativeSenderFactory: (MicrophoneConfig) -> NativeUplinkSender? = NativeUplinkSender::create,
    private val noAudioRestartMillis: Long = NO_AUDIO_RESTART_MILLIS,
    private val onCaptureState: () -> Unit = {},
    /** Read for a report window that recorded no sound; see [silenceDetail]. */
    private val audioManager: AudioManager? = null,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val stats = MicrophoneCaptureStats(config, report = { message ->
        Log.i(TAG, message)
        onDiagnostic(message)
    }, onSnapshot = onCaptureState)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var nativeSender: NativeUplinkSender? = null
    @Volatile private var effects: List<AudioEffect> = emptyList()
    @Volatile private var softwareEcho = false
    private var thread: Thread? = null

    fun start(): Boolean {
        NativeMedia.requireAvailable()
        if (!running.compareAndSet(false, true)) return true

        val channelMask = if (config.channels >= 2) {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        } else {
            AndroidAudioFormat.CHANNEL_IN_MONO
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "microphone unavailable rate=${config.sampleRate} channels=${config.channels}")
            stats.failure(MicrophoneFailureStage.MIN_BUFFER, code = minBuffer)
            release()
            return false
        }

        val source = when {
            config.audioType == "telephony" -> telephonyMicrophoneSource(
                speakerphoneCall = speakerphoneCall,
                carPhoneState = PhoneAudioRoute.carPlayCallsUseCarAudio(),
                geelyHeadUnit = com.shihab.diplay.network.GeelyHeadUnit.detected,
            )
            // The Geely/Lynk head unit only opens the microphone for the voice-communication
            // source; its voice-recognition source records silence, so Siri never hears.
            config.audioType == "speechrecognition" ->
                if (com.shihab.diplay.network.GeelyHeadUnit.detected) {
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                } else {
                    MediaRecorder.AudioSource.VOICE_RECOGNITION
                }
            else -> MediaRecorder.AudioSource.MIC
        }
        stats.selectedSource(source)
        val bufferSize = maxOf(minBuffer * 2, config.frameBytes * 4)
        // Some head units throw on VOICE_RECOGNITION at build(); VOICE_COMMUNICATION works there.
        val nextRecorder = createRecorder(source, channelMask, bufferSize)
            ?: if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
                stats.selectedSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                createRecorder(MediaRecorder.AudioSource.VOICE_COMMUNICATION, channelMask, bufferSize)
            } else {
                null
            }
        if (nextRecorder == null) {
            release()
            return false
        }

        recorder = nextRecorder
        val nextSender = try {
            nativeSenderFactory(config)?.also { nativeSender = it } ?: run {
                Log.w(TAG, "microphone native pipeline is unavailable")
                stats.failure(MicrophoneFailureStage.NATIVE_PIPELINE)
                release()
                return false
            }
        } catch (error: Throwable) {
            runCatching { release() }.onFailure { error.addSuppressed(it) }
            throw error
        }
        val implementation = if (config.codec == AudioCodecKind.OPUS) "native encoder=libopus" else "native"
        val message = "Microphone: sender type=${config.audioType} codec=${config.codec} implementation=$implementation"
        Log.i(TAG, message)
        runCatching { onDiagnostic(message) }
        return try {
            if (config.audioType == "telephony") {
                softwareEcho = nextSender.enableEcho(echoReferenceSource())
                if (!speakerphoneCall) effects = voiceEffects(nextRecorder.audioSessionId)
                Log.i(TAG, "microphone echo canceller enabled=$softwareEcho tail=${ECHO_TAIL_MILLIS}ms")
            }
            nextRecorder.startRecording()
            stats.started(routeType(nextRecorder))
            thread = Thread({ capture(nextRecorder, nextSender) }, "carplay-mic").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            Log.e(TAG, "microphone recording failed", error)
            stats.failure(MicrophoneFailureStage.RECORDING, error)
            release()
            false
        }
    }

    private fun createRecorder(source: Int, channelMask: Int, bufferSize: Int): AudioRecord? {
        stats.selectedSource(source)
        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AndroidAudioFormat.Builder()
                        .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(config.sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (error: Exception) {
            Log.e(TAG, "microphone recorder creation failed source=$source", error)
            stats.failure(MicrophoneFailureStage.RECORDER_CREATION, error)
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "microphone recorder failed to initialize source=$source")
            stats.failure(MicrophoneFailureStage.RECORDER_INITIALIZATION, code = recorder.state)
            recorder.release()
            return null
        }
        stats.recorderSource(runCatching { recorder.audioSource }.getOrNull())
        return recorder
    }

    // Called by the native worker at every completed frame, without the release monitor.
    private fun captureRunning(): Boolean = running.get()
    private fun captureEchoSource(): EchoReference? = if (running.get()) echoReferenceSource() else null

    private fun voiceEffects(sessionId: Int): List<AudioEffect> {
        if (PhoneAudioRoute.carPlayCallsUseCarAudio()) {
            // In the car's phone audio state its own processing handles echo and noise, and was only measured
            // with the platform effects off.
            Log.i(TAG, "microphone effects left to the car's phone audio state")
            return emptyList()
        }
        var aec: AudioEffect? = null
        if (softwareEcho) {
            // VOICE_COMMUNICATION can enable AEC by default. Keep its controller alive and
            // explicitly disable it while Speex runs, rather than stacking two cancellers.
            val platformAvailable = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(true)
            if (platformAvailable) {
                aec = configuredEffect("AEC", false) { AcousticEchoCanceler.create(sessionId) }
                if (aec == null) {
                    nativeSender?.disableEcho()
                    softwareEcho = false
                    Log.w(TAG, "microphone native echo canceller skipped: platform AEC could not be disabled")
                }
            }
        }
        if (!softwareEcho) {
            aec = enabledEffect("AEC") {
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId) else null
            }
        }
        val ns = if (softwareEcho) {
            // Noise suppression before the canceller distorts the echo it has to model; Speex
            // denoises after cancelling instead. Keep the controller so a fallback can re-enable it.
            configuredEffect("NS", false) {
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId) else null
            }
        } else {
            enabledEffect("NS") {
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId) else null
            }
        }
        return listOfNotNull(aec, ns)
    }

    // Advertised effects may still fail to initialize on a vendor ROM. Keep recording without them.
    private fun enabledEffect(name: String, create: () -> AudioEffect?): AudioEffect? =
        configuredEffect(name, true, create)

    private fun configuredEffect(name: String, enabled: Boolean, create: () -> AudioEffect?): AudioEffect? {
        var effect: AudioEffect? = null
        try {
            effect = create()
            if (effect != null) {
                val status = effect.setEnabled(enabled)
                if (status == AudioEffect.SUCCESS && effect.enabled == enabled) {
                    Log.i(TAG, "microphone effect=$name enabled=$enabled")
                    return effect
                }
                Log.w(TAG, "microphone effect=$name could not be ${if (enabled) "enabled" else "disabled"} status=$status")
            } else {
                Log.i(TAG, "microphone effect=$name unavailable")
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "microphone effect=$name unavailable; continuing without it", error)
        }
        effect?.let(::releaseEffect)
        return null
    }

    private fun releaseEffect(effect: AudioEffect) {
        try {
            effect.release()
        } catch (error: RuntimeException) {
            Log.w(TAG, "microphone effect release failed", error)
        }
    }

    private fun capture(recorder: AudioRecord, sender: NativeUplinkSender) {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
        val readBuffer = ByteArray(maxOf(config.frameBytes, MIN_READ_BYTES))
        val routeInfo = { routeType(recorder) }
        val ownSession = recorder.audioSessionId
        val silence = audioManager?.let { manager -> { silenceDetail(manager, ownSession) } }
        val timestamp = android.media.AudioTimestamp()
        val recovery = longArrayOf(0L, 0L, System.nanoTime())
        try {
            while (running.get()) {
                stats.reading()
                val count = recorder.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                stats.read(count)
                val now = if (count > 0) System.nanoTime() else 0L
                val stamped = if (count > 0 && softwareEcho) runCatching {
                    recorder.getTimestamp(timestamp, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                }.getOrDefault(false) else false
                if (count < 0) {
                    if (running.get()) {
                        Log.e(TAG, "microphone read failed code=$count")
                        stats.failure(MicrophoneFailureStage.READ, code = count)
                    }
                    // The first read after a head unit enters call mode can fail with
                    // ERROR_INVALID_OPERATION, and capture used to end there for the whole call
                    // (#647). Restart recording a few times before giving up.
                    if (running.get() && count != AudioRecord.ERROR_DEAD_OBJECT &&
                        NativeAudioScheduler.captureRecovery(recovery, READ_ERROR, 0L, 0L) > 0 && restartRecording(recorder)
                    ) {
                        val attempt = NativeAudioScheduler.captureRecovery(recovery, READ_RESTARTED, 0L, 0L)
                        stats.restarted(attempt, "read-error", routeType(recorder))
                        continue
                    }
                    return
                }
                if (count == 0) {
                    stats.flush(routeType = routeInfo, silence = silence)
                    // A recorder that starts with no input device routed returns 0 bytes at once, for
                    // as long as it runs: a Leapmotor T03 (Android 7.1) did this for minutes of Siri and
                    // call capture (#566). Restart it, and pause between empty reads instead of spinning.
                    val now = System.nanoTime()
                    val attempt = if (running.get()) NativeAudioScheduler.captureRecovery(recovery, NO_AUDIO, now, noAudioRestartMillis) else 0
                    if (attempt > 0) {
                        if (restartRecording(recorder)) {
                            stats.restarted(attempt, "no-audio", routeType(recorder))
                        } else {
                            stats.failure(MicrophoneFailureStage.RECORDING, code = recorder.recordingState)
                        }
                    } else {
                        Thread.sleep(EMPTY_READ_PAUSE_MILLIS)
                    }
                    continue
                }
                NativeAudioScheduler.captureRecovery(recovery, AUDIO_READ, System.nanoTime(), 0L)
                val result = sender.feed(readBuffer, count, now, stamped, timestamp.nanoTime, timestamp.framePosition, this, stats)
                if (result < -1 && running.get()) throw java.io.IOException("microphone send failed errno=${-result}")
                stats.flush(routeType = routeInfo, silence = silence)
            }
        } catch (error: Exception) {
            if (running.get()) {
                Log.e(TAG, "microphone capture failed", error)
                stats.failure(MicrophoneFailureStage.CAPTURE, error)
            }
        } finally {
            stats.flush(ended = true, routeType = routeInfo, silence = silence)
            running.set(false)
            release()
        }
    }

    private fun restartRecording(recorder: AudioRecord): Boolean = try {
        runCatching { recorder.stop() }
        Thread.sleep(READ_RESTART_PAUSE_MILLIS)
        recorder.startRecording()
        recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
    } catch (error: IllegalStateException) {
        false
    }

    private fun routeType(recorder: AudioRecord): Int? = runCatching { recorder.routedDevice?.type }.getOrNull()

    /**
     * The audio mode and the sources of the other recordings running now. A head unit that stays in
     * communication mode after a call, or another app holding the microphone, can each leave this one silent.
     * Other apps' recordings carry no package name for a normal app, only their source.
     */
    private fun silenceDetail(manager: AudioManager, ownSession: Int): String {
        val others = manager.activeRecordingConfigurations
            .filter { it.clientAudioSessionId != ownSession }
            .map { MicrophoneCaptureStats.sourceName(it.clientAudioSource) }
        return "audioMode=${manager.mode} otherRecordings=" +
            if (others.isEmpty()) "none" else others.take(MAX_LISTED_RECORDINGS).joinToString(",", "[", "]")
    }

    /** Capture can continue with the same recorder if the optional native processor stops working. */
    private fun restorePlatformEchoCancellation() {
        softwareEcho = false
        if (!running.get()) return // close/release already owns all recorder effects.
        effects.filterIsInstance<NoiseSuppressor>().forEach { ns ->
            runCatching { ns.setEnabled(true) }
                .onFailure { Log.w(TAG, "microphone platform NS could not be restored", it) }
        }
        val aec = effects.filterIsInstance<AcousticEchoCanceler>().firstOrNull()
        if (aec != null) {
            val enabled = runCatching { aec.setEnabled(true) == AudioEffect.SUCCESS && aec.enabled }.getOrDefault(false)
            if (enabled) {
                Log.i(TAG, "microphone native echo processing stopped; platform AEC restored")
                return
            }
            effects = effects - aec
            releaseEffect(aec)
        }
        enabledEffect("AEC") {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(recorder!!.audioSessionId) else null
        }?.let { effects = effects + it }
        Log.i(TAG, "microphone native echo processing stopped; using platform effects")
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            release()
            return
        }
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Best effort; release below is authoritative.
        }
        thread?.let { worker ->
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        }
        release()
    }

    @Synchronized
    private fun release() {
        running.set(false)
        val currentSender = nativeSender
        nativeSender = null
        currentSender?.close()
        softwareEcho = false
        val currentEffects = effects
        effects = emptyList()
        currentEffects.forEach(::releaseEffect)
        val currentRecorder = recorder
        recorder = null
        try {
            currentRecorder?.release()
        } catch (_: Exception) {
            // Best effort.
        }
    }

    private companion object {
        const val TAG = "diplay-usb"
        const val AUDIO_READ = 0
        const val READ_ERROR = 1
        const val READ_RESTARTED = 2
        const val NO_AUDIO = 3
        const val MIN_READ_BYTES = 2_048
        const val CLOSE_JOIN_MILLIS = 500L
        const val ECHO_TAIL_MILLIS = 250
        const val READ_RESTART_PAUSE_MILLIS = 50L
        const val NO_AUDIO_RESTART_MILLIS = 1_000L
        const val EMPTY_READ_PAUSE_MILLIS = 5L
        const val MAX_LISTED_RECORDINGS = 4
    }
}

/**
 * The recorder source for a CarPlay call. While DiPlay holds the car's phone audio state the car's call
 * processing is on for every source, but VOICE_COMMUNICATION then clips (about a fifth of the samples on a
 * Han) and the plain microphone source is muted. VOICE_RECOGNITION, which is also what the stock CarPlay
 * service ends up with, gives a normal level with no clipping, and the car still suppresses the far end's
 * echo on it. Geely/Lynk head units record silence on VOICE_RECOGNITION, so they keep the old choice.
 */
internal fun telephonyMicrophoneSource(speakerphoneCall: Boolean, carPhoneState: Boolean, geelyHeadUnit: Boolean): Int = when {
    carPhoneState && !geelyHeadUnit -> MediaRecorder.AudioSource.VOICE_RECOGNITION
    speakerphoneCall -> MediaRecorder.AudioSource.MIC
    else -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
}

/**
 * When each captured microphone sample was heard, on System.nanoTime's clock. Uses the recorder's
 * timestamp when the platform has one, otherwise uses the core's calibrated 20 ms fallback latency.
 */
internal class CaptureClock(private val sampleRate: Int) {
    init { require(sampleRate > 0) }
    private val timestamp = android.media.AudioTimestamp()
    private val owner = LongArray(3)

    fun read(recorder: AudioRecord, samples: Int) {
        val now = System.nanoTime()
        val stamped = runCatching {
            recorder.getTimestamp(timestamp, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
        }.getOrDefault(false)
        read(samples, now, stamped, timestamp.nanoTime, timestamp.framePosition)
    }

    /** Platform reads stay outside the core; tests can replay the same timestamp facts. */
    internal fun read(samples: Int, nowNs: Long, stamped: Boolean, timestampNs: Long, framePosition: Long) =
        NativeEchoReference.captureRead(owner, sampleRate, samples, nowNs, stamped, timestampNs, framePosition)

    /** The capture time of the end of the first [samplesIntoRead] samples of the latest read. */
    fun frameEndNs(samplesIntoRead: Int): Long =
        NativeEchoReference.captureEnd(owner, sampleRate, samplesIntoRead)
}
