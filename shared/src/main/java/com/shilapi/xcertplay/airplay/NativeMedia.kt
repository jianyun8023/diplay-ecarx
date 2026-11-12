package com.shilapi.xcertplay.airplay

import android.util.Log
import java.nio.ByteBuffer

/**
 * The media data plane in `libdiplay_media` (Rust; source in `shared/src/main/rust/diplay-media`, design
 * in docs/ARCHITECTURE.zh-CN.md). The screen and audio receivers hand it their socket and get pooled
 * [MediaPacket]s back; [chachaOpen] serves the Kotlin callers of ChaCha20-Poly1305. When the library
 * does not load (host unit tests, an unexpected ABI) every caller keeps its Kotlin path.
 */
internal object NativeMedia {
    private val load: Result<Unit> by lazy {
        runCatching { System.loadLibrary("diplay_media") }
            .onFailure { runCatching { Log.w("xcertplay-usb", "native media library unavailable: $it") } }
    }

    val available: Boolean get() = load.isSuccess

    /** Why the library did not load, for callers that report it; null when it loaded. */
    val loadError: Throwable? get() = load.exceptionOrNull()

    /** Plaintext length written at [out] + [outOffset]; -1 when the tag does not verify. */
    @JvmStatic external fun chachaOpen(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        aadOffset: Int,
        aadLength: Int,
        input: ByteArray,
        inputOffset: Int,
        inputLength: Int,
        out: ByteArray,
        outOffset: Int,
    ): Int

    /**
     * Receives one screen stream from [fd], which the receiver owns from now on, on its own
     * `airplay-screen` thread. Returns the handle for [stop], or 0 when it could not start (the caller
     * then still owns [fd]).
     */
    @JvmStatic external fun startVideo(fd: Int, key: ByteArray, callbacks: NativeStreamCallbacks, route: Long = 0L): Long

    @JvmStatic external fun videoRouteCreate(): Long
    /** Consumes retained pipe handles; any zero selects the packet callback fallback. Empty disconnects. */
    @JvmStatic external fun videoRouteSetTargets(handle: Long, targets: LongArray)
    @JvmStatic external fun videoRouteDestroy(handle: Long)
    @JvmStatic external fun videoTouchSent(sentAtNanos: Long)
    @JvmStatic external fun videoTouchFrame(nowNanos: Long): Long

    /** As [startVideo], for one RTP audio stream on an `airplay-audio-rx` thread at audio priority. */
    @JvmStatic external fun startAudio(fd: Int, key: ByteArray, streamType: Int, callbacks: NativeStreamCallbacks): Long

    /** Stops a stream and gives up [handle]; also safe from the stream's own callbacks. */
    @JvmStatic external fun stop(handle: Long)

    /** Opens one buffered-audio frame into a pooled RTP packet; 0 when it is malformed or does not open. */
    @JvmStatic external fun openRtp(key: ByteArray, body: ByteArray, length: Int): Long

    @JvmStatic external fun bufferedCreate(sampleRate: Int): Long
    /** Receives into the core's queue. The returned receiver handle is stopped after destroying the owner. */
    @JvmStatic external fun bufferedStart(handle: Long, fd: Int, key: ByteArray, callbacks: NativeStreamCallbacks): Long
    /** Retained packet; out: sample, first frame, output generation. Commit after the platform start callback. */
    @JvmStatic external fun bufferedNext(handle: Long, nowNanos: Long, out: LongArray): Long
    @JvmStatic external fun bufferedCommit(handle: Long, generation: Long): Boolean
    /** 0 anchor, 1 rate, 2 flush, 3 feedback. out: RTP (-1 absent), NTP seconds/fraction, rate, stop output. */
    @JvmStatic external fun bufferedControl(handle: Long, op: Int, timestamp: Long, rate: Int,
        ntpSeconds: Long, ntpFraction: Long, nowNanos: Long, out: LongArray)
    /** Wakes the bounded receiver, releases all queued packets; returns whether to stop the platform output. */
    @JvmStatic external fun bufferedDestroy(handle: Long): Boolean

    @JvmStatic external fun audioQueueCreate(clockRate: Int, media: Boolean): Long
    /** Consumes packet. Result bit 0: accepted; remaining bits: stale packets released. */
    @JvmStatic external fun audioQueuePush(handle: Long, packet: Long, sample: Int, arrivedNanos: Long): Int
    /** Transfers a packet; out: sample, arrival. */
    @JvmStatic external fun audioQueuePoll(handle: Long, out: LongArray): Long
    @JvmStatic external fun audioQueueSize(handle: Long): Int
    @JvmStatic external fun audioQueuePending(handle: Long): Long
    @JvmStatic external fun audioQueueArrivals(handle: Long, sinceNanos: Long, nowNanos: Long, sampleRate: Int): Double
    @JvmStatic external fun audioQueueDestroy(handle: Long)

