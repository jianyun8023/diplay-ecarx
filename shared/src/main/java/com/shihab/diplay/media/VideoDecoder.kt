package com.shihab.diplay.media

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.shihab.diplay.airplay.HeapPacket
import com.shihab.diplay.airplay.MediaPacket
import com.shihab.diplay.airplay.VideoCodec
import java.io.Closeable

/** CarPlay's main screen stream type. */
internal const val MAIN_SCREEN_TYPE = 110

/**
 * An offscreen consumer a parked decoder renders to while its SurfaceView is gone. It drops each image
 * on its own thread, so it keeps draining while the main thread waits on a surface handoff. Only the
 * decoder worker creates and closes it, after the codec no longer renders to it.
 */
internal class ParkingOutput(width: Int, height: Int) {
    private val consumerLock = Any()
    private var closed = false
    private val thread = android.os.HandlerThread("carplay-video-parking").apply { start() }
    private val reader: android.media.ImageReader
    val surface: Surface
    /** The looper the consumer drains on, never the main one. */
    val listenerLooper: android.os.Looper get() = thread.looper

    init {
        try {
            reader = android.media.ImageReader.newInstance(
                width.coerceAtLeast(1), height.coerceAtLeast(1), android.graphics.ImageFormat.PRIVATE, 3,
            )
            reader.setOnImageAvailableListener({ source ->
                synchronized(consumerLock) {
                    if (!closed) source.acquireLatestImage()?.close()
                }
            }, android.os.Handler(thread.looper))
            surface = reader.surface
        } catch (error: Throwable) {
            thread.quitSafely()
            throw error
        }
    }

    fun close() {
        try {
            synchronized(consumerLock) {
                if (!closed) {
                    closed = true
                    reader.close()
                }
            }
        } finally {
            thread.quitSafely()
        }
    }
}

/**
 * The operating rate requested from a stream's decoder, 0 for none: the main screen asks for the frame
 * rate the iPhone is asked for ([frameRate], the frame-rate setting). At 60 fps on my Tang, the Qualcomm
 * decoder's median time from queueing a frame to dequeueing its output was 8.5–9 ms lower with it.
 * Mirrors and the cluster stream keep the format they had.
 */
internal fun videoOperatingRate(type: Int, statsLabel: String?, frameRate: Int): Int =
    NativeVideoPolicy.operatingRate(type, statsLabel != null, frameRate)

internal data class DecoderAttempt(
    val codecName: String?,
    val tuned: Boolean,
    val operatingRate: Int = 0,
    val mtkMode: MtkDecoderMode = MtkDecoderMode.NONE,
)

/** Qualcomm's private request can be submitted to older OMX drivers, including on API 28.
 * Picture order is separate: do not request it here without a guarantee of no reordered frames.
 */
internal const val QTI_LOW_LATENCY_PARAMETER = "vendor.qti-ext-dec-low-latency.enable"
internal fun vendorLowLatencyKeys(codecName: String): List<String> =
    if (videoCodecNameFlags(codecName) and 1 != 0) listOf(QTI_LOW_LATENCY_PARAMETER) else emptyList()

/** One regular decoder of the stream's type, as [lowLatencyDecoderName] sees it. */
internal data class DecoderCandidate(val name: String, val hardware: Boolean, val alias: Boolean, val lowLatency: Boolean)

/**
 * The decoder the main screen tries first with smooth video: the first hardware decoder (not an alias) that
 * advertises the low-latency feature, whose tuned format then asks for low latency. On my Tang this is
 * c2.qti.avc.decoder.low_latency; the default c2.qti.avc.decoder does not advertise the feature.
 */
internal fun lowLatencyDecoderName(candidates: List<DecoderCandidate>): String? =
    candidates.getOrNull(NativeVideoPolicy.lowLatency(candidates.map { (if (it.hardware) 1 else 0) or (if (it.alias) 2 else 0) or (if (it.lowLatency) 4 else 0) }.toIntArray()))?.name

/**
 * Qualcomm's decoder parameter for output in decoding order. Without it my Tang's decoders released a frame
 * only once one or two later frames had been queued. Decoding order is the display order only for a stream
 * without reordered (B) frames. The iPhone's CarPlay H.264 had none, and its HEVC declares
 * sps_max_num_reorder_pics=0 (Mi 8 bench, 2026-11-10, main and second stream). It is set only for the main
 * screen with smooth video on known Qualcomm OMX decoders ([submitsPictureOrderUnlisted]).
 */
internal const val PICTURE_ORDER_PARAMETER = "vendor.qti-ext-dec-picture-order.enable"

