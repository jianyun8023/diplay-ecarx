package com.shihab.diplay.media

import android.media.MediaRecorder
import com.shihab.diplay.airplay.NativeMedia
import com.shihab.diplay.airplay.MicrophoneConfig

internal enum class MicrophoneFailureStage {
    MIN_BUFFER, NATIVE_PIPELINE, ENCODER, RECORDER_CREATION, RECORDER_INITIALIZATION, SOCKET_CREATION,
    RECORDING, READ, CAPTURE, START,
}

/** Capture-thread counters only. Never retains PCM, encoded packets, keys or endpoint metadata. */
internal class MicrophoneCaptureStats(
    config: MicrophoneConfig,
    private val report: (String) -> Unit,
    private val onSnapshot: () -> Unit = {},
    private val nowNs: () -> Long = System::nanoTime,
) {
    private val type = audioType(config.audioType)
    private val format = format(config)
    private var metadata = metadata(config)
    internal val owner = NativeCaptureStats.create(nowNs())
    private var routedDeviceType: Int? = null

    fun started(routeType: Int?) {
        routedDeviceType = routeType
        emit("Microphone: start $metadata routedDeviceType=${routeType ?: "unknown"}")
        runCatching(onSnapshot)
    }

    /** Call before the capture thread starts; [metadata] is not volatile. */
    fun selectedSource(source: Int) {
        metadata = "type=$type source=${sourceName(source)} $format"
    }

    /** The recorder may disagree with the requested source; never infer this from the stream type. */
    fun recorderSource(source: Int?) {
        metadata += " actualSource=${sourceName(source)}"
    }

    fun reading() = NativeCaptureStats.event(owner, 0, 0, 0, nowNs())
    fun read(count: Int) = NativeCaptureStats.event(owner, 1, count.toLong(), 0, nowNs())
    fun level(pcm: ByteArray) = NativeCaptureStats.level(owner, pcm)
    fun encoded(frameCount: Int, emptyCount: Int) =
        NativeCaptureStats.event(owner, 2, frameCount.toLong(), emptyCount.toLong(), 0)
    fun sent() = NativeCaptureStats.event(owner, 3, 0, 0, nowNs())
    fun sendFailed() = NativeCaptureStats.event(owner, 4, 0, 0, 0)

    fun restarted(attempt: Int, reason: String, routeType: Int?) {
        emit("Microphone: recording restarted $metadata attempt=$attempt reason=$reason " +
            "routedDeviceType=${routeType ?: "unknown"}")
    }

    fun failure(stage: MicrophoneFailureStage, error: Throwable? = null, code: Int? = null) {
        emit(failureMessage(metadata, stage, error, code))
    }

    /**
     * [silence] is asked only for a window with no sound in it, for what else might explain it: a silent source
     * alone cannot tell a head unit that mutes it from another app holding the microphone (#987).
     */
    fun flush(ended: Boolean = false, routeType: (() -> Int?)? = null, silence: (() -> String)? = null) {
        val now = nowNs()
        val snapshot = NativeCaptureStats.snapshot(owner, now, ended) ?: return
        if (routeType != null) routedDeviceType = runCatching { routeType() }.getOrNull()
        val silenceDetail = if (snapshot[10] <= 0 && silence != null) runCatching { " " + silence() }.getOrDefault("") else ""
        emit("Microphone: stats $metadata routedDeviceType=${routedDeviceType ?: "unknown"} " +
            "captureBytes=${snapshot[0]} reads=${snapshot[1]} zeroReads=${snapshot[2]} readErrors=${snapshot[3]} " +
            "readMaxMs=${snapshot[4]} encodedFrames=${snapshot[5]} " +
            "emptyEncodedFrames=${snapshot[6]} udpSent=${snapshot[7]} sendErrors=${snapshot[8]} " +
            "sendGapMaxMs=${snapshot[9]} peakDbfs=${dbfs(snapshot[11])} " +
            "rmsDbfs=${dbfs(snapshot[12])} zeroSamplePct=${if (snapshot[13] < 0) "unknown" else snapshot[13].toString()} " +
            "clippedSamples=${snapshot[14]}$silenceDetail ended=$ended")
        runCatching(onSnapshot)
        NativeCaptureStats.event(owner, 5, 0, 0, now)
    }

    private fun emit(message: String) { runCatching { report(message) } }

    companion object {
        /** Whole dBFS of a 16-bit peak; "silent" when every sample was zero. */
        internal fun peakDbfs(peak: Int): String =
            dbfs(NativeCaptureStats.peakDbfs(peak))

        private fun dbfs(value: Long): String = when (value) {
            Long.MIN_VALUE -> "unknown"
            Long.MIN_VALUE + 1 -> "silent"
            else -> value.toString()
        }

        private fun audioType(audioType: String): String = when (audioType) {
            "telephony", "speechrecognition" -> audioType
            else -> "other"
        }

        private val SOURCE_NAMES = mapOf(
            MediaRecorder.AudioSource.DEFAULT to "DEFAULT",
            MediaRecorder.AudioSource.MIC to "MIC",
            MediaRecorder.AudioSource.VOICE_UPLINK to "VOICE_UPLINK",
            MediaRecorder.AudioSource.VOICE_DOWNLINK to "VOICE_DOWNLINK",
            MediaRecorder.AudioSource.VOICE_CALL to "VOICE_CALL",
            MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
            MediaRecorder.AudioSource.REMOTE_SUBMIX to "REMOTE_SUBMIX",
            MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
            // Not public: the always-listening source of a car's own voice assistant.
            1999 to "HOTWORD",
        )

        internal fun sourceName(source: Int?): String = SOURCE_NAMES[source] ?: "unknown"

        private fun format(config: MicrophoneConfig): String = "codec=${config.codec.name} " +
            "rate=${config.sampleRate} channels=${config.channels} frameMs=${config.frameMillis}"

        private fun metadata(config: MicrophoneConfig): String {
            return "type=${audioType(config.audioType)} source=unknown ${format(config)}"
        }

        private fun failureMessage(
            metadata: String,
            stage: MicrophoneFailureStage,
            error: Throwable?,
            code: Int?,
        ): String = "Microphone: failure $metadata stage=${stage.name} " +
            "error=${error?.javaClass?.simpleName?.replace(Regex("[^A-Za-z0-9_]"), "")?.take(64) ?: "none"} " +
            "code=${code ?: "none"}"

        fun reportStartFailure(config: MicrophoneConfig, error: Throwable, report: (String) -> Unit) {
            runCatching { report(failureMessage(metadata(config), MicrophoneFailureStage.START, error, null)) }
        }
    }
}

internal object NativeCaptureStats {
    init { NativeMedia.requireAvailable() }
    @JvmStatic external fun create(now: Long): LongArray
    @JvmStatic external fun event(owner: LongArray, event: Int, value: Long, extra: Long, now: Long)
    @JvmStatic external fun level(owner: LongArray, pcm: ByteArray)
    @JvmStatic external fun snapshot(owner: LongArray, now: Long, ended: Boolean): LongArray?
    @JvmStatic external fun peakDbfs(peak: Int): Long
}