    @JvmStatic external fun clockCreate(sampleRate: Int, targetFrames: Long, integral: Double, measuresTarget: Boolean): Long
    @JvmStatic external fun clockSample(handle: Long, queuedFrames: Long, nowNanos: Long,
        compressedFrames: Long, headMoving: Boolean, arrivedFrameNanos: Double)
    /** Zero keeps the current rate. */
    @JvmStatic external fun clockRateDue(handle: Long, nowNanos: Long): Int
    /** 0 current rate, 1 last sample time, 2 measure again. */
    @JvmStatic external fun clockQuery(handle: Long, what: Int): Long
    @JvmStatic external fun clockLearned(set: Boolean, value: Double): Double
    @JvmStatic external fun clockDestroy(handle: Long)

    /** EV 0xA101 parameter body; energyMask bits 0/1 preserve optional current/max charge. Null rejects invalid values. */
    @JvmStatic external fun vehicleStatusPayload(rangeKm: Int, rangeWarning: Boolean, batteryPercent: Double,
        currentChargeWh: Long, maxChargeWh: Long, energyMask: Int, maxRangeKm: Int, charging: Boolean): ByteArray?

    @JvmStatic external fun packetSize(handle: Long): Int
    @JvmStatic external fun packetRetain(handle: Long)
    @JvmStatic external fun packetRelease(handle: Long)
    /** Bytes copied into the direct [target] at [position], or -1 when it is not direct or too small. */
    @JvmStatic external fun packetCopyInto(handle: Long, offset: Int, length: Int, target: ByteBuffer, position: Int): Int
    @JvmStatic external fun packetRead(handle: Long, offset: Int, length: Int, out: ByteArray, outOffset: Int): Boolean
    /** A pooled copy of the first [length] bytes of [bytes]; 0 on bad arguments. */
    @JvmStatic external fun packetWrap(bytes: ByteArray, length: Int): Long

    /** An audio renderer's PCM state (see `NativePcm`); 0 on bad arguments. */
    @JvmStatic external fun pcmCreate(sampleRate: Int, channels: Int, voiceFilter: Boolean, opus: Boolean): Long
    @JvmStatic external fun pcmDestroy(handle: Long)
    /** PCM bytes written from the start of the direct [out], or negative when the packet does not decode. */
    @JvmStatic external fun pcmDecodeOpus(handle: Long, packet: Long, offset: Int, length: Int, out: ByteBuffer): Int
    /** Big-endian LPCM bytes copied into the start of the direct [out] as little-endian, or -1. */
    @JvmStatic external fun pcmCopySwapped(packet: Long, offset: Int, length: Int, out: ByteBuffer): Int
    /** A screen stream's native decode loop (see `NativeVideoPipe`); 0 when this system cannot run one. */
    @JvmStatic external fun pipeCreate(callbacks: NativeVideoCallbacks, pacingDelayNanos: Long, trackTouches: Boolean): Long
    /** A separately owned reference for a receiver route; survives destruction of the Kotlin pipe handle. */
    @JvmStatic external fun pipeRetain(handle: Long): Long
    @JvmStatic external fun pipeConfigure(
        handle: Long, name: String?, mime: String, width: Int, height: Int, keys: Array<String>, values: IntArray,
        csd0: ByteArray?, csd1: ByteArray?, surface: android.view.Surface, hevc: Boolean, reportVendorFormat: Boolean,
        detailOut: Array<String?>,
    ): Int
    @JvmStatic external fun pipeRelease(handle: Long)
    @JvmStatic external fun pipeSetSurface(handle: Long, surface: android.view.Surface, shown: Boolean): Int
    @JvmStatic external fun pipeHold(handle: Long, hold: Boolean)
    @JvmStatic external fun pipeSetAccepting(handle: Long, accepting: Boolean, resetChain: Boolean)
    @JvmStatic external fun pipeSubmit(handle: Long, frame: Long, size: Int, flags: Int, senderNanos: Long, arrivalNanos: Long)
    @JvmStatic external fun pipeLive(handle: Long, out: LongArray)
    @JvmStatic external fun pipeTakeStats(handle: Long, out: LongArray, decodeMicros: IntArray): Int
    @JvmStatic external fun pipeDestroy(handle: Long)