/**
 * Qualcomm's OMX decoders never list vendor parameters (API 28 cannot ask; the Mi 8's OMX lists nothing on
 * Android 15), yet a car probe on the ECARX 753's OMX.qcom.video.decoder.avc/hevc accepted the parameter at
 * configure. They get it unlisted, and a configure, start or runtime failure drops it as for QTI low latency.
 */
internal fun submitsPictureOrderUnlisted(codecName: String): Boolean =
    videoCodecNameFlags(codecName) and 2 != 0

/** Serial platform control for the Rust NDK video loop; the worker owns configuration and surfaces. */
internal class VideoDecoder(
    streamType: Int,
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val requestKeyFrame: () -> Unit,
    private val report: (String) -> Unit,
    statsLabel: String? = null,
    pacingDelayNanos: Long = 0L,
    /** Called on the worker as its last step, after it has released its codec. */
    private val onExit: (VideoDecoder) -> Unit = {},
    operatingRate: Int = 0,
    /** Set [vendorLowLatencyKeys] on the tuned configure attempts. */
    private val vendorLowLatency: Boolean = false,
    /** Another decoder of this sink had to drop [vendorLowLatency]; this one starts without it. */
    vendorLowLatencyRefusedEarlier: Boolean = false,
    /** Called when this decoder drops [vendorLowLatency], so the sink's later decoders skip it. */
    private val onVendorLowLatencyRefused: () -> Unit = {},
    /**
     * The main screen with smooth video (H.264 and HEVC): try a low-latency decoder first ([lowLatencyDecoderName]) and
     * ask known Qualcomm OMX decoders for [PICTURE_ORDER_PARAMETER] to avoid frame reordering delay.
     */
    private val framesAtOnce: Boolean = false,
    private val mtkDecoderTuningEnabled: Boolean = false,
    private val debugLogsEnabled: () -> Boolean = { false },
    /** Draws this decoder's pictures onto its surface and its mirrors while it has any ([setMirror]). */
    private val fanoutFactory: (width: Int, height: Int, report: (String) -> Unit) -> VideoFanout =
        { w, h, r -> GlVideoFanout(w, h, r) },
) : Closeable {
    // Worker only: the cached debug-log switch for the periodic stats line.
    private var debugLogsCached = false
    private var debugLogsCheckedNanos = 0L
    private val queue = VideoDecodeQueue()
    private val pipe = NativeVideoPipe.create(pacingDelayNanos, streamType == MAIN_SCREEN_TYPE && statsLabel == null) { kind, detail ->
        queue.offer(VideoJob.NativeEvent(kind, detail))
    }.apply { setAccepting(surface != null, resetChain = false) }
    // Guards [pipe] against [submit] and [liveCounters] once the worker has destroyed it.
    private val pipeLock = Any()
    private var pipeAlive = true
    // The decoder the pipe runs, by name, null while it has none; and whether the pipe keeps frames for
    // one (there is a surface). Worker only.
    @Volatile private var nativeCodecName: String? = null
    private var pipeAccepting = surface != null
    @Volatile private var running = true
    private var outputSurface: Surface? = surface
    private var parking: ParkingOutput? = null
    // The stream's mirrors and, while there are any, the fanout the codec renders to instead of
    // [outputSurface]; the fanout then draws onto [outputSurface] and every mirror. Worker only.
    private val mirrors = LinkedHashMap<String, Surface>()
    private var fanout: VideoFanout? = null
    private val configuration = DecoderConfigurationPolicy(operatingRate, mtkDecoderTuningEnabled, vendorLowLatencyRefusedEarlier)
    private val operatingRate get() = configuration.operatingRate
    private val configuredRate get() = configuration.configuredRate
    private val configuredMtkMode get() = configuration.configuredMtkMode
    private val configuredMtkCodec2LowLatency get() = configuration.configuredMtkCodec2LowLatency
    private val configuredVendorLowLatency get() = configuration.configuredVendorLowLatency
    private val vendorLowLatencyRefused get() = configuration.vendorLowLatencyRefused
    private val startedLowLatency get() = configuration.startedLowLatency
    private val startedPictureOrder get() = configuration.startedPictureOrder
    // Installed decoders per MIME type, read once: the list does not change while the app runs.
    private val installedDecoders = HashMap<String, List<InstalledDecoder>>()
    // Set within one configure pass once the hardware decoder could not be created even after its retries;
    // the pass then goes to the software decoder instead of creating the hardware one again per attempt.
    private var hardwareCreateFailed: Boolean
        get() = configuration.hardwareCreateFailed
        set(value) { configuration.hardwareCreateFailed = value }
    // While the stream decodes in software because the hardware was busy: when to check for it again.
    private var hardwareProbe: HardwareDecoderProbe? = null
    private val hardwareProbeIntervalMillis get() = configuration.probeIntervalMillis
    private var failureReports = 0
    // The main screen keeps the historical log format; other screens are labelled.
    private val stats = VideoStats(statsLabel ?: if (streamType == MAIN_SCREEN_TYPE) "" else " stream=$streamType")
    fun liveCounters(): LiveVideoCounters = synchronized(pipeLock) {
        if (pipeAlive) {
            val native = pipe.live()
            LiveVideoCounters(native[0], native[1], native[2], native[3])
        } else LiveVideoCounters(0, 0, 0, 0)
    }

    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true }

    fun start() {
        thread.start()
    }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        // On the receiver's thread, before the keyframe that follows: the pipe must not feed it to the old decoder.
        synchronized(pipeLock) { if (pipeAlive) pipe.hold(true) }
        queue.offer(VideoJob.Config(codec, codecData))
    }

    fun submit(nalus: ByteArray, senderNanos: Long = 0L, arrivalNanos: Long = 0L) =
        submit(HeapPacket(nalus), senderNanos, arrivalNanos)

    /** Takes ownership of [frame]: the worker releases it once fed or dropped. */
    fun submit(frame: MediaPacket, senderNanos: Long = 0L, arrivalNanos: Long = 0L) {
        synchronized(pipeLock) { if (pipeAlive) pipe.submit(frame, senderNanos, arrivalNanos) else frame.release() }
    }

    fun retainNativeTarget(): Long = synchronized(pipeLock) {
        if (pipeAlive) pipe.retainTarget() else 0L
    }

    fun setSurface(surface: Surface?) {
        queue.offer(VideoJob.SurfaceChanged(surface))
    }

    /** Also shows this stream on [surface] under [key], from the same decoded pictures; null stops it. */
    fun setMirror(key: String, surface: Surface?) {
        queue.offer(VideoJob.MirrorChanged(key, surface))
    }

    /** Asks the worker to stop rendering to [surface], parking the codec when [park]. */
    fun requestDetach(surface: Surface, park: Boolean): SurfaceDetachRequest {
        check(Thread.currentThread() !== thread) { "detach from the decoder worker" }
        return SurfaceDetachRequest(surface, park).also { queue.offer(VideoJob.DetachSurface(it)) }
    }

    /** True once [request] is confirmed or the worker has exited; false when [deadlineNanos] passed first. */
    fun awaitDetach(request: SurfaceDetachRequest, deadlineNanos: Long): Boolean =
        request.await(deadlineNanos - System.nanoTime(), workerAlive = thread::isAlive)

    /** True once the worker has exited, so its codec is released. */
    val exited: Boolean get() = !thread.isAlive

    /**
     * After [close], waits until [deadlineNanos] for the worker to release its codec and exit. An
     * interrupt does not end the wait early; it is restored before returning.
     */
    fun awaitExitUntil(deadlineNanos: Long): Boolean {
        var interrupted = false
        try {
            while (thread.isAlive) {
                val leftMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L
                if (leftMillis <= 0) break
                try { thread.join(leftMillis) } catch (_: InterruptedException) { interrupted = true }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        return !thread.isAlive
    }

    fun refreshPicture() {
        queue.offer(VideoJob.RefreshPicture)
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        try {
            while (running) {
                val job = queue.poll(VIDEO_IDLE_POLL_MS)
                try {
                    when (job) {
                        is VideoJob.Config -> try {
                            configureDecoder(job, completingConfigHold = true)
                        } finally {
                            pipe.hold(false)
                        }
                        // This mailbox only receives control jobs; frames are submitted to the pipe.
                        is VideoJob.Frame -> job.packet.release()
                        is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                        is VideoJob.MirrorChanged -> changeMirror(job.key, job.surface)
                        is VideoJob.DetachSurface -> detach(job.request)
                        // Once per new surface, so it skips the rate limit an earlier request may still hold.
                        is VideoJob.NativeEvent -> onPipeEvent(job.kind, job.detail)
                        is VideoJob.RefreshPicture -> if (hasCodec()) {
                            pipe.requestKeyframe(System.nanoTime(), force = true)
                            requestKeyFrame()
                        }
                        is VideoJob.Resync -> Unit
                        null -> Unit
                    }
                    probeHardwareIfDue()
                    if (statsLoggingEnabled()) {
                        pipe.drainStats(stats)
                        stats.logIfDue()?.let(report)
                    }
                    updatePipeAccepting()
                } catch (error: Exception) {
                    onJobFailure(job?.javaClass?.simpleName ?: "drain", error)
                } catch (error: LinkageError) {
                    if (running) reportFailure("stage=${job?.javaClass?.simpleName ?: "drain"}", error)
                    throw error
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            running = false
            releaseDecoder()
            synchronized(pipeLock) {
                if (pipeAlive) pipe.destroy()
                pipeAlive = false
            }
            // The codec is gone, so nothing renders to any surface or to the parking consumer any more.
            parking?.close()
            parking = null
            fanout?.let { runCatching(it::close) }
            fanout = null
            queue.drain().forEach { job ->
                (job as? VideoJob.DetachSurface)?.request?.complete(DetachOutcome.RELEASED)
                (job as? VideoJob.Frame)?.packet?.release()
            }
            runCatching { onExit(this) }
        }
    }

    private fun hasCodec(): Boolean = nativeCodecName != null

    /** Moves the running native codec to [surface]; throws when it cannot. */
    private fun setCodecSurface(surface: Surface) {
        if (hasCodec()) check(pipe.setSurface(surface, shown = surface !== parking?.surface)) {
            "native video decoder could not move to the surface"
        }
    }

    private fun updatePipeAccepting() {
        val accepting = codecSurface() != null
        if (accepting == pipeAccepting) return
        pipeAccepting = accepting
        pipe.setAccepting(accepting, resetChain = false)
    }

    /** What the native decode loop asks of the worker; see [NativeVideoPipe]. */
    private fun onPipeEvent(kind: Int, detail: String?) {
        when (kind) {
            NativeVideoPipe.EVENT_REQUEST_KEYFRAME -> {
                requestKeyFrame()
            }
            NativeVideoPipe.EVENT_REPORT -> detail?.let(report)
            NativeVideoPipe.EVENT_LOG -> if (detail?.startsWith("Video recovery") == true) Log.w(TAG, detail) else Log.i(TAG, detail ?: "")
            NativeVideoPipe.EVENT_NEED_DECODER -> if (!hasCodec()) pipe.lastConfiguration()?.let { configureDecoder(it, resetChain = false) }
            NativeVideoPipe.EVENT_RELEASE_DECODER -> releaseDecoder()
            NativeVideoPipe.EVENT_CODEC_ERROR -> onJobFailure("native", IllegalStateException("native video decoder failed: $detail"))
            NativeVideoPipe.EVENT_INPUT_STALLED -> {
                dropVendorLowLatency("video decoder input stalled")
                dropMtkTuning("video decoder input stalled")
                dropMtkCodec2LowLatency("video decoder input stalled")
            }
            NativeVideoPipe.EVENT_FIRST_FRAME -> {
                configuration.firstFrame()
                report("first frame rendered")
                if (vendorLowLatency) report("QTI first output released for rendering " +
                    "qtiLowLatencySubmitted=$configuredVendorLowLatency effectiveness=unverified")
                Log.i(TAG, "video decoder rendered first frame $detail decoder=native")
            }
        }
    }

    private fun onJobFailure(stage: String, error: Exception) {
        if (running) Log.e(TAG, "video decoder job failed: $stage", error)
        if (running) reportFailure("stage=$stage", error)
        // MTK vendor failures may surface as CodecException or the broader IllegalStateException.
        // Retire the active experimental mode before recreating the decoder.
        if (error is IllegalStateException) {
            dropVendorLowLatency("codec failed during $stage")
            dropMtkTuning("codec failed during $stage")
            dropMtkCodec2LowLatency("codec failed during $stage")
        }
        // A codec can accept the rate and still fail once it runs (start errors may surface on
        // later calls): one that failed before its first frame with it is not given it again.
        if (configuration.dropsRateAfterFailure(error is MediaCodec.CodecException)) {
            dropOperatingRate("codec failed before its first frame")
        }
        releaseDecoder()
        queue.discardCurrentChain()
        requestKeyFrameIfDue()
    }

    /** [resetChain] false only when the pipe asks for a decoder for the keyframe it holds. */
    private fun configureDecoder(config: VideoJob.Config, resetChain: Boolean = true, completingConfigHold: Boolean = false) {
        val memory = pipe.rememberConfiguration(config, hasCodec())
        if (memory != 0) {
            if (memory == 1 && debugLogsEnabled()) Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            return
        }
        if (!resetChain) Log.i(TAG, "video decoder rebuilt for the native pipe's keyframe")
        releaseDecoder()
        val surface = codecSurface() ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val csd = if (codec == VideoCodec.H265) {
            MediaCodecSupport.hevcCodecSpecificData(codecData).takeIf { it.isNotEmpty() }
                ?.let { listOf(it) } ?: emptyList()
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            listOfNotNull(
                sps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
                pps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
            )
        }
        val requestedRate = operatingRate
        val requestedMtkCodec2 = if (configuration.tryMtkCodec2) {
            MtkDecoderTuning.codec2LowLatencyDecoderName(mime)
        } else null
        val requestedLowLatency = requestedMtkCodec2
            ?: if (framesAtOnce && configuration.tryLowLatency) findLowLatencyDecoder(mime) else null
        val requestedMtkMode = configuration.requestedMtkMode(mtkDecoderTuningEnabled && MtkDecoderTuning.legacyAcodecDecoderName(mime) != null)
        val softwareDecoder = softwareDecoderName(mime)
        var configured: ConfiguredVideoDecoder? = null
        var outcome = 0
        configuration.begin(requestedRate, softwareDecoder, requestedLowLatency, requestedMtkMode, Build.VERSION.SDK_INT)
        hardwareProbe = null
        while (true) {
            val attempt = configuration.next(softwareDecoder, requestedLowLatency) ?: break
            configured = tryConfigureNative(mime, csd, surface, attempt, framesAtOnce)
            outcome = configuration.result(configured != null, attempt.codecName == requestedLowLatency)
            if (configured != null) break
        }
        if (configured != null) noteSoftwareFallback(mime, configured.codecName)
        if (configured == null) {
            queue.discardCurrentChain()
            report("decoder configuration failed mime=$mime size=${width}x$height")
        }
        if (outcome and 1 != 0) dropOperatingRate("refused at configure")
        if (outcome and 2 != 0) {
            report("low-latency decoder $requestedLowLatency dropped: refused at configure")
            configuration.refuseLowLatency()
        }
        configuration.finishedAttempt()
        nativeCodecName = configured?.codecName
        // Retire only this failure's chain; a newer Config may already have delivered its only IDR.
        if (configured == null) pipe.configurationFailed(completingConfigHold)
        val mtkFallback = configuration.finish(if (configured != null) configuration.successfulRate else 0, configured?.mtkMode ?: MtkDecoderMode.NONE,
            requestedMtkMode, configured != null,
            configured != null && requestedMtkCodec2 != null && configured.codecName.equals(requestedMtkCodec2, ignoreCase = true),
            configured?.codecName?.let(MtkDecoderTuning::supportsLegacyAcodecKeys) == true)
        if (mtkFallback) {
            report("MTK decoder tuning fell back at configure from=${requestedMtkMode.diagnosticName} " +
                "to=${configuredMtkMode.diagnosticName}")
            configuration.acceptMtkFallback()
        }
        if (configured != null) {
            val rate = if (requestedRate > 0) " operatingRate=$configuredRate" else ""
            val lowLatency = " qtiLowLatencyRequested=$vendorLowLatency" +
                " qtiLowLatencySubmitted=$configuredVendorLowLatency" +
                " qtiLowLatencyFallback=$vendorLowLatencyRefused effectiveness=unverified"
            val atOnceReport = if (framesAtOnce) " lowLatency=$startedLowLatency pictureOrder=$startedPictureOrder" else ""
            val mtk = when {
                configuredMtkCodec2LowLatency -> " mtkTuning=codec2-low-latency"
                MtkDecoderTuning.isDecoderName(configured.codecName) ->
                    " mtkTuning=${configuredMtkMode.diagnosticName}"
                else -> ""
            }
            report("decoder=${configured.codecName} mime=$mime size=${width}x$height" +
                "$rate$lowLatency$atOnceReport$mtk")
            Log.i(
                TAG,
                "video decoder configured name=${configured.codecName} mime=$mime size=${width}x$height$mtk",
            )
        }
    }

    private fun dropVendorLowLatency(reason: String) {
        if (!configuration.declineVendor()) return
        onVendorLowLatencyRefused()
        report("QTI low-latency fallback: $reason; next=without-private-parameters for this session")
    }

    private fun dropOperatingRate(reason: String) {
        report("operating rate $operatingRate dropped: $reason")
        configuration.dropRate()
    }

    private fun dropMtkTuning(reason: String) {
        val transition = configuration.dropMtk()
        if (transition < 0) return
        val previous = MtkDecoderMode.entries[transition and 3]
        val fallback = MtkDecoderMode.entries[(transition ushr 2) and 3]
        report("MTK decoder tuning ${previous.diagnosticName} dropped: $reason; " +
            "next=${fallback.diagnosticName}")
    }

    private fun dropMtkCodec2LowLatency(reason: String) {
        if (!configuration.dropMtkCodec2()) return
        report("MTK Codec2 low-latency decoder dropped: $reason; next=default")
    }

    private data class ConfiguredVideoDecoder(
        val codecName: String,
        val mtkMode: MtkDecoderMode,
    )

    private fun tryConfigureNative(
        mime: String,
        csd: List<ByteArray>,
        surface: Surface,
        attempt: DecoderAttempt,
        atOnce: Boolean,
    ): ConfiguredVideoDecoder? {
        while (true) {
            val startedNs = System.nanoTime()
            val requestedName = attempt.codecName ?: nativeDefaultDecoderName(mime)
            val codecName = requestedName ?: "default"
            val mtkMode = appliedMtkDecoderMode(attempt.mtkMode, requestedName.orEmpty())
            val keys = LinkedHashMap<String, Int>()
            if (attempt.tuned) {
                keys[MediaFormat.KEY_MAX_INPUT_SIZE] = MAX_INPUT_SIZE
                keys[MediaFormat.KEY_PRIORITY] = 0
                if (attempt.operatingRate > 0) keys[MediaFormat.KEY_OPERATING_RATE] = attempt.operatingRate
            }
            mtkMode.formatKeys().forEach { keys[it] = 1 }
            val advertised = attempt.tuned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && requestedName != null &&
                runCatching {
                    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == requestedName }
                        ?.getCapabilitiesForType(mime)?.isFeatureSupported("low-latency") == true
                }.getOrDefault(false)
            val flags = configuration.formatFlags(attempt.tuned, atOnce, vendorLowLatency, Build.VERSION.SDK_INT, advertised, false, videoCodecNameFlags(requestedName.orEmpty()))
            val lowLatency = flags and 1 != 0
            if (lowLatency) keys[MediaFormat.KEY_LOW_LATENCY] = 1
            val vendorKeys = if (flags and 2 != 0) listOf(QTI_LOW_LATENCY_PARAMETER) else emptyList()
            vendorKeys.forEach { keys[it] = 1 }
            val pictureOrder = flags and 4 != 0
            val privateParameters = flags and 8 != 0
            if (pictureOrder) keys[PICTURE_ORDER_PARAMETER] = 1
            if (vendorKeys.isNotEmpty()) {
                report("QTI low-latency attempt decoder=$codecName api=${Build.VERSION.SDK_INT} " +
                    "request=${vendorKeys.joinToString { "$it=1" }} pictureOrder=$pictureOrder")
            }
            val hevc = mime == MediaFormat.MIMETYPE_VIDEO_HEVC
            // Hardware sessions are freed asynchronously, so retry creation before selecting software.
            val retries = if (attempt.codecName == null && requestedName != null) HARDWARE_CREATE_RETRY_MILLIS else emptyList()
            val result = try {
                createWithRetries(retries, sleep = Thread::sleep, onRetry = { tries, failure ->
                    report("hardware decoder $requestedName create failed attempt=$tries ${MediaFailureSummary.describe(failure)}; retrying")
                }) {
                    pipe.configure(requestedName, mime, width, height, keys, csd, surface, hevc, vendorLowLatency).also {
                        if (it.status == -1) throw java.io.IOException("native create failed: ${it.detail}")
                    }
                }
            } catch (failure: Exception) {
                if (attempt.codecName == null) hardwareCreateFailed = true
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                reportFailure("stage=configure phase=create decoder=$codecName native=true mime=$mime", failure)
                return null
            }
            if (result.status != 0) {
                val phase = when (result.status) { -2 -> "configure"; -3 -> "start"; else -> "async" }
                val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
                reportFailure("stage=configure phase=$phase elapsedMs=$elapsedMs decoder=$codecName " +
                    "qtiRequest=${vendorKeys.joinToString()} tuned=${attempt.tuned} operatingRate=${attempt.operatingRate} " +
                    "mtkTuning=${mtkMode.diagnosticName} mime=$mime native=true", IllegalStateException(result.detail))
                Log.w(TAG, "native video decoder configure failed name=$codecName phase=$phase ${result.detail} " +
                    "tuned=${attempt.tuned} operatingRate=${attempt.operatingRate} mime=$mime size=${width}x$height")
                if (!configuration.retryWithoutPrivateParameters(privateParameters)) return null
                onVendorLowLatencyRefused()
                report("QTI low-latency fallback decoder=$codecName phase=$phase; " +
                    "retry=same-format-without-private-parameters for this session")
                continue
            }
            configuration.noteParameters(vendorKeys.isNotEmpty(), privateParameters)
            if (configuredVendorLowLatency) {
                report("QTI low-latency configure/start returned decoder=$codecName; effectiveness=unverified")
            }
            configuration.noteFeatures(lowLatency, pictureOrder)
            return ConfiguredVideoDecoder(result.detail?.takeIf { it.isNotEmpty() } ?: codecName, mtkMode)
        }
    }

    /** Resolve an unnamed attempt; null keeps the platform's own decoder choice. */
    private fun nativeDefaultDecoderName(mime: String): String? {
        return preferredDefaultDecoder(installedDecoders(mime), prefersSoftware(mime), hardwareCreateFailed)
    }

    /** Android 11 added the low-latency feature; aliases and software-only flags exist from Android 10. */
    private fun findLowLatencyDecoder(mime: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            lowLatencyDecoderName(MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && mime in it.supportedTypes }
                .map { info ->
                    DecoderCandidate(
                        name = info.name,
                        hardware = info.isHardwareAccelerated,
                        alias = info.isAlias,
                        lowLatency = info.getCapabilitiesForType(mime).isFeatureSupported("low-latency"),
                    )
                })
        }.getOrNull()
    }

    private fun softwareDecoderName(mime: String): String? = softwareFallbackDecoder(installedDecoders(mime))

    private fun installedDecoders(mime: String): List<InstalledDecoder> = installedDecoders.getOrPut(mime) {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && mime in it.supportedTypes }
                .map { info ->
                    val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    InstalledDecoder(
                        name = info.name,
                        software = isSoftwareDecoderName(info.name) || (q && info.isSoftwareOnly),
                        alias = q && info.isAlias,
                    )
                }
        }.getOrDefault(emptyList())
    }

    /** Whether a software decoder took over only because the hardware was busy; if so, look for it again later. */
    private fun noteSoftwareFallback(mime: String, codecName: String) {
        val hardware = defaultHardwareDecoder(installedDecoders(mime))
        if (!configuration.probesHardware(hardware != null, isSoftwareDecoderName(codecName), prefersSoftware(mime),
                hardware != null && codecName.equals(hardware, ignoreCase = true))) return
        val probe = HardwareDecoderProbe.start(checkNotNull(hardware), System.nanoTime(), hardwareProbeIntervalMillis)
        hardwareProbe = probe
        // A probe that finds the hardware free but whose decoder then fails again waits longer next time.
        configuration.advanceProbeInterval()
        report("software decoder fallback decoder=$codecName hardware=$hardware unavailable; " +
            "next hardware check in ${probe.intervalMillis} ms")
    }

    /** Worker only: once the hardware decoder can be created again, rebuild the stream on it at the next keyframe. */
    private fun probeHardwareIfDue() {
        val probe = hardwareProbe ?: return
        if (!hasCodec()) { hardwareProbe = null; return }
        val now = System.nanoTime()
        if (!probe.isDue(now)) return
        val created = runCatching { MediaCodec.createByCodecName(probe.name) }.getOrNull()
        if (created == null) {
            hardwareProbe = probe.failed(now)
            return
        }
        runCatching { created.release() }
        hardwareProbe = null
        report("hardware decoder ${probe.name} available again; leaving the software decoder")
        releaseDecoder()
        queue.discardCurrentChain()
        requestKeyFrameIfDue()
    }

    private fun prefersSoftware(mime: String): Boolean =
        NativeVideoPolicy.prefersSoftware(Build.VERSION.SDK_INT, mime == MediaFormat.MIMETYPE_VIDEO_HEVC, preferSoftwareHevcDecoder)

    /** Completes [request] once the codec no longer renders to its surface, whatever happens here. */
    private fun detach(request: SurfaceDetachRequest): Unit {
        var outcome = DetachOutcome.RELEASED
        val sharing = fanout
        if (sharing != null) {
            // The codec renders to the fanout: only the fanout lets go of the surface, and the codec keeps its state.
            try {
                if (outputSurface === request.surface) {
                    sharing.setPrimary(null)
                    outputSurface = null
                }
                mirrors.entries.firstOrNull { it.value === request.surface }?.key?.let { changeMirror(it, null) }
                outcome = DetachOutcome.NOT_RENDERING
            } catch (error: Exception) {
                closeFanout()
                outputSurface = null
                throw error
            } finally {
                request.complete(outcome)
            }
            return
        }
        try {
            outcome = detachAction(outputSurface, request.surface, request.park, hasCodec())
            when (outcome) {
                DetachOutcome.NOT_RENDERING -> Unit
                DetachOutcome.PARKED -> {
                    val consumer = parking ?: ParkingOutput(width, height).also { parking = it }
                    try {
                        outputSurface = consumer.surface
                        setCodecSurface(consumer.surface)
                        if (debugLogsEnabled()) Log.i(TAG, "video decoder parked off screen")
                    } catch (error: Exception) {
                        Log.w(TAG, "video decoder could not park; releasing it", error)
                        outcome = DetachOutcome.RELEASED
                        outputSurface = null
                        releaseDecoder()
                    }
                }
                DetachOutcome.RELEASED -> {
                    outputSurface = null
                    releaseDecoder()
                    if (debugLogsEnabled()) Log.i(TAG, "video decoder detached from surface")
                }
            }
        } catch (error: Exception) {
            outcome = DetachOutcome.RELEASED
            outputSurface = null
            releaseDecoder()
            throw error
        } finally {
            request.complete(outcome)
        }
    }

    private fun changeSurface(surface: Surface?): Unit {
        if (outputSurface === surface) return
        outputSurface = surface
        fanout?.let { sharing ->
            try {
                sharing.setPrimary(surface)
            } catch (error: Exception) {
                closeFanout()
                throw error
            }
            return
        }
        if (surface == null) {
            releaseDecoder()
            queue.discardCurrentChain()
            if (debugLogsEnabled()) Log.i(TAG, "video decoder detached from surface")
            return
        }
        if (hasCodec()) {
            try {
                setCodecSurface(surface)
                if (debugLogsEnabled()) Log.i(TAG, "video decoder output surface updated")
                return
            } catch (error: Exception) {
                Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        queue.discardCurrentChain()
        pipe.lastConfiguration()?.let(::configureDecoder)
    }

    /** Mirrors: one decoder for all of them, through a fanout that also draws onto the stream's own surface. */
    private fun changeMirror(key: String, surface: Surface?): Unit {
        if (surface == null) {
            if (mirrors.remove(key) == null) return
        } else {
            if (mirrors[key] === surface) return
            mirrors[key] = surface
        }
        val sharing = fanout
        when {
            mirrors.isEmpty() -> if (sharing != null) stopFanout(sharing)
            sharing != null -> try {
                sharing.setMirror(key, surface)
            } catch (error: Exception) {
                closeFanout()
                throw error
            }
            else -> startFanout()
        }
    }

    private fun startFanout() {
        val sharing = try {
            fanoutFactory(width, height, report)
        } catch (error: Exception) {
            Log.w(TAG, "video fanout unavailable; mirrors not shown", error)
            report("mirrors unavailable: ${MediaFailureSummary.describe(error)}")
            return
        }
        try {
            mirrors.forEach { (key, surface) -> sharing.setMirror(key, surface) }
            if (hasCodec()) {
                // Leaves the stream's own surface at once, so the fanout can draw there.
                try {
                    setCodecSurface(sharing.input)
                } catch (error: Exception) {
                    Log.w(TAG, "video decoder could not move to the fanout; reconfiguring", error)
                    releaseDecoder()
                    queue.discardCurrentChain()
                }
            }
            fanout = sharing
            sharing.setPrimary(outputSurface)
            report("mirrors share this decoder mirrors=${mirrors.size}")
        } catch (error: Exception) {
            if (fanout === sharing) closeFanout() else {
                runCatching(sharing::close)
            }
            throw error
        }
    }

    private fun stopFanout(sharing: VideoFanout) {
        // Frees the stream's own surface before the codec renders there again.
        runCatching { sharing.setPrimary(null) }
        fanout = null
        val primary = outputSurface
        if (hasCodec()) {
            if (primary == null) {
                releaseDecoder()
                queue.discardCurrentChain()
            } else try {
                setCodecSurface(primary)
            } catch (error: Exception) {
                Log.w(TAG, "video decoder could not return to its surface; reconfiguring", error)
                releaseDecoder()
                queue.discardCurrentChain()
            }
        }
        runCatching(sharing::close)
        report("mirrors stopped; decoder renders to its surface")
    }

    /** After a fanout failure: the codec rendered to it, so it goes too and the next keyframe starts again. */
    private fun closeFanout() {
        val sharing = fanout ?: return
        fanout = null
        releaseDecoder()
        queue.discardCurrentChain()
        runCatching(sharing::close)
        mirrors.clear()
    }

    /** Where the codec renders: the fanout while the stream has mirrors, else the stream's own surface. */
    private fun codecSurface(): Surface? = fanout?.input ?: outputSurface

    /** [debugLogsEnabled] reads settings; the worker loop checks it at most once a second. */
    private fun statsLoggingEnabled(): Boolean {
        val now = System.nanoTime()
        if (debugLogsCheckedNanos == 0L || now - debugLogsCheckedNanos >= DEBUG_LOGS_RECHECK_NS) {
            debugLogsCached = debugLogsEnabled()
            debugLogsCheckedNanos = now
        }
        return debugLogsCached
    }

    private fun requestKeyFrameIfDue() {
        val now = System.nanoTime()
        if (!pipe.requestKeyframe(now, force = false)) return
        requestKeyFrame()
    }

    private fun reportFailure(context: String, error: Throwable) {
        // A bad decoder can fail again on every frame; exported detail stays bounded per worker.
        if (failureReports >= 8) return
        failureReports++
        runCatching { report("decoder failed api=${Build.VERSION.SDK_INT} $context " +
            MediaFailureSummary.describe(error)) }
    }

    private fun releaseDecoder() {
        if (nativeCodecName != null) {
            nativeCodecName = null
            pipe.release()
        }
        configuration.released()
    }

    private companion object {
        const val TAG = "diplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val DEBUG_LOGS_RECHECK_NS = 1_000_000_000L
        // Waits before creating a refused hardware decoder again: 0.7 s in all, while frames queue up.
        val HARDWARE_CREATE_RETRY_MILLIS = listOf(100L, 200L, 400L)
        const val VIDEO_IDLE_POLL_MS = 50L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}
