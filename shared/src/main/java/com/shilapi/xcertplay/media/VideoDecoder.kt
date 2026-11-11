package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.HeapPacket
import com.shilapi.xcertplay.airplay.MediaPacket
import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

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
    if (type == MAIN_SCREEN_TYPE && statsLabel == null && frameRate > 0) frameRate else 0

internal data class DecoderAttempt(
    val codecName: String?,
    val tuned: Boolean,
    val operatingRate: Int = 0,
    val mtkMode: MtkDecoderMode = MtkDecoderMode.NONE,
)

/**
 * Configure attempts in order. Some vendor decoders (e.g. MediaTek c2.mtk.avc.decoder) reject the tuned
 * parameters with BAD_VALUE, so a minimal format and then software follow. An [operatingRate] is tried
 * first on its own, so a decoder that refuses it keeps the tuned format it gets without one. A
 * [lowLatencyDecoder] gets its tuned attempts before the default decoder's.
 */
internal fun videoDecoderAttempts(
    operatingRate: Int,
    softwareDecoder: String?,
    lowLatencyDecoder: String? = null,
    maximumMtkMode: MtkDecoderMode = MtkDecoderMode.NONE,
): List<DecoderAttempt> = buildList {
    if (operatingRate > 0) lowLatencyDecoder?.let { add(DecoderAttempt(it, tuned = true, operatingRate)) }
    lowLatencyDecoder?.let { add(DecoderAttempt(it, tuned = true)) }
    val mtkModes = maximumMtkMode.attempts()
    if (operatingRate > 0) {
        mtkModes.forEach { add(DecoderAttempt(null, tuned = true, operatingRate, it)) }
    }
    mtkModes.forEach { add(DecoderAttempt(null, tuned = true, mtkMode = it)) }
    add(DecoderAttempt(codecName = null, tuned = false))
    softwareDecoder?.let { add(DecoderAttempt(it, tuned = false)) }
}

/**
 * The operating rate to ask for at the next configure: none once a later attempt ([used]) worked after
 * the rate was refused, so the refusal is not paid again; unchanged when every attempt failed.
 */
internal fun nextOperatingRate(requested: Int, used: DecoderAttempt?): Int =
    if (requested > 0 && used != null && used.operatingRate == 0) 0 else requested

/** Qualcomm's private request can be submitted to older OMX drivers, including on API 28.
 * Picture order is separate: do not request it here without a guarantee of no reordered frames.
 */
internal const val QTI_LOW_LATENCY_PARAMETER = "vendor.qti-ext-dec-low-latency.enable"
internal fun vendorLowLatencyKeys(codecName: String): List<String> =
    if (codecName.startsWith("c2.qti.", ignoreCase = true) ||
        codecName.startsWith("OMX.qcom.", ignoreCase = true)) {
        listOf(QTI_LOW_LATENCY_PARAMETER)
    } else emptyList()

/** One regular decoder of the stream's type, as [lowLatencyDecoderName] sees it. */
internal data class DecoderCandidate(val name: String, val hardware: Boolean, val alias: Boolean, val lowLatency: Boolean)

/**
 * The decoder the main screen tries first with smooth video: the first hardware decoder (not an alias) that
 * advertises the low-latency feature, whose tuned format then asks for low latency. On my Tang this is
 * c2.qti.avc.decoder.low_latency; the default c2.qti.avc.decoder does not advertise the feature.
 */
internal fun lowLatencyDecoderName(candidates: List<DecoderCandidate>): String? =
    candidates.firstOrNull { it.lowLatency && it.hardware && !it.alias }?.name

/** The low-latency decoder to try at the next configure: none once another decoder ([used]) worked after it. */
internal fun nextLowLatencyDecoder(requested: String?, used: DecoderAttempt?): String? =
    if (requested != null && used != null && used.codecName != requested) null else requested

/**
 * Qualcomm's decoder parameter for output in decoding order. Without it my Tang's decoders released a frame
 * only once one or two later frames had been queued. Decoding order is the display order only for a stream
 * without reordered (B) frames. The iPhone's CarPlay H.264 had none, and its HEVC declares
 * sps_max_num_reorder_pics=0 (Mi 8 bench, 2026-11-10, main and second stream). It is set only for the main
 * screen with smooth video, on a decoder that lists the parameter or on Qualcomm's OMX decoders
 * ([submitsPictureOrderUnlisted]).
 */
internal const val PICTURE_ORDER_PARAMETER = "vendor.qti-ext-dec-picture-order.enable"

/**
 * Qualcomm's OMX decoders never list vendor parameters (API 28 cannot ask; the Mi 8's OMX lists nothing on
 * Android 15), yet a car probe on the ECARX 753's OMX.qcom.video.decoder.avc/hevc accepted the parameter at
 * configure. They get it unlisted, and a configure, start or runtime failure drops it as for QTI low latency.
 */