    /** A 48 kHz mono VoIP Opus encoder (opus-pure); 0 when [bitrate] is refused. */
    @JvmStatic external fun opusEncoderCreate(bitrate: Int): Long
    /** Encodes 960 samples of [pcm] into [output]; the packet length, or negative (the encoder is then reset). */
    @JvmStatic external fun opusEncode(handle: Long, pcm: ByteArray, output: ByteArray): Int
    @JvmStatic external fun opusEncoderDestroy(handle: Long)

    /**
     * The microphone sender on [fd] (a duplicate it owns): Opus at [opusBitrate] (0 for PCM), sealed,
     * to [address] ([scopeId] for a link-local IPv6 peer) and [port]. 0 when an argument is wrong.
     */
    @JvmStatic external fun uplinkCreate(
        fd: Int, key: ByteArray, payloadType: Int, address: ByteArray, scopeId: Int, port: Int,
        opusBitrate: Int, rtpSamplesPerPacket: Int,
    ): Long
    /** Packets sent for one frame (0 when the encoder produced none), -1 encode failure, -errno send failure. */
    @JvmStatic external fun uplinkSend(handle: Long, frame: ByteArray, length: Int): Int
    @JvmStatic external fun uplinkClose(handle: Long)

    /**
     * The wired data path (see `NcmUsbBridge.startNative`): bulk IN/OUT on [usbFd] and the VPN tun on
     * [tunFd], both duplicates the engine owns. 0 on bad arguments.
     */
    @JvmStatic external fun ncmStart(
        usbFd: Int, inEndpoint: Int, outEndpoint: Int, packetSize: Int, tunFd: Int, hostMac: ByteArray,
        inbound: NativeStreamCallbacks, outbound: NativeStreamCallbacks,
    ): Long
    @JvmStatic external fun ncmTraffic(handle: Long, airPlayPort: Int): String
    @JvmStatic external fun ncmAttemptSince(handle: Long, port: Int, sinceNanos: Long): Boolean
    /** System.nanoTime of the first frame the phone accepted on bulk OUT, 0 before. */
    @JvmStatic external fun ncmLinkOpened(handle: Long): Long
    @JvmStatic external fun ncmStop(handle: Long)

    /** An AAudio output for 16-bit PCM routed by [usage]; 0 when AAudio cannot open it. */
    @JvmStatic external fun outOpen(sampleRate: Int, channels: Int, usage: Int, contentType: Int, capacityFrames: Int): Long
    /** Input bytes consumed; transformed output may remain queued. Zero length drains it. Errors end the output. */
    @JvmStatic external fun outWrite(handle: Long, buffer: ByteBuffer, offset: Int, length: Int): Int
    @JvmStatic external fun outFramesRead(handle: Long): Int
    /** 0 underruns, 1 buffer frames, 2 state, 3 sample rate. */
    @JvmStatic external fun outQuery(handle: Long, what: Int): Int
    /** 0 start, 1 pause, 2 flush. */
    @JvmStatic external fun outControl(handle: Long, what: Int): Int
    @JvmStatic external fun outSetGain(handle: Long, gain: Float)
    @JvmStatic external fun outSetRate(handle: Long, rate: Int): Int
    @JvmStatic external fun outClose(handle: Long)

    /** Runs the PCM steps on the first [length] bytes of the direct [buffer]; 1 when sound was detected. */
    @JvmStatic external fun pcmProcess(handle: Long, buffer: ByteBuffer, length: Int, flags: Int, attenuationDb: Int, peak: Int): Int
}

/**
 * Called by a native receiver from its own thread. [onPacket] hands over a packet handle the
 * receiver no longer owns: the implementation must wrap it in a [NativePacket] or release it.
 * For video, [a] is the iPhone's frame time and [b] the arrival time (System.nanoTime); for audio,
 * [a] is the RTP timestamp (unsigned) and [b] the arrival time.
 */
internal abstract class NativeStreamCallbacks {
    abstract fun onPacket(handle: Long, size: Int, flags: Int, a: Long, b: Long)
    open fun onConfig(bytes: ByteArray) {}
    open fun onReceiving() {}
    /** Logcat: [level] 0 is info, 1 a warning. */
    abstract fun onLog(level: Int, line: String)
    /** A line for the session's diagnostic log (the `Receive:` stats). */
    abstract fun onReport(line: String)
    /** The receive loop ended on its own: [error] is null at end of stream. Not called after [NativeMedia.stop]. */
    abstract fun onEnded(error: String?)
}

/** Events from a native video pipe, delivered in order on its own `carplay-video-events` thread. */
internal abstract class NativeVideoCallbacks {
    abstract fun onEvent(kind: Int, detail: String?)
}