internal fun submitsPictureOrderUnlisted(codecName: String): Boolean =
    codecName.startsWith("OMX.qcom.video.decoder.", ignoreCase = true)

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
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
     * Release decoded frames from a thread that waits on the codec's output, instead of when the worker
     * next comes back from its queue or from feeding: a frame is shown as soon as it is decoded.
     */
    dedicatedOutputThread: Boolean = false,
    /**
     * The main screen with smooth video (H.264 and HEVC): try a low-latency decoder first ([lowLatencyDecoderName]) and
     * ask decoders that list it for [PICTURE_ORDER_PARAMETER], so each frame leaves the decoder as soon as it can.
     */
    private val framesAtOnce: Boolean = false,
    private val mtkDecoderTuningEnabled: Boolean = false,
    private val debugLogsEnabled: () -> Boolean = { false },
    /** Draws this decoder's pictures onto its surface and its mirrors while it has any ([setMirror]). */
    private val fanoutFactory: (width: Int, height: Int, report: (String) -> Unit) -> VideoFanout =
        { w, h, r -> GlVideoFanout(w, h, r) },
    /**
     * Run the frames in the native decode loop ([NativeVideoPipe]) when the library can: frames then go
     * from the receiver into the codec without this worker, and its events come back as jobs.
     */
    nativeDecodeLoop: Boolean = false,
) : Closeable {
    private val pacer = FramePacer()
    private val pacingDelay = if (pacingDelayNanos > 0) PacingDelay(pacingDelayNanos) else null
    // Per queued presentation time (decode thread only): when it was queued, and whether it is a paced
    // frame's local time rather than the queue time.
    private val queuedPresentationUs = LongArray(64)
    private val queuedAtNanos = LongArray(64)
    private val queuedPaced = BooleanArray(64)
    // Set on the paced frames queued just before a pause in the iPhone's frames: the decoder releases a
    // frame only once about two more are queued, so they waited for that pause, which no display delay
    // can hide.
    private val queuedBeforePause = BooleanArray(64)
    private val recentPacedSlots = IntArray(PAUSE_HELD_FRAMES) { -1 }
    private var queuedSlot = 0
    // The decoder releases a frame only once later input arrives, so a frame queued before a still-screen
    // gap waits for the next one; such waits are not decode time and are left out of the stats.
    private var lastQueuedNanos = 0L
    private var resumedAtNanos = 0L
    private var lastQueuedLocalNanos = 0L
    // Worker only: the cached debug-log switch for the periodic stats line.
    private var debugLogsCached = false
    private var debugLogsCheckedNanos = 0L
    private val queue = VideoDecodeQueue()
    private val pipe: NativeVideoPipe? = if (nativeDecodeLoop) {
        NativeVideoPipe.create(pacingDelayNanos) { kind, detail -> queue.offer(VideoJob.NativeEvent(kind, detail)) }
    } else null
    // Guards [pipe] against [submit] and [liveCounters] once the worker has destroyed it.
    private val pipeLock = Any()
    private var pipeAlive = pipe != null
    // The decoder the pipe runs, by name, null while it has none; and whether the pipe keeps frames for
    // one (there is a surface). Worker only.
    @Volatile private var nativeCodecName: String? = null
    private var pipeAccepting = false
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = surface
    private var parking: ParkingOutput? = null
    // The stream's mirrors and, while there are any, the fanout the codec renders to instead of
    // [outputSurface]; the fanout then draws onto [outputSurface] and every mirror. Worker only.
    private val mirrors = LinkedHashMap<String, Surface>()
    private var fanout: VideoFanout? = null
    private var lastConfig: VideoJob.Config? = null
    @Volatile private var renderedFrameLogged = false
    // The operating rate still asked for, dropped for this stream once a decoder refuses it; and the rate
    // the current codec was configured with.
    private var operatingRate = operatingRate
    private var configuredRate = 0
    private var maximumMtkMode = if (mtkDecoderTuningEnabled) {
        MtkDecoderMode.LOW_LATENCY_NO_REORDER
    } else MtkDecoderMode.NONE
    private var configuredMtkMode = MtkDecoderMode.NONE
    private var configuredMtkCodec2LowLatency = false
    // Whether the vendor low-latency parameters were set on the codec being configured, and whether any
    // private parameter was (those or an unlisted picture order), which a failure then drops.
    private var configuredVendorLowLatency = false
    private var configuredPrivateParameters = false
    private var vendorLowLatencyRefused = vendorLowLatencyRefusedEarlier
    // Likewise the low-latency decoder, not tried again once another decoder had to take over; and what the
    // codec that tryConfigure started last was given.
    private var lowLatencyDecoderRefused = false
    private var startedLowLatency = false
    private var startedPictureOrder = false
    private var submittedFrameLogged = false
    // Frames queued into the current codec. Its CSD is in the configure format, so a codec that has had
    // none can wait for the next keyframe as it is.
    private var framesSinceConfigure = 0
    private var duplicateConfigLogged = false
    // Installed decoders per MIME type, read once: the list does not change while the app runs.
    private val installedDecoders = HashMap<String, List<InstalledDecoder>>()
    // Set within one configure pass once the hardware decoder could not be created even after its retries;
    // the pass then goes to the software decoder instead of creating the hardware one again per attempt.
    private var hardwareCreateFailed = false
    // While the stream decodes in software because the hardware was busy: when to check for it again.
    private var hardwareProbe: HardwareDecoderProbe? = null
    private var hardwareProbeIntervalMillis = HardwareDecoderProbe.FIRST_INTERVAL_MILLIS
    private var failureReports = 0
    private val referenceChain = VideoReferenceChain()
    private val backlogRecovery = VideoBacklogRecovery()
    private var lastKeyFrameRequestNs = 0L
    // The main screen keeps the historical log format; other screens are labelled.
    private val stats = VideoStats(statsLabel ?: if (streamType == MAIN_SCREEN_TYPE) "" else " stream=$streamType")
    // Running totals for the live FPS counter, read from the UI thread.
    private val liveReceived = AtomicLong()
    private val liveRendered = AtomicLong()
    private val liveDecodeNanos = AtomicLong()
    private val liveDecodeSamples = AtomicLong()

    fun liveCounters(): LiveVideoCounters {
        synchronized(pipeLock) {
            if (pipeAlive) {
                val native = checkNotNull(pipe).live()
                return LiveVideoCounters(liveReceived.get(), native[1], native[2], native[3])
            }
        }
        return javaLiveCounters()
    }

    private fun javaLiveCounters() = LiveVideoCounters(
        received = liveReceived.get(),
        rendered = liveRendered.get(),
        decodeNanos = liveDecodeNanos.get(),
        decodeSamples = liveDecodeSamples.get(),
    )
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true }
    private val outputThread = if (dedicatedOutputThread) {
        Thread(::runOutput, "carplay-video-output").apply { isDaemon = true }
    } else null
    // Guards the codec handoff between the worker and the output thread: [decoder] changes, [outputSurface]
    // and [parking] changes, the per-frame bookkeeping, and the output thread's handling of one buffer.
    // The output thread waits inside dequeueOutputBuffer without it.
    private val outputLock = Object()
    // The codec the output thread is waiting on; the worker waits for it to return before releasing that codec.
    private var dequeuing: MediaCodec? = null
    // A failure on the output thread, handled by the worker as if its own job had failed.
    @Volatile private var outputFailure: Exception? = null

    fun start() {
        thread.start()
        outputThread?.start()
    }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        // On the receiver's thread, before the keyframe that follows: the pipe must not feed it to the old decoder.
        synchronized(pipeLock) { if (pipeAlive) checkNotNull(pipe).hold(true) }
        queue.offer(VideoJob.Config(codec, codecData))
    }

    fun submit(nalus: ByteArray, senderNanos: Long = 0L, arrivalNanos: Long = 0L) =
        submit(HeapPacket(nalus), senderNanos, arrivalNanos)

    /** Takes ownership of [frame]: the worker releases it once fed or dropped. */
    fun submit(frame: MediaPacket, senderNanos: Long = 0L, arrivalNanos: Long = 0L) {
        stats.onReceived(frame.size)
        liveReceived.incrementAndGet()
        if (pipe != null) {
            synchronized(pipeLock) { if (pipeAlive) pipe.submit(frame, senderNanos, arrivalNanos) else frame.release() }
            return
        }
        // Pacing runs on the worker, in queue order: callbacks of a replaced stream may still deliver here.
        queue.offer(VideoJob.Frame(frame, senderNanos = senderNanos, arrivalNanos = arrivalNanos))
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
                val job = queue.poll(workerWaitMillis())
                outputFailure?.let { failure ->
                    outputFailure = null
                    onJobFailure("output", failure)
                }
                try {
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> try {
                            val backlog = queue.backlogAfterCurrent()
                            val overloaded = !referenceChain.needsKeyFrame && backlogRecovery.observe(
                                System.nanoTime(), job.receivedNs,
                                backlog.pendingFrames, backlog.newestPendingReceivedNs,
                            )
                            if (overloaded) {
                                recover("video backlog kept growing or exceeded hard limit")
                            } else feed(job.packet, job.receivedNs, localTimeOf(job))
                        } finally {
                            job.packet.release()
                        }
                        is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                        is VideoJob.MirrorChanged -> changeMirror(job.key, job.surface)
                        is VideoJob.DetachSurface -> detach(job.request)
                        // Once per new surface, so it skips the rate limit an earlier request may still hold.
                        is VideoJob.NativeEvent -> onPipeEvent(job.kind, job.detail)
                        is VideoJob.RefreshPicture -> if (hasCodec()) {
                            lastKeyFrameRequestNs = System.nanoTime()
                            requestKeyFrame()
                        }
                        // A slow configure can fill the queue before the codec got a frame (1.9 s on a
                        // cold head unit); rebuilding that unused codec only adds another configure.
                        is VideoJob.Resync -> recover("video queue overflow", keepUnusedDecoder = true)
                        null -> Unit
                    }
                    if (outputThread == null) decoder?.let(::drainOutput)
                    probeHardwareIfDue()
                    if (statsLoggingEnabled()) {
                        pipe?.drainStats(stats)
                        stats.logIfDue()?.let(report)
                    }
                    // The pipe keeps its own reference chain and asks for keyframes itself.
                    if (pipe == null && referenceChain.needsKeyFrame && lastConfig != null && codecSurface() != null) requestKeyFrameIfDue()
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
                if (pipeAlive) pipe?.destroy()
                pipeAlive = false
            }
            outputThread?.let { output ->
                output.interrupt()
                joinUninterruptibly(output, OUTPUT_EXIT_WAIT_MS)
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

    private fun hasCodec(): Boolean = decoder != null || nativeCodecName != null

    /** Moves the running codec, Java or native, to [surface]; throws when it cannot. */
    private fun setCodecSurface(surface: Surface) {
        val codec = decoder
        if (codec != null) {
            codec.setOutputSurface(surface)
        } else if (nativeCodecName != null) {
            check(checkNotNull(pipe).setSurface(surface, shown = surface !== parking?.surface)) {
                "native video decoder could not move to the surface"
            }
        }
    }

    private fun updatePipeAccepting() {
        val pipe = pipe ?: return
        val accepting = codecSurface() != null
        if (accepting == pipeAccepting) return
        pipeAccepting = accepting
        pipe.setAccepting(accepting, resetChain = false)
    }

    /** What the native decode loop asks of the worker; see [NativeVideoPipe]. */
    private fun onPipeEvent(kind: Int, detail: String?) {
        when (kind) {
            NativeVideoPipe.EVENT_REQUEST_KEYFRAME -> {
                lastKeyFrameRequestNs = System.nanoTime()
                requestKeyFrame()
            }
            NativeVideoPipe.EVENT_REPORT -> detail?.let(report)
            NativeVideoPipe.EVENT_LOG -> if (detail?.startsWith("Video recovery") == true) Log.w(TAG, detail) else Log.i(TAG, detail ?: "")
            NativeVideoPipe.EVENT_NEED_DECODER -> if (!hasCodec()) lastConfig?.let { configureDecoder(it, resetChain = false) }
            NativeVideoPipe.EVENT_RELEASE_DECODER -> releaseDecoder()
            NativeVideoPipe.EVENT_CODEC_ERROR -> onJobFailure("native", IllegalStateException("native video decoder failed: $detail"))
            NativeVideoPipe.EVENT_INPUT_STALLED -> {
                dropVendorLowLatency("video decoder input stalled")
                dropMtkTuning("video decoder input stalled")
                dropMtkCodec2LowLatency("video decoder input stalled")
            }
            NativeVideoPipe.EVENT_FIRST_FRAME -> {
                renderedFrameLogged = true
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
        if (configuredRate > 0 && !renderedFrameLogged && error is MediaCodec.CodecException) {
            dropOperatingRate("codec failed before its first frame")
        }
        releaseDecoder()
        queue.discardCurrentChain()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    /** The output thread: waits on the current codec's output and releases each frame as it comes. */
    private fun runOutput() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
        val info = MediaCodec.BufferInfo()
        while (running) {
            val codec = synchronized(outputLock) {
                val current = decoder
                if (current == null || outputFailure != null) {
                    try { outputLock.wait(OUTPUT_IDLE_WAIT_MS) } catch (_: InterruptedException) { return }
                    null
                } else {
                    dequeuing = current
                    current
                }
            } ?: continue
            var index = MediaCodec.INFO_TRY_AGAIN_LATER
            var failure: Exception? = null
            try {
                index = codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
            } catch (error: Exception) {
                failure = error
            }
            synchronized(outputLock) {
                dequeuing = null
                outputLock.notifyAll()
                // A codec the worker released or replaced meanwhile has no buffers left to hand out.
                if (decoder === codec) {
                    try {
                        if (failure != null) throw failure
                        handleOutput(codec, index, info)
                    } catch (error: Exception) {
                        if (running) outputFailure = error
                    }
                }
            }
        }
    }

    /** [resetChain] false only when the pipe asks for a decoder for the keyframe it holds. */
    private fun configureDecoder(config: VideoJob.Config, resetChain: Boolean = true) {
        val previous = lastConfig
        if (
            hasCodec() &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                if (debugLogsEnabled()) Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            pipe?.hold(false)
            return
        }
        if (!resetChain) Log.i(TAG, "video decoder rebuilt for the native pipe's keyframe")
        lastConfig = config
        duplicateConfigLogged = false
        releaseDecoder()
        referenceChain.reset()
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
        val requestedMtkCodec2 = if (mtkDecoderTuningEnabled && !lowLatencyDecoderRefused) {
            MtkDecoderTuning.codec2LowLatencyDecoderName(mime)
        } else null
        val requestedLowLatency = requestedMtkCodec2
            ?: if (framesAtOnce && !lowLatencyDecoderRefused) findLowLatencyDecoder(mime) else null
        val requestedMtkMode = if (mtkDecoderTuningEnabled &&
            MtkDecoderTuning.legacyAcodecDecoderName(mime) != null) maximumMtkMode else MtkDecoderMode.NONE
        var configured: ConfiguredVideoDecoder? = null
        var used: DecoderAttempt? = null
        hardwareCreateFailed = false
        hardwareProbe = null
        for (attempt in videoDecoderAttempts(
            requestedRate,
            softwareDecoderName(mime),
            requestedLowLatency,
            requestedMtkMode,
        )) {
            // The default decoder is the hardware one; once it could not be created, only named ones are left.
            if (attempt.codecName == null && hardwareCreateFailed) continue
            // API 28 never had a software attempt: there it only stands in for a hardware decoder that is busy,
            // not for one that was created but refused a format (the next keyframe retries that as before).
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hardwareCreateFailed &&
                attempt.codecName != null && isSoftwareDecoderName(attempt.codecName)) continue
            configured = pipe?.let { tryConfigureNative(it, mime, csd, surface, attempt, framesAtOnce) }
                ?: if (pipe == null) tryConfigure(mime, csd, surface, attempt, framesAtOnce) else null
            if (configured != null) { used = attempt; break }
        }
        if (configured != null) noteSoftwareFallback(mime, configured.codecName)
        if (configured == null) {
            queue.discardCurrentChain()
            report("decoder configuration failed mime=$mime size=${width}x$height")
        }
        if (nextOperatingRate(requestedRate, used) != requestedRate) dropOperatingRate("refused at configure")
        if (nextLowLatencyDecoder(requestedLowLatency, used) != requestedLowLatency) {
            report("low-latency decoder $requestedLowLatency dropped: refused at configure")
            lowLatencyDecoderRefused = true
        }
        renderedFrameLogged = false
        submittedFrameLogged = false
        synchronized(outputLock) {
            decoder = configured?.codec
            nativeCodecName = if (pipe != null) configured?.codecName else null
            framesSinceConfigure = 0
            outputLock.notifyAll()
        }
        // No decoder: let the next keyframe ask again, as a Kotlin frame would.
        if (configured == null) pipe?.hold(false)
        if (configured != null) backlogRecovery.decoderStarted(System.nanoTime())
        configuredRate = used?.operatingRate ?: 0
        configuredMtkMode = configured?.mtkMode ?: MtkDecoderMode.NONE
        configuredMtkCodec2LowLatency = configured != null && requestedMtkCodec2 != null &&
            configured.codecName.equals(requestedMtkCodec2, ignoreCase = true)
        if (configured != null && MtkDecoderTuning.supportsLegacyAcodecKeys(configured.codecName) &&
            configuredMtkMode.ordinal < requestedMtkMode.ordinal) {
            report("MTK decoder tuning fell back at configure from=${requestedMtkMode.diagnosticName} " +
                "to=${configuredMtkMode.diagnosticName}")
            maximumMtkMode = configuredMtkMode
        }
        if (configured != null) {
            val rate = if (requestedRate > 0) " operatingRate=$configuredRate" else ""
            val lowLatency = " qtiLowLatencyRequested=$vendorLowLatency" +
                " qtiLowLatencySubmitted=$configuredVendorLowLatency" +
                " qtiLowLatencyFallback=$vendorLowLatencyRefused effectiveness=unverified"
            val output = if (outputThread != null) " outputThread=true" else ""
            val atOnceReport = if (framesAtOnce) " lowLatency=$startedLowLatency pictureOrder=$startedPictureOrder" else ""
            val mtk = when {
                configuredMtkCodec2LowLatency -> " mtkTuning=codec2-low-latency"
                MtkDecoderTuning.isDecoderName(configured.codecName) ->
                    " mtkTuning=${configuredMtkMode.diagnosticName}"
                else -> ""
            }
            report("decoder=${configured.codecName} mime=$mime size=${width}x$height" +
                "$rate$lowLatency$output$atOnceReport$mtk")
            Log.i(
                TAG,
                "video decoder configured name=${configured.codecName} mime=$mime size=${width}x$height$mtk",
            )
        }
    }

    private fun dropVendorLowLatency(reason: String) {
        if (!configuredPrivateParameters || vendorLowLatencyRefused) return
        vendorLowLatencyRefused = true
        onVendorLowLatencyRefused()
        report("QTI low-latency fallback: $reason; next=without-private-parameters for this session")
    }

    private fun dropOperatingRate(reason: String) {
        report("operating rate $operatingRate dropped: $reason")
        operatingRate = 0
    }

    private fun dropMtkTuning(reason: String) {
        val previous = configuredMtkMode
        if (previous == MtkDecoderMode.NONE) return
        val fallback = previous.fallback()
        if (fallback.ordinal < maximumMtkMode.ordinal) maximumMtkMode = fallback
        report("MTK decoder tuning ${previous.diagnosticName} dropped: $reason; " +
            "next=${fallback.diagnosticName}")
    }

    private fun dropMtkCodec2LowLatency(reason: String) {
        if (!configuredMtkCodec2LowLatency) return
        configuredMtkCodec2LowLatency = false
        lowLatencyDecoderRefused = true
        report("MTK Codec2 low-latency decoder dropped: $reason; next=default")
    }

    private fun buildFormat(
        mime: String,
        csd: List<ByteArray>,
        attempt: DecoderAttempt,
        mtkMode: MtkDecoderMode,
    ): MediaFormat =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            if (attempt.tuned) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (attempt.operatingRate > 0) setInteger(MediaFormat.KEY_OPERATING_RATE, attempt.operatingRate)
            }
            mtkMode.formatKeys().forEach { setInteger(it, 1) }
            csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
        }

    private data class ConfiguredVideoDecoder(
        /** Null for a decoder the native pipe runs. */
        val codec: MediaCodec?,
        val codecName: String,
        val mtkMode: MtkDecoderMode,
    )

    private fun tryConfigure(
        mime: String,
        csd: List<ByteArray>,
        surface: Surface,
        attempt: DecoderAttempt,
        /** Ask for [PICTURE_ORDER_PARAMETER] when the decoder lists it ([framesAtOnce]). */
        atOnce: Boolean = false,
    ): ConfiguredVideoDecoder? {
        var candidate: MediaCodec? = null
        var mtkMode = MtkDecoderMode.NONE
        var vendorKeys = emptyList<String>()
        var privateParameters = false
        var codecName = attempt.codecName ?: "default"
        var phase = "create"
        var phaseStartNs = System.nanoTime()
        val startedNs = phaseStartNs
        return try {
            val codec = attempt.codecName?.let { MediaCodec.createByCodecName(it) } ?: createDecoder(mime)
            candidate = codec
            val createdName = runCatching { codec.name }.getOrNull().orEmpty()
            codecName = createdName.ifEmpty { codecName }
            mtkMode = appliedMtkDecoderMode(attempt.mtkMode, createdName)
            val format = buildFormat(mime, csd, attempt, mtkMode)
            phase = "configure"
            phaseStartNs = System.nanoTime()
            val lowLatency = attempt.tuned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                codec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported("low-latency")
            if (lowLatency) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            vendorKeys = if (attempt.tuned && vendorLowLatency && !vendorLowLatencyRefused) {
                vendorLowLatencyKeys(createdName)
            } else emptyList()
            vendorKeys.forEach { format.setInteger(it, 1) }
            val pictureOrderListed = listsPictureOrder(codec)
            val pictureOrder = attempt.tuned && atOnce && !vendorLowLatencyRefused &&
                (pictureOrderListed || submitsPictureOrderUnlisted(createdName))
            privateParameters = vendorKeys.isNotEmpty() || (pictureOrder && !pictureOrderListed)
            if (pictureOrder) format.setInteger(PICTURE_ORDER_PARAMETER, 1)
            if (vendorKeys.isNotEmpty()) {
                report("QTI low-latency attempt decoder=$codecName api=${Build.VERSION.SDK_INT} " +
                    "request=${vendorKeys.joinToString { "$it=1" }} pictureOrder=$pictureOrder")
            }
            codec.configure(format, surface, null, 0)
            phase = "start"
            phaseStartNs = System.nanoTime()
            codec.start()
            configuredVendorLowLatency = vendorKeys.isNotEmpty()
            configuredPrivateParameters = privateParameters
            if (configuredVendorLowLatency) {
                report("QTI low-latency configure/start returned decoder=$codecName; effectiveness=unverified")
            }
            startedLowLatency = lowLatency
            startedPictureOrder = pictureOrder
            val configuredName = runCatching { codec.name }.getOrNull().orEmpty()
                .ifEmpty { createdName }
                .ifEmpty { attempt.codecName ?: "default" }
            ConfiguredVideoDecoder(codec, configuredName, mtkMode)
        } catch (error: Exception) {
            val phaseMs = (System.nanoTime() - phaseStartNs) / 1_000_000
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
            runCatching { candidate?.release() }.onFailure {
                Log.w(TAG, "video decoder candidate release failed name=$codecName", it)
            }
            reportFailure("stage=configure phase=$phase phaseMs=$phaseMs elapsedMs=$elapsedMs " +
                "decoder=$codecName qtiRequest=${vendorKeys.joinToString()} " +
                "tuned=${attempt.tuned} operatingRate=${attempt.operatingRate} " +
                "mtkTuning=${mtkMode.diagnosticName} mime=$mime", error)
            Log.w(
                TAG,
                "video decoder configure failed name=$codecName phase=$phase phaseMs=$phaseMs elapsedMs=$elapsedMs " +
                    "tuned=${attempt.tuned} operatingRate=${attempt.operatingRate} " +
                    "mtkTuning=${mtkMode.diagnosticName} mime=$mime size=${width}x$height",
                error,
            )
            if (privateParameters) {
                vendorLowLatencyRefused = true
                onVendorLowLatencyRefused()
                report("QTI low-latency fallback decoder=$codecName phase=$phase; " +
                    "retry=same-format-without-private-parameters for this session")
                tryConfigure(mime, csd, surface, attempt, atOnce)
            } else null
        }
    }

    /**
     * [tryConfigure] for the native pipe: the same attempt, format keys and private-parameter fallback,
     * with the codec created and started by the pipe. A decoder's listed vendor parameters need a Java
     * instance (Android 12+), so only Qualcomm's OMX decoders get picture order here, unlisted.
     */
    private fun tryConfigureNative(
        pipe: NativeVideoPipe,
        mime: String,
        csd: List<ByteArray>,
        surface: Surface,
        attempt: DecoderAttempt,
        atOnce: Boolean,
    ): ConfiguredVideoDecoder? {
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
        val lowLatency = attempt.tuned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && requestedName != null &&
            runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == requestedName }
                    ?.getCapabilitiesForType(mime)?.isFeatureSupported("low-latency") == true
            }.getOrDefault(false)
        if (lowLatency) keys[MediaFormat.KEY_LOW_LATENCY] = 1
        val vendorKeys = if (attempt.tuned && vendorLowLatency && !vendorLowLatencyRefused) {
            vendorLowLatencyKeys(requestedName.orEmpty())
        } else emptyList()
        vendorKeys.forEach { keys[it] = 1 }
        val pictureOrder = attempt.tuned && atOnce && !vendorLowLatencyRefused && requestedName != null &&
            submitsPictureOrderUnlisted(requestedName)
        val privateParameters = vendorKeys.isNotEmpty() || pictureOrder
        if (pictureOrder) keys[PICTURE_ORDER_PARAMETER] = 1
        if (vendorKeys.isNotEmpty()) {
            report("QTI low-latency attempt decoder=$codecName api=${Build.VERSION.SDK_INT} " +
                "request=${vendorKeys.joinToString { "$it=1" }} pictureOrder=$pictureOrder")
        }
        val hevc = mime == MediaFormat.MIMETYPE_VIDEO_HEVC
        // The hardware decoder by name is retried as in [createDecoder]: its session is freed asynchronously.
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
            if (!privateParameters) return null
            vendorLowLatencyRefused = true
            onVendorLowLatencyRefused()
            report("QTI low-latency fallback decoder=$codecName phase=$phase; " +
                "retry=same-format-without-private-parameters for this session")
            return tryConfigureNative(pipe, mime, csd, surface, attempt, atOnce)
        }
        configuredVendorLowLatency = vendorKeys.isNotEmpty()
        configuredPrivateParameters = privateParameters
        if (configuredVendorLowLatency) {
            report("QTI low-latency configure/start returned decoder=$codecName; effectiveness=unverified")
        }
        startedLowLatency = lowLatency
        startedPictureOrder = pictureOrder
        return ConfiguredVideoDecoder(null, result.detail?.takeIf { it.isNotEmpty() } ?: codecName, mtkMode)
    }

    /** The decoder [createDecoder] would make for an unnamed attempt, by name; null for the platform's own choice. */
    private fun nativeDefaultDecoderName(mime: String): String? {
        if (prefersSoftware(mime)) {
            installedDecoders(mime).firstOrNull { it.software }?.let { return it.name }
        }
        if (hardwareCreateFailed) return null
        return defaultHardwareDecoder(installedDecoders(mime))
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

    /** Android 12 lets a codec list its vendor parameters. */
    private fun listsPictureOrder(codec: MediaCodec): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { PICTURE_ORDER_PARAMETER in codec.supportedVendorParameters }.getOrDefault(false)

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
        if (hardware == null || !isSoftwareDecoderName(codecName) || prefersSoftware(mime)) {
            if (hardware != null && codecName.equals(hardware, ignoreCase = true)) {
                hardwareProbeIntervalMillis = HardwareDecoderProbe.FIRST_INTERVAL_MILLIS
            }
            return
        }
        val probe = HardwareDecoderProbe.start(hardware, System.nanoTime(), hardwareProbeIntervalMillis)
        hardwareProbe = probe
        // A probe that finds the hardware free but whose decoder then fails again waits longer next time.
        hardwareProbeIntervalMillis = (hardwareProbeIntervalMillis * 2).coerceAtMost(HardwareDecoderProbe.MAX_INTERVAL_MILLIS)
        report("software decoder fallback decoder=$codecName hardware=$hardware unavailable; " +
            "next hardware check in ${probe.intervalMillis} ms")
    }

    /** Worker only: once the hardware decoder can be created again, rebuild the stream on it at the next keyframe. */
    private fun probeHardwareIfDue() {
        val probe = hardwareProbe ?: return
        if (!hasCodec()) { hardwareProbe = null; return }
        val now = System.nanoTime()
        if (now < probe.dueNanos) return
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
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    private fun prefersSoftware(mime: String): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mime == MediaFormat.MIMETYPE_VIDEO_HEVC && preferSoftwareHevcDecoder

    private fun createDecoder(mime: String): MediaCodec {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            preferSoftwareHevcDecoder
        ) {
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
            }
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software.name)
                } catch (error: Exception) {
                    Log.w(TAG, "software HEVC decoder unavailable name=${software.name}", error)
                }
            }
        }
        // Without a known hardware decoder (emulators, debugging boards) keep the platform's own choice.
        val hardware = defaultHardwareDecoder(installedDecoders(mime)) ?: return MediaCodec.createDecoderByType(mime)
        if (hardwareCreateFailed) throw java.io.IOException("hardware decoder $hardware unavailable")
        return try {
            createWithRetries(
                HARDWARE_CREATE_RETRY_MILLIS,
                sleep = Thread::sleep,
                onRetry = { attempt, failure ->
                    report("hardware decoder $hardware create failed attempt=$attempt " +
                        "${MediaFailureSummary.describe(failure)}; retrying")
                },
            ) { MediaCodec.createByCodecName(hardware) }
        } catch (failure: Exception) {
            hardwareCreateFailed = true
            // tryConfigure reports and swallows the failure; a close must still end the worker.
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            throw failure
        }
    }

    private fun localTimeOf(frame: VideoJob.Frame): Long =
        if (pacingDelay != null && frame.senderNanos > 0 && frame.arrivalNanos > 0) {
            pacer.localTime(frame.senderNanos, frame.arrivalNanos)
        } else 0L

    /** Completes [request] once the codec no longer renders to its surface, whatever happens here. */
    private fun detach(request: SurfaceDetachRequest): Unit = synchronized(outputLock) {
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

    private fun changeSurface(surface: Surface?): Unit = synchronized(outputLock) {
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
        referenceChain.reset()
        lastConfig?.let(::configureDecoder)
    }

    /** Mirrors: one decoder for all of them, through a fanout that also draws onto the stream's own surface. */
    private fun changeMirror(key: String, surface: Surface?): Unit = synchronized(outputLock) {
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
                    referenceChain.reset()
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
                referenceChain.reset()
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
        referenceChain.reset()
        runCatching(sharing::close)
        mirrors.clear()
    }

    /** Where the codec renders: the fanout while the stream has mirrors, else the stream's own surface. */
    private fun codecSurface(): Surface? = fanout?.input ?: outputSurface

    /** [frame] stays owned by the caller, which releases it afterwards. */
    private fun feed(frame: MediaPacket, receivedNs: Long, presentNs: Long = 0L) {
        val annexB = frame.annexBOrNull()
        val config = lastConfig ?: return
        if (codecSurface() == null) return
        if (annexB == null) { recover("invalid video access unit"); return }
        if (!referenceChain.accepts(annexB, config.codec)) {
            requestKeyFrameIfDue()
            return
        }
        if (decoder == null) configureDecoder(config)
        val codec = decoder ?: return
        // A slow rebuild must not immediately restart on its obsolete triggering IDR.
        if (referenceChain.needsKeyFrame && VideoRecoveryFrameAge.isObsolete(
                System.nanoTime(), receivedNs, queue.backlogAfterCurrent(),
            )) {
            queue.discardCurrentChain()
            backlogRecovery.reset()
            requestKeyFrameIfDue()
            return
        }
        // The queue overflowed behind this frame (a slow configure): its chain is gone, and the
        // pending Resync recovers, so do not spend the fresh codec on it.
        if (queue.resyncPending()) return
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(
                TAG,
                "video decoder first input avcc=${frame.size} annexB=${annexB.size} " +
                    "packet=${frame.javaClass.simpleName}",
            )
        }
        val index = DecoderInputPump.acquire(
            running = { running }, drain = { if (outputThread == null) drainOutput(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
        )
        if (index < 0) {
            dropVendorLowLatency("video decoder input stalled")
            dropMtkTuning("video decoder input stalled")
            dropMtkCodec2LowLatency("video decoder input stalled")
            recover("video decoder input stalled")
            return
        }
        val input = checkNotNull(codec.getInputBuffer(index)) { "Decoder input buffer unavailable" }
        input.clear()
        if (annexB.size <= input.remaining()) {
            annexB.copyInto(input)
            // A paced frame carries its local time; MediaCodec returns it with the decoded output.
            val presentationUs = if (presentNs > 0) presentNs / 1000 else System.nanoTime() / 1000
            // Recorded before queueing: the output thread may dequeue the frame as soon as it is queued.
            synchronized(outputLock) {
                val queuedNow = System.nanoTime()
                if (lastQueuedNanos != 0L && queuedNow - lastQueuedNanos > STILL_GAP_NS) resumedAtNanos = queuedNow
                lastQueuedNanos = queuedNow
                queuedPresentationUs[queuedSlot] = presentationUs
                queuedAtNanos[queuedSlot] = queuedNow
                queuedPaced[queuedSlot] = presentNs > 0
                queuedBeforePause[queuedSlot] = false
                if (presentNs > 0) {
                    // A gap between consecutive iPhone frame times, not a decoder backlog, marks a pause.
                    if (lastQueuedLocalNanos != 0L && presentNs - lastQueuedLocalNanos > SENDER_PAUSE_NS) {
                        recentPacedSlots.forEach { if (it >= 0) queuedBeforePause[it] = true }
                    }
                    lastQueuedLocalNanos = presentNs
                    recentPacedSlots.copyInto(recentPacedSlots, 1, 0, PAUSE_HELD_FRAMES - 1)
                    recentPacedSlots[0] = queuedSlot
                }
                queuedSlot = (queuedSlot + 1) % queuedPresentationUs.size
            }
            codec.queueInputBuffer(index, 0, annexB.size, presentationUs, 0)
            framesSinceConfigure++
            referenceChain.onQueued()
        } else {
            recover("video frame exceeded codec input capacity")
            return
        }
        if (outputThread == null) drainOutput(codec)
    }

    private fun recover(reason: String, keepUnusedDecoder: Boolean = false) {
        val keep = keepUnusedDecoder && decoder != null && framesSinceConfigure == 0
        Log.w(TAG, "Video recovery: $reason; waiting for keyframe")
        stats.onRecovery()
        report("recovery: $reason; waiting for keyframe" + if (keep) "; decoder kept, no frame queued yet" else "")
        // Recreate with codec-specific data: flush can discard CSD before the first output.
        if (!keep) releaseDecoder()
        queue.discardCurrentChain()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    /**
     * How long the worker may wait for the next job; see [videoWorkerWaitMillis].
     */
    private fun workerWaitMillis(): Long = videoWorkerWaitMillis(
        drainsOutput = outputThread == null && decoder != null,
        lastQueuedNanos = lastQueuedNanos,
        nowNanos = System.nanoTime(),
    )

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
        if (lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < 1_000_000_000L) return
        lastKeyFrameRequestNs = now
        requestKeyFrame()
    }

    private fun queuedSlotOf(presentationUs: Long): Int =
        queuedPresentationUs.indices.firstOrNull { queuedPresentationUs[it] == presentationUs } ?: -1

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            if (!handleOutput(codec, codec.dequeueOutputBuffer(info, 0), info)) return
        }
    }

    /** Handles one dequeueOutputBuffer result; false when there is nothing more to drain for now. */
    private fun handleOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo): Boolean {
        when {
            index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
            index >= 0 -> {
                val target = codecSurface()
                val render = target != null
                // Parked output goes to the offscreen consumer: it keeps the codec's state, but it is
                // not shown, so it is neither paced nor counted.
                val shown = render && target !== parking?.surface
                val now = System.nanoTime()
                val slot = queuedSlotOf(info.presentationTimeUs)
                val queued = if (slot >= 0) queuedAtNanos[slot] else 0L
                val heldOverGap = queued in 1 until resumedAtNanos
                if (queued > 0) stats.onDecodeLatency(now - queued, heldOverGap)
                if (queued > 0 && !heldOverGap) {
                    liveDecodeNanos.addAndGet(now - queued)
                    liveDecodeSamples.incrementAndGet()
                }
                val delay = pacingDelay
                val paced = shown && delay != null && slot >= 0 && queuedPaced[slot]
                val localNs = info.presentationTimeUs * 1000
                val pauseHeld = paced && queuedBeforePause[slot]
                val targetNs = if (paced && delay != null) {
                    if (!heldOverGap && !pauseHeld) delay.onFrame(now - localNs)
                    stats.onPacingDelay(delay.nanos)
                    localNs + delay.nanos
                } else 0L
                if (targetNs - now in 1..MAX_PACING_AHEAD_NS) {
                    codec.releaseOutputBuffer(index, targetNs)
                } else {
                    if (shown && delay != null && !heldOverGap && !pauseHeld) stats.onLate()
                    codec.releaseOutputBuffer(index, render)
                }
                if (shown) {
                    stats.onRendered()
                    liveRendered.incrementAndGet()
                }
                if (shown && !renderedFrameLogged) {
                    renderedFrameLogged = true
                    report("first frame rendered")
                    if (vendorLowLatency) report("QTI first output released for rendering " +
                        "qtiLowLatencySubmitted=$configuredVendorLowLatency effectiveness=unverified")
                    Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return false
            }
            else -> return false
        }
        return true
    }

    private fun logOutputFormat(format: MediaFormat) {
        if (vendorLowLatency) {
            report("QTI driver-reported output format " +
                "$QTI_LOW_LATENCY_PARAMETER=${format.intOrNull(QTI_LOW_LATENCY_PARAMETER) ?: "unreported"} " +
                "$PICTURE_ORDER_PARAMETER=${format.intOrNull(PICTURE_ORDER_PARAMETER) ?: "unreported"}; " +
                "effectiveness=unverified (reported values may be stale; unreported is not unsupported)")
        }
        report("output format requested=${width}x${height} " +
            "coded=${format.intOrNull(MediaFormat.KEY_WIDTH)}x${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
            "crop=${format.intOrNull("crop-left")},${format.intOrNull("crop-top")}," +
            "${format.intOrNull("crop-right")},${format.intOrNull("crop-bottom")} " +
            "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
            "color=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)}/${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)}/${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}")
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    private fun reportFailure(context: String, error: Throwable) {
        // A bad decoder can fail again on every frame; exported detail stays bounded per worker.
        if (failureReports >= 8) return
        failureReports++
        runCatching { report("decoder failed api=${Build.VERSION.SDK_INT} $context " +
            MediaFailureSummary.describe(error)) }
    }

    // Worker only; [outputLock] orders it against the output thread.
    private fun releaseDecoder() {
        backlogRecovery.reset()
        val codec: MediaCodec?
        synchronized(outputLock) {
            codec = decoder
            decoder = null
            // The output thread returns from dequeueOutputBuffer within its timeout and then sees no codec.
            awaitNotDequeuing(codec)
        }
        if (nativeCodecName != null) {
            nativeCodecName = null
            checkNotNull(pipe).release()
        }
        configuredRate = 0
        configuredMtkMode = MtkDecoderMode.NONE
        configuredMtkCodec2LowLatency = false
        configuredVendorLowLatency = false
        configuredPrivateParameters = false
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    /** Waits, holding [outputLock], until the output thread no longer waits on [codec]; keeps an interrupt. */
    private fun awaitNotDequeuing(codec: MediaCodec?) {
        if (codec == null) return
        var interrupted = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OUTPUT_EXIT_WAIT_MS)
        while (dequeuing === codec) {
            val leftMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (leftMillis <= 0) break
            try { outputLock.wait(leftMillis) } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun joinUninterruptibly(thread: Thread, timeoutMillis: Long) {
        var interrupted = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (thread.isAlive) {
            val leftMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (leftMillis <= 0) break
            try { thread.join(leftMillis) } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        // The output thread's wait for a decoded frame; a release waits at most this long for it to return.
        const val OUTPUT_TIMEOUT_US = 10_000L
        const val OUTPUT_IDLE_WAIT_MS = 10L
        const val OUTPUT_EXIT_WAIT_MS = 500L
        const val INPUT_TIMEOUT_US = 10_000L
        const val DEBUG_LOGS_RECHECK_NS = 1_000_000_000L
        // Waits before creating a refused hardware decoder again: 0.7 s in all, while frames queue up.
        val HARDWARE_CREATE_RETRY_MILLIS = listOf(100L, 200L, 400L)
        // A paced frame more than this ahead is treated as a bad mapping and shown at once.
        const val MAX_PACING_AHEAD_NS = 1_000_000_000L
        // Longer input gaps are a still screen, not decoding work.
        const val STILL_GAP_NS = VIDEO_STILL_GAP_NS
        // Consecutive frames this far apart in iPhone time are a pause in its frames: about four frame
        // intervals at 30 fps.
        const val SENDER_PAUSE_NS = 120_000_000L
        // Frames before a pause that wait for it: the decoder holds about two, sometimes three.
        const val PAUSE_HELD_FRAMES = 3
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

/** Use the existing hard backlog age only when a newer frame remains in this control segment. */
internal object VideoRecoveryFrameAge {
    fun isObsolete(nowNs: Long, receivedNs: Long, backlog: VideoDecodeQueue.Backlog): Boolean =
        nowNs >= receivedNs && nowNs - receivedNs >= 1_500_000_000L &&
            backlog.pendingFrames > 0 && backlog.newestPendingReceivedNs?.let {
                it <= nowNs && it - receivedNs >= 100_000_000L
            } == true
}

internal const val VIDEO_DRAIN_POLL_MS = 5L
internal const val VIDEO_IDLE_POLL_MS = 50L
internal const val VIDEO_STILL_GAP_NS = 500_000_000L

/**
 * The decoder worker's wait for its next job. When it drains a codec's output itself ([drainsOutput]: a
 * codec and no output thread) it polls every [VIDEO_DRAIN_POLL_MS] while the codec may still emit a
 * frame. The codec holds the last frames of a burst until later input arrives, so once input has
 * paused for a still-screen gap nothing comes out until the next frame, and that frame wakes the
 * queue at once. Otherwise only due checks (hardware probe, keyframe request, stats) run, every
 * [VIDEO_IDLE_POLL_MS].
 */
internal fun videoWorkerWaitMillis(drainsOutput: Boolean, lastQueuedNanos: Long, nowNanos: Long): Long =
    if (drainsOutput && lastQueuedNanos != 0L && nowNanos - lastQueuedNanos < VIDEO_STILL_GAP_NS) {
        VIDEO_DRAIN_POLL_MS
    } else VIDEO_IDLE_POLL_MS
