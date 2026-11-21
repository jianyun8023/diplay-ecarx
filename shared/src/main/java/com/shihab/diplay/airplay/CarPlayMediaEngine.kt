package com.shihab.diplay.airplay

import android.util.Log
import com.shihab.diplay.hud.NavigationOutputs
import com.shihab.diplay.media.NativeAudioScheduler
import com.shihab.diplay.transport.BlockingDuplexByteStream
import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.math.BigInteger
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Identity of one CarPlay audio stream: the stream type plus its CarPlay audio type. */
data class AudioStreamId(val type: Int, val audioType: String)

/**
 * Named facts of a screen stream's decode loop that drive session behavior. The decoder delivers
 * them through [MediaSink.setVideoEventHandler]; diagnostic text is formatting only, and no
 * consumer reparses it for these facts.
 */
sealed interface VideoStreamEvent {
    /** The stream's decoder rendered its first frame onto the current surface. */
    data object FirstFrameRendered : VideoStreamEvent
    /** A frame the session asked to sample finished rendering, with the decode loop's own fields. */
    data class FrameSampled(val sampledAt: Long, val epoch: Long, val decoderId: Int) : VideoStreamEvent
}

/** Rendering seam for the decrypted CarPlay media streams. */
interface MediaSink {
    fun requestVideoFrameSample(): Boolean = false
    fun onVideoCodec(type: Int, codec: VideoCodec) {}
    fun onVideoConfig(type: Int, codecData: ByteArray) {}
    fun onNativeVideoRoute(type: Int, route: NativeVideoRoute) {}
    fun onVideoReceiving(type: Int) {}
    fun onVideoFrame(type: Int, naluBytes: ByteArray) {}
    /** A frame with the iPhone's frame time and its arrival time (System.nanoTime); see [ScreenStream.Listener]. */
    fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) =
        onVideoFrame(type, naluBytes)
    /** A frame the sink now owns and releases once done with it; see [MediaPacket]. */
    fun onVideoFrame(type: Int, frame: MediaPacket, senderNanos: Long, arrivalNanos: Long) {
        try {
            onVideoFrame(type, frame.toByteArray(), senderNanos, arrivalNanos)
        } finally {
            frame.release()
        }
    }
    fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {}
    fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {}
    /** Named decode-loop facts of stream [type]; registered per SETUP, like the diagnostic handler. */
    fun setVideoEventHandler(type: Int, handler: (VideoStreamEvent) -> Unit) {}
    fun onScreenStreamActive(type: Int, active: Boolean) {}
    fun onNativeAudioRoute(id: AudioStreamId, format: AudioFormat, route: NativeAudioRoute) { route.disconnect() }
    fun onAudioQueueEvent(id: AudioStreamId, kind: Int, value: Long, extra: Long) {}
    fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {}
    fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {}
    /** An RTP packet the sink now owns and releases once done with it; see [MediaPacket]. */
    fun onAudioPacket(id: AudioStreamId, format: AudioFormat, rtp: MediaPacket, sample: Int) {
        try {
            onAudioRtp(id, format, rtp.toByteArray(), sample)
        } finally {
            rtp.release()
        }
    }
    fun onAudioStopped(id: AudioStreamId) {}
    fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {}
    fun onMicrophoneStopped(id: AudioStreamId) {}
    fun onIapMessage(bytes: ByteArray) {}
    /** The iPhone asked the car to duck its other audio for a navigation prompt (true), or to restore it. */
    fun onAudioDuck(ducked: Boolean) {}
}

/**
 * Concrete [AirPlayMediaHandler] that binds the screen, audio and iAP2 DataStream ports,
 * decrypts their payloads, and hands decoded media to a [MediaSink]. Telephony and speech
 * streams can additionally return a PCM microphone uplink through the sink.
 */
/**
 * Delivers a screen stream's data to [sink] only while [isCurrent]: a replaced stream's thread may still
 * be delivering when its successor starts. The check runs before each delivery, so a callback already
 * past it can still hand over one item; the successor's configuration and keyframe follow it. The iPhone
 * connects after SETUP returns the port, which is after the stream is registered as current.
 */
internal fun currentScreenListener(type: Int, sink: MediaSink, isCurrent: () -> Boolean) = object : ScreenStream.Listener {
    override fun onNativeVideoRoute(route: NativeVideoRoute) {
        if (isCurrent()) sink.onNativeVideoRoute(type, route) else route.disconnect()
    }
    override fun onReceiving() { if (isCurrent()) sink.onVideoReceiving(type) }
    override fun onCodec(codec: VideoCodec) { if (isCurrent()) sink.onVideoCodec(type, codec) }
    override fun onConfig(codecData: ByteArray) { if (isCurrent()) sink.onVideoConfig(type, codecData) }
    override fun onFrame(naluBytes: ByteArray) { if (isCurrent()) sink.onVideoFrame(type, naluBytes) }
    override fun onFrame(naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
        if (isCurrent()) sink.onVideoFrame(type, naluBytes, senderNanos, arrivalNanos)
    }
    override fun onFrame(frame: MediaPacket, senderNanos: Long, arrivalNanos: Long) {
        if (isCurrent()) sink.onVideoFrame(type, frame, senderNanos, arrivalNanos) else frame.release()
    }
}

class CarPlayMediaEngine(
    private val sink: MediaSink,
    private val microphoneEnabled: Boolean = false,
    private val audioCaptureDirectory: File? = null,
) : AirPlayMediaHandler {
    internal data class StreamKey(
        val session: AirPlaySession,
        val type: Int,
        val audioType: String = "",
    )

    private data class AudioMeta(
        val type: Int,
        val format: AudioFormat,
        val connectionId: Any?,
        val playoutLatencyMs: Int,
        /** The platform's start-callback fact: the stream reported its first decrypted sample. */
        @Volatile var started: Boolean = false,
    ) {
        @Volatile var route: NativeAudioRoute? = null
    }

    private data class PendingIapTunnel(
        val bridge: AirPlayIapTunnelStream,
        val handler: (BlockingDuplexByteStream) -> Boolean,
    )

    private val streams = ConcurrentHashMap<StreamKey, Closeable>()
    /** This engine's lease namespace: a replaced engine starts with an empty sharing registry. */
    internal val leaseEpoch = LEASE_EPOCHS.incrementAndGet()
    /**
     * The latest stream registered for each screen type. Two sessions can hold the same type at once
     * (their registry keys differ) while the sink's decoder, mirrors and saved configuration are shared
     * per type, so only the type's latest stream may deliver: this is the configuration-delivery
     * admission replaced native routes already have through their permanent Rust-side retirement.
     */
    private val screenOwners = ConcurrentHashMap<Int, ScreenStream>()
    private val audioMeta = ConcurrentHashMap<StreamKey, AudioMeta>()
    private val pendingMicrophone = ConcurrentHashMap<StreamKey, MicrophoneConfig>()
    private val audioCaptures = ConcurrentHashMap<StreamKey, AudioPacketCapture>()
    private val pendingIapTunnels = ConcurrentHashMap<AirPlaySession, PendingIapTunnel>()
    private val videoSettingsChannels = ConcurrentHashMap<AirPlaySession, VideoSettingsChannel>()
    private val bufferedStreams = ConcurrentHashMap<AirPlaySession, BufferedStream>()
    private val microphoneScheduler by lazy {
        ScheduledThreadPoolExecutor(1) { task -> Thread(task, "carplay-mic-start").apply { isDaemon = true } }.apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(MICROPHONE_SCHEDULER_IDLE_SECONDS, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }
    }

    private class BufferedStream(val stream: BufferedAudioStream, val connectionId: Any?)

    /** The session whose last duck command started a navigation prompt. */
    private val duckingSession = AtomicReference<AirPlaySession?>()

    // The sink has one type-103 renderer, even while old control sessions remain connected.
    // Never hold this lock when closing/controlling a stream: sink callbacks can reenter us.
    private val bufferedOwnershipLock = Any()
    private var bufferedOwner: AirPlaySession? = null
    private var bufferedTransition: BufferedTransition? = null
    private val bufferedCore = BufferedEngineCore()
    private val bufferedEligibility = WeakHashMap<AirPlaySession, Long>()
    private class BufferedTransition(val session: AirPlaySession, val candidate: BufferedStream?) {
        var retiring: BufferedStream? = null
    }

    private fun mayInstallBufferedLocked(session: AirPlaySession): Boolean {
        // A reentrant sink callback cannot wait for itself. Preserve its retirement barrier
        // until cleanup completes; the volatile flag requires no reverse stream-lock order.
        val retiring = bufferedTransition?.retiring
        val plan = bufferedCore.check(session.isClosed, bufferedEligibility[session] ?: bufferedCore.initialEligibility,
            retiring != null, retiring?.stream?.outputCleanupComplete == true)
        if (plan.releaseBarrier) bufferedTransition = null
        return plan.accepted
    }
    @Volatile private var iapTunnelHandler: ((BlockingDuplexByteStream) -> Boolean)? = null

    override fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) {
        iapTunnelHandler = handler
    }

    override fun requestVideoFrameSample(): Boolean = sink.requestVideoFrameSample()

    private fun acquireLease(session: AirPlaySession, kind: Int, type: Int, audioType: String) =
        NativeMedia.outputLeaseAcquire(leaseEpoch, session.leaseHolderId, kind, type, audioType)
    private fun releaseLease(session: AirPlaySession, kind: Int, type: Int, audioType: String): Boolean =
        NativeMedia.outputLeaseRelease(leaseEpoch, session.leaseHolderId, kind, type, audioType)

    override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? {
        // The screen SETUP shares the data-stream key derivation: the connection decimal and
        // label cross the boundary, the salt assembly stays in protocols::airplay.
        val parsed = NativeAirPlayProtocol.dataStreamSetupPlan(stream) ?: return null
        val shared = session.sharedSecret ?: return null
        val key = parsed.connectionIdDecimal
            ?.let { decimal -> NativeAirPlayProtocol.dataStreamKey(shared, decimal, DATASTREAM_OUTPUT_KEY) }
            ?: return null
        val peer = session.remoteAddress ?: return null
        val streamKey = StreamKey(session, type)
        Log.i(TAG, "airplay screen key connectionID=${parsed.connectionIdDecimal}")
        val screen = ScreenStream(key, peer, session::logDebug)
        // Two sessions can hold the same type at once, but the sink's decoder, mirrors and saved
        // configuration are shared per type. The registry check covers a same-session replacement;
        // the owner check also covers a different session taking the type over, whose decoders stay
        // in the sink.
        val isCurrent = { streams[streamKey] === screen && screenOwners[type] === screen }
        // The named decode-loop facts drive this session's behavior; the diagnostic handler below
        // only formats text (the cluster's decode lines and the session log).
        if (type == STREAM_TYPE_MAIN_SCREEN) sink.setVideoEventHandler(type) handler@{ event ->
            if (streams[streamKey] !== screen) return@handler
            when (event) {
                VideoStreamEvent.FirstFrameRendered -> session.videoFrameRendered()
                is VideoStreamEvent.FrameSampled -> session.videoFrameSample(event.sampledAt, event.decoderId, event.epoch)
            }
        }
        sink.setVideoDiagnosticHandler(type) {
            if (type == STREAM_TYPE_ALT_SCREEN && !session.isClosed && isCurrent()) {
                if (it == "first frame rendered") {
                    NavigationOutputs.diagnostic("cluster video stream=111 decoderFirstFrame=submitted")
                } else {
                    val format = CLUSTER_OUTPUT_FORMAT.find(it)
                    if (format != null) {
                        val (requestedWidth, requestedHeight, codedWidth, codedHeight, left, top, right, bottom) = format.destructured
                        NavigationOutputs.diagnostic("cluster video stream=111 decoderFormat requested=${requestedWidth}x$requestedHeight " +
                            "coded=${codedWidth}x$codedHeight crop=$left,$top,$right,$bottom")
                    }
                }
            }
            // Other screens are labeled: the report's display negotiation keeps only the main screen's lines.
            session.logDebug(if (type == STREAM_TYPE_MAIN_SCREEN) "Video: $it" else "Video stream=$type: $it")
        }
        sink.setVideoRecoveryHandler(type) {
            if (isCurrent()) {
                // The cluster asks for a keyframe of its own screen; the plain command is the main screen's.
                val sent = if (type == STREAM_TYPE_ALT_SCREEN) {
                    session.requestClusterKeyFrame()
                } else {
                    session.sendCommand(AirPlayCommands.keyFrame())
                }
                session.logDebug("Video recovery: requested keyframe stream=$type sent=$sent")
            }
        }
        val port = screen.listen(
            object : ScreenStream.Listener by currentScreenListener(type, sink, isCurrent = isCurrent) {
                override fun onClosed(cause: Throwable?) {
                    Log.w(
                        TAG,
                        "screen stream ended type=$type reason=${cause?.message ?: "peer EOF"}",
                    )
                    screenOwners.remove(type, screen)
                    if (streams.remove(streamKey, screen)) {
                        if (releaseLease(session, NativeMedia.LEASE_SCREEN, type, "")) {
                            sink.onScreenStreamActive(type, false)
                        }
                    }
                    session.close()
                }
            },
        )
        streams.put(streamKey, screen)?.close()
        acquireLease(session, NativeMedia.LEASE_SCREEN, type, "")
        screenOwners[type] = screen
        sink.onScreenStreamActive(type, true)
        return port
    }

    override fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? {
        val audioType = stream["audioType"]?.toString()?.lowercase() ?: "default"
        // Concurrent streams may share a type (e.g. music, guidance and Siri can all arrive as
        // type 100); only the same (type, audioType) pair replaces a previous stream.
        val streamKey = StreamKey(session, type, audioType)
        val streamId = AudioStreamId(type, audioType)
        // Replacement retires this session's own previous stream for the same output identity. The
        // shared-output stop is admitted only when this release is the last holder, so a re-SETUP by
        // one session never stops an output another live session still shares.
        streams.remove(streamKey)?.close()
        stopAudioOutputs(streamKey)

        // protocols::airplay interprets the request, derives the DataStream keys and plans the
        // microphone; the platform binds the sockets and constructs its own MicrophoneConfig.
        val plan = NativeAirPlayProtocol.audioSetupPlan(type, audioType, stream, session.sharedSecret, microphoneEnabled)
            ?: return null
        val key = plan.outputKey ?: return null
        val formatBits = plan.formatBits
        val micPort = plan.micPort
        val format = AudioStreamCodec.fromFormatBits(formatBits, type, audioType)
        Log.i(
            TAG,
            "airplay audio format type=$type audioType=$audioType codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "formatBits=0x${java.lang.Long.toHexString(formatBits)} micPort=$micPort",
        )
        val connectionId = stream["streamConnectionID"]
        val latencyMs = plan.latencyMs
        val meta = AudioMeta(type, format, connectionId, latencyMs)
        val microphone = microphoneConfig(session, plan.microphone, format)
        if (microphone != null) {
            pendingMicrophone[streamKey] = microphone
            acquireLease(session, NativeMedia.LEASE_MICROPHONE, type, audioType)
        }
        if (type == STREAM_TYPE_MAIN_AUDIO && audioType != "media") {
            session.logDebug(
                "Audio: setup type=$type audioType=$audioType input=${stream["input"] ?: "none"} " +
                    "micPort=${if (micPort > 0) "yes" else "no"} " +
                    "microphone=${if (microphone != null) "on" else "off"} keys=${stream.keys.sorted().joinToString(",")}" +
                    // An app call (WeChat, #718) carried vocoderInfo; the report showed only its name.
                    (stream["vocoderInfo"]?.let { " vocoderInfo=${it.toString().take(200)}" } ?: ""),
            )
        }

        val capture = audioCaptureDirectory?.let { AudioPacketCapture(it, type) }
        if (capture != null) audioCaptures[streamKey] = capture
        val microphoneStart = microphone?.let { config -> MicrophoneStart { sink.onMicrophoneStarted(streamId, config) } }
        // Native wiretap keeps capture bytes while disabling direct queue delivery.
        val audio = AudioStream(key, type, session::logDebug, preferNative = capture == null, clockRate = format.rtpClockRate)
        meta.route = audio.route
        streams[streamKey] = audio
        acquireLease(session, NativeMedia.LEASE_AUDIO, type, audioType)
        audioMeta[streamKey] = meta
        var listening = false
        val (dataPort, controlPort) = try { audio.listen(
            object : AudioStream.Listener {
                private fun current(): Boolean = streams[streamKey] === audio

                override fun onStarted(firstSample: Int) {
                    if (!current()) return
                    meta.started = true
                    audio.route.anchor(firstSample, System.nanoTime())
                    sink.onAudioStarted(streamId, format, firstSample)
                    sink.onNativeAudioRoute(streamId, format, audio.route)
                    microphoneStart?.onDownlink()
                }

                override fun onRtp(rtp: ByteArray, sample: Int) {
                    if (current()) sink.onAudioRtp(streamId, format, rtp, sample)
                }

                override fun onClock(hz: Int) {
                    if (current()) session.logDebug(
                        "Audio: rtp clock type=$type audioType=$audioType " +
                            "audioFormat=0x${java.lang.Long.toHexString(formatBits)} " +
                            "measuredHz=$hz microphoneClockHz=${microphone?.rtpClockRate ?: "none"}",
                    )
                }

                override fun onQueueEvent(kind: Int, value: Long, extra: Long) {
                    if (current()) sink.onAudioQueueEvent(streamId, kind, value, extra)
                }

                override fun onPacket(rtp: MediaPacket, sample: Int) {
                    if (current()) sink.onAudioPacket(streamId, format, rtp, sample) else rtp.release()
                }

                override fun onPacket(
                    wire: ByteArray,
                    rtp: ByteArray?,
                    sample: Int?,
                    error: Throwable?,
                ) {
                    if (current()) capture?.record(wire, rtp, sample, error)
                }
            },
        ).also { listening = true } } finally {
            if (!listening) {
                if (streams.remove(streamKey, audio)) releaseLease(session, NativeMedia.LEASE_AUDIO, type, audioType)
                if (pendingMicrophone.remove(streamKey) != null) releaseLease(session, NativeMedia.LEASE_MICROPHONE, type, audioType)
                audioMeta.remove(streamKey, meta)
                if (capture != null) audioCaptures.remove(streamKey, capture)
                capture?.close()
                audio.close()
            }
        }
        // The plan's wait-for-downlink flag travels with the microphone rules; telephony waits
        // for its echo-reference audio while other streams may start after the 500 ms deadline.
        val waitsForDownlink = plan.microphone?.waitDownlink == true
        if (microphoneStart != null && !waitsForDownlink) {
            microphoneStart.scheduleSilentStart(microphoneScheduler, MicrophoneStart.SILENT_START_MILLIS) { startNow ->
                // Atomic with TEARDOWN, which removes the pending entry before it stops the microphone.
                var started = false
                pendingMicrophone.computeIfPresent(streamKey) { _, pending ->
                    if (pending === microphone) started = startNow()
                    pending
                }
                if (started) {
                    session.logDebug(
                        "Audio: microphone started without downlink audio type=$type audioType=$audioType " +
                            "afterMs=${MicrophoneStart.SILENT_START_MILLIS}",
                    )
                }
            }
        }
        return NativeAirPlayProtocol.setupReply(
            NativeAirPlayProtocol.REPLY_AUDIO,
            type,
            0L,
            dataPort,
            controlPort,
            connectionId,
        )
    }

    override fun onBufferedAudio(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
        if (!synchronized(bufferedOwnershipLock) { mayInstallBufferedLocked(session) }) return null
        val type = BufferedAudioStream.STREAM_TYPE
        val streamKey = StreamKey(session, type, "media")
        val plan = NativeAirPlayProtocol.bufferedSetupPlan(stream)
        when (plan.failure) {
            NativeAirPlayProtocol.BufferedSetupFailure.KEY -> {
                Log.w(TAG, "buffered audio SETUP without a 32-byte shk; declined")
                return null
            }
            NativeAirPlayProtocol.BufferedSetupFailure.FORMAT -> {
                Log.w(TAG, "buffered audio format ${stream["audioFormat"]} is not AAC-LC; declined")
                return null
            }
            NativeAirPlayProtocol.BufferedSetupFailure.PARAMETERS -> {
                Log.w(TAG, "buffered audio requires AAC and 1024 samples per frame; declined")
                return null
            }
            NativeAirPlayProtocol.BufferedSetupFailure.NONE -> Unit
        }
        val key = checkNotNull(plan.key)
        val format = AudioStreamCodec.fromFormatBits(plan.formatBits, type, "media")
        val peer = session.remoteAddress ?: return null
        val buffered = try {
            BufferedAudioStream(key, format, sink, session::logDebug, expectedPeer = peer)
        } catch (error: Exception) {
            Log.w(TAG, "buffered audio listener failed", error)
            return null
        }
        // Validate before retiring a healthy owner. Detach its registry entries first, but
        // keep a transition barrier until close has finished its final sink callback.
        val candidate = BufferedStream(buffered, stream["streamConnectionID"])
        val transition = BufferedTransition(session, candidate)
        var previous: BufferedStream? = null
        val accepted = synchronized(bufferedOwnershipLock) {
            val retiring = bufferedTransition?.retiring
            val admission = bufferedCore.begin(session.isClosed, bufferedEligibility[session] ?: bufferedCore.initialEligibility,
                retiring != null, retiring?.stream?.outputCleanupComplete == true, bufferedOwner != null, bufferedOwner === session)
            if (admission.releaseBarrier) bufferedTransition = null
            if (!admission.accepted) false else {
                bufferedTransition = transition
                bufferedOwner?.let { oldSession ->
                    previous = detachBufferedLocked(oldSession)
                    admission.previousEligibility?.let { bufferedEligibility[oldSession] = it }
                }
                transition.retiring = previous
                true
            }
        }
        if (!accepted) {
            buffered.close()
            return null
        }
        val closeReturnedNormally = runCatching { previous?.stream?.close() }.isSuccess
        val outputCleanupComplete = previous?.stream?.outputCleanupComplete == true
        val installed = synchronized(bufferedOwnershipLock) {
            val result = bufferedCore.finishSetup(closeReturnedNormally, previous != null,
                outputCleanupComplete, bufferedTransition === transition, session.isClosed,
                bufferedEligibility[session] ?: bufferedCore.initialEligibility)
            if (result.install) {
                streams[streamKey] = buffered
                bufferedStreams[session] = candidate
                bufferedOwner = session
            }
            when (result.barrier) {
                BufferedEngineCore.Barrier.UNCHANGED -> Unit
                BufferedEngineCore.Barrier.CLEAR -> bufferedTransition = null
                BufferedEngineCore.Barrier.RETIREMENT_ONLY -> bufferedTransition = BufferedTransition(session, null).also { it.retiring = previous }
            }
            result.install
        }
        if (!installed) {
            buffered.close()
            return null
        }
        buffered.start()
        if (bufferedStreams[session] !== candidate) return null
        Log.i(TAG, "buffered audio stream client=${stream["clientID"]} rate=${format.sampleRate} port=${buffered.port}")
        return NativeAirPlayProtocol.bufferedSetupReply(buffered.port)
    }

    override fun onBufferedAudioControl(
        session: AirPlaySession,
        method: String,
        body: Map<String, Any?>,
    ): Map<String, Any?>? {
        val buffered = synchronized(bufferedOwnershipLock) { bufferedStreams[session]?.stream } ?: return null
        val plan = BufferedAudioControlPolicy.plan(method, body)
        return when (plan.action) {
            BufferedControlAction.SET_RATE -> buffered.setRate(plan.timestamp, plan.rate, session.syncedNtp())
            BufferedControlAction.GET_ANCHOR -> buffered.anchor()
            BufferedControlAction.FLUSH -> {
                buffered.flush(plan.timestamp)
                null
            }
            BufferedControlAction.NONE -> null
        }
    }

    override fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
        val uuid = (stream["clientTypeUUID"] as? String)?.uppercase() ?: return null
        val parsed = NativeAirPlayProtocol.dataStreamSetupPlan(stream) ?: return null
        if (session.videoInCar) videoDataStream(session, uuid, parsed, stream)?.let { return it }
        if (uuid != IAP_DATASTREAM_UUID) return null
        val shared = session.sharedSecret ?: return null
        val seed = parsed.seed ?: return null
        val peer = session.remoteAddress ?: return null
        session.logDebug(
            "AirPlay iAP SETUP uuid=$uuid seed=$seed " +
                "streamConnectionID=${parsed.connectionIdDecimal ?: "none"}",
        )
        val key = NativeAirPlayProtocol.dataStreamKey(shared, seed, DATASTREAM_OUTPUT_KEY) ?: return null
        val tunnel = IapTunnel(
            readKey = key,
            expectedPeer = peer,
            bindAddress = session.localAddress
                ?: when (session.remoteAddress) {
                    is Inet6Address -> InetAddress.getByName("::")
                    is Inet4Address -> InetAddress.getByName("0.0.0.0")
                    else -> InetAddress.getByName("0.0.0.0")
                },
        )
        val bridge = AirPlayIapTunnelStream(session, tunnel)
        val handler = iapTunnelHandler
        val port = try {
            if (handler != null) {
                val boundPort = bridge.listen()
                session.logDebug(
                    "AirPlay iAP tunnel listening address=" +
                        "${session.localAddress?.hostAddress ?: "wildcard"} port=$boundPort",
                )
                replacePendingIapTunnel(session, PendingIapTunnel(bridge, handler))
                boundPort
            } else {
                tunnel.listen(
                    object : IapTunnel.Listener {
                        override fun onIap(bytes: ByteArray) = sink.onIapMessage(bytes)

                        override fun onClosed(cause: Throwable?) {
                            Log.w(
                                TAG,
                                "iAP tunnel ended reason=${cause?.message ?: "peer EOF"}",
                            )
                            session.close()
                        }
                    },
                )
            }
        } catch (error: Throwable) {
            bridge.close()
            throw error
        }
        streams[StreamKey(session, STREAM_TYPE_DATA)] = if (handler != null) bridge else tunnel
        return NativeAirPlayProtocol.setupReply(
            NativeAirPlayProtocol.REPLY_DATA,
            STREAM_TYPE_DATA,
            1L,
            port,
            0,
            stream["streamConnectionID"],
        )
    }

    /**
     * Video in car: the settings channel gets its own encrypted socket; the remote control sessions that
     * carry playback have none (controlType 1), their messages arrive as commands with X-Apple-StreamID.
     */
    private fun videoDataStream(
        session: AirPlaySession,
        uuid: String,
        parsed: NativeAirPlayProtocol.DataStreamSetupPlan,
        stream: Map<String, Any?>,
    ): Map<String, Any?>? {
        if (uuid in VideoInCar.REMOTE_CONTROL_UUIDS && parsed.controlType == 1) {
            val streamId = session.nextRemoteControlStreamId()
            session.logTrace("video remote-control stream accepted uuid=$uuid streamID=$streamId")
            return NativeAirPlayProtocol.setupReply(
                NativeAirPlayProtocol.REPLY_REMOTE_CONTROL,
                STREAM_TYPE_DATA,
                streamId,
                0,
                0,
                null,
            )
        }
        if (uuid != VideoInCar.SETTINGS_CHANNEL_UUID) return null
        val shared = session.sharedSecret ?: return null
        val seed = parsed.seed ?: return null
        val peer = session.remoteAddress ?: return null
        val outputKey = NativeAirPlayProtocol.dataStreamKey(shared, seed, DATASTREAM_OUTPUT_KEY) ?: return null
        val inputKey = NativeAirPlayProtocol.dataStreamKey(shared, seed, DATASTREAM_INPUT_KEY) ?: return null
        val channel = VideoSettingsChannel(outputKey, inputKey, peer) { session.logDebug(it) }
        val port = channel.listen(session.localAddress ?: InetAddress.getByName("::"))
        videoSettingsChannels.put(session, channel)?.close()
        session.logTrace("video settings stream listening port=$port")
        return NativeAirPlayProtocol.setupReply(
            NativeAirPlayProtocol.REPLY_DATA,
            STREAM_TYPE_DATA,
            VIDEO_SETTINGS_STREAM_ID,
            port,
            0,
            stream["streamConnectionID"],
        )
    }

    override fun onSetupResponseSent(session: AirPlaySession) {
        val pending = pendingIapTunnels.remove(session) ?: return
        val attached = try {
            pending.handler(pending.bridge)
        } catch (error: Throwable) {
            Log.w(TAG, "iAP tunnel relay attachment failed", error)
            false
        }
        if (!attached) {
            Log.w(TAG, "iAP tunnel relay attachment was rejected after SETUP")
            pending.bridge.close()
            session.close()
        }
    }

    override fun onFeedback(session: AirPlaySession): Map<String, Any?>? {
        val active = audioMeta.entries.filter { it.key.session === session }.map { it.value }
        val buffered = bufferedStreams[session]
        if (active.isEmpty() && buffered == null) return null
        val streams = active.map { meta ->
            // sampleTime counts on the stream's RTP clock, never the decode rate: the #473
            // note travels with the native calculation. Clock reads keep their count and order.
            if (!meta.started) {
                linkedMapOf<String, Any?>("type" to meta.type, "sampleRate" to meta.format.rtpClockRate)
            } else {
                val nowNs = System.nanoTime()
                val entry = meta.route?.feedbackEntry(
                    meta.type,
                    nowNs,
                    meta.playoutLatencyMs,
                    unsignedPlistInteger(meta.connectionId ?: 0L),
                    session.syncedNtp(),
                ) ?: linkedMapOf("type" to meta.type, "sampleRate" to meta.format.rtpClockRate)
                (entry["sampleTime"] as? Long)?.let { sampleTime ->
                    meta.route?.feedback(sampleTime, nowNs)?.let { row ->
                        session.logDebug("Audio: feedback type=${meta.type} id=${entry["streamConnectionID"] ?: 0} " +
                            "reported=$sampleTime received=${row[0]} aheadMs=${row[1]}" +
                            (if (row[2] != 0L) " overrun=true" else "") +
                            " maxAheadMs=${row[3]} overrunSamples=${row[4]} overrunObservedSpanMs=${row[5]} sinceNewestRxMs=${row[6]}")
                    }
                }
                entry
            }
        } + listOfNotNull(buffered?.let { it.stream.feedback(session.syncedNtp(), it.connectionId) })
        return linkedMapOf("streams" to streams)
    }

    override fun onTeardown(session: AirPlaySession, type: Int) {
        if (type == STREAM_TYPE_DATA) clearPendingIapTunnel(session)
        if (type == BufferedAudioStream.STREAM_TYPE) {
            retireBuffered(session)
            return
        }
        // TEARDOWN carries only the stream type; release every audioType variant of it.
        val tornDown = streams.keys.filter { it.session === session && it.type == type }
        // Detach and close each source first: a shared output must stop receiving packets before its
        // sink is asked to stop, so a final flush lands on a still-live renderer.
        tornDown.forEach { key ->
            val removed = streams.remove(key)
            if (removed is ScreenStream) screenOwners.remove(key.type, removed)
            removed?.close()
        }
        // Then release output leases. Only an audio stream holds an audio/microphone lease, so screen
        // and data teardowns never emit a spurious audio stop; and only the last holder may stop it.
        tornDown.forEach { key ->
            if (!isScreenStreamType(key.type) && key.type != STREAM_TYPE_DATA) {
                stopAudioOutputs(key)
            }
        }
        if (isScreenStreamType(type) && releaseLease(session, NativeMedia.LEASE_SCREEN, type, "")) {
            sink.onScreenStreamActive(type, false)
        }
    }

    override fun onAudioDuck(session: AirPlaySession, ducked: Boolean) {
        val released = if (ducked) { duckingSession.set(session); false } else duckingSession.compareAndSet(session, null)
        if (NativeAudioScheduler.duckOutputMayNotify(ducked, sessionClosing = false, released, duckingSession.get() != null)) {
            sink.onAudioDuck(ducked)
        }
    }

    override fun onSessionClosed(session: AirPlaySession) {
        // A phone that disconnects during a prompt never sends unduckAudio.
        val released = duckingSession.compareAndSet(session, null)
        if (NativeAudioScheduler.duckOutputMayNotify(ducked = false, sessionClosing = true, released, duckingSession.get() != null)) {
            sink.onAudioDuck(false)
        }
        clearPendingIapTunnel(session)
        retireBuffered(session)
        videoSettingsChannels.remove(session)?.close()
        val sessionStreams = streams.keys.filter {
            it.session === session && it.type != BufferedAudioStream.STREAM_TYPE
        }
        // Detach and close every source first, then release leases, so a final flush lands on a live
        // renderer and only the last holder stops a shared sink (same order as teardown).
        sessionStreams.forEach {
            val removed = streams.remove(it)
            if (removed is ScreenStream) screenOwners.remove(it.type, removed)
            removed?.close()
        }
        sessionStreams
            .filter { isScreenStreamType(it.type) }
            .forEach { key ->
                if (releaseLease(session, NativeMedia.LEASE_SCREEN, key.type, "")) {
                    sink.onScreenStreamActive(key.type, false)
                }
            }
        // A session that ends without TEARDOWN (a wireless restart) must still release its renderers,
        // or they keep their codec, track and focus until the sink closes. Renderers are keyed by stream
        // id only, so one a newer session already uses stays.
        sessionStreams
            .filter { !isScreenStreamType(it.type) && it.type != STREAM_TYPE_DATA }
            .forEach(::stopAudioOutputs)
        audioMeta.keys.filter { it.session === session }.forEach { audioMeta.remove(it) }
        pendingMicrophone.keys.filter { it.session === session }.forEach { key ->
            if (pendingMicrophone.remove(key) != null) releaseLease(session, NativeMedia.LEASE_MICROPHONE, key.type, key.audioType)
        }
        audioCaptures.entries.filter { it.key.session === session }.forEach { (key, capture) ->
            if (audioCaptures.remove(key, capture)) capture.close()
        }
    }

    /** The packet source has stopped. Retire an unshared phone track before capture can block. */
    private fun stopAudioOutputs(key: StreamKey) {
        val id = AudioStreamId(key.type, key.audioType)
        val stopMicrophone = pendingMicrophone.remove(key) != null &&
            releaseLease(key.session, NativeMedia.LEASE_MICROPHONE, key.type, key.audioType)
        val stopAudio = releaseLease(key.session, NativeMedia.LEASE_AUDIO, key.type, key.audioType)
        audioMeta.remove(key)
        audioCaptures.remove(key)?.close()
        if (key.audioType == "telephony") {
            try {
                if (stopAudio) sink.onAudioStopped(id)
            } finally {
                if (stopMicrophone) sink.onMicrophoneStopped(id)
            }
        } else {
            if (stopMicrophone) sink.onMicrophoneStopped(id)
            if (stopAudio) sink.onAudioStopped(id)
        }
    }

    /** Removes ownership before a close that may block on delivery or invoke the sink. */
    private fun detachBufferedLocked(session: AirPlaySession): BufferedStream? {
        val previous = bufferedStreams.remove(session)
        previous?.let { streams.remove(StreamKey(session, BufferedAudioStream.STREAM_TYPE, "media"), it.stream) }
        if (bufferedOwner === session) bufferedOwner = null
        return previous
    }

    private fun retireBuffered(session: AirPlaySession) {
        var pending: BufferedStream? = null
        var retirement: BufferedTransition? = null
        val previous = synchronized(bufferedOwnershipLock) {
            val plan = bufferedCore.retire(bufferedTransition?.session === session,
                bufferedTransition?.candidate != null, bufferedStreams.containsKey(session))
            // The SETUP thread still owns the barrier while it closes the earlier owner.
            if (plan.closeCandidate) pending = bufferedTransition?.candidate
            detachBufferedLocked(session).also {
                if (plan.createBarrier) {
                    retirement = BufferedTransition(session, null).also { barrier -> barrier.retiring = it }
                    bufferedTransition = retirement
                }
            }
        }
        try {
            pending?.stream?.close()
            previous?.stream?.close()
        } finally {
            synchronized(bufferedOwnershipLock) {
                if (bufferedCore.finishRetire(retirement != null && bufferedTransition === retirement,
                        previous != null, previous?.stream?.outputCleanupComplete == true)) bufferedTransition = null
            }
        }
    }

    private fun replacePendingIapTunnel(session: AirPlaySession, next: PendingIapTunnel) {
        val previous = pendingIapTunnels.put(session, next)
        previous?.bridge?.close()
    }

    private fun clearPendingIapTunnel(session: AirPlaySession? = null) {
        if (session == null) {
            val pending = pendingIapTunnels.values.toList()
            pendingIapTunnels.clear()
            pending.forEach { it.bridge.close() }
            return
        }
        pendingIapTunnels.remove(session)?.bridge?.close()
    }

    /**
     * The platform half of the Rust microphone plan: the rule, port, key and parameters already
     * live in protocols::airplay; only the InetAddress and the DTO are built here.
     */
    private fun microphoneConfig(
        session: AirPlaySession,
        mic: NativeAirPlayProtocol.MicrophonePlan?,
        format: AudioFormat,
    ): MicrophoneConfig? {
        if (mic == null) return null
        val host = session.remoteAddress ?: return null
        val key = mic.inputKey ?: return null
        return MicrophoneConfig(
            audioType = format.audioType,
            sampleRate = format.sampleRate,
            channels = format.channels,
            payloadType = format.payloadType,
            frameMillis = mic.frameMillis,
            host = host,
            port = mic.port,
            key = key,
            codec = format.codec,
            bitrate = mic.bitrate,
            opusClockRate = mic.opusClockRate,
        )
    }

    private fun isScreenStreamType(type: Int): Boolean =
        type == STREAM_TYPE_MAIN_SCREEN || type == STREAM_TYPE_ALT_SCREEN

    internal companion object {
        private val LEASE_EPOCHS = java.util.concurrent.atomic.AtomicLong()
        private val CLUSTER_OUTPUT_FORMAT = Regex(
            "^output format requested=(\\d{1,5})x(\\d{1,5}) " +
                "coded=(null|-?\\d{1,10})x(null|-?\\d{1,10}) " +
                "crop=(null|-?\\d{1,10}),(null|-?\\d{1,10}),(null|-?\\d{1,10}),(null|-?\\d{1,10})(?= |$)",
        )
        private const val TAG = "diplay-usb"
        private const val MICROPHONE_SCHEDULER_IDLE_SECONDS = 5L
        private const val STREAM_TYPE_MAIN_SCREEN = 110
        private const val STREAM_TYPE_ALT_SCREEN = 111
        private const val STREAM_TYPE_MAIN_AUDIO = 100
        private const val STREAM_TYPE_DATA = 130
        private const val DATASTREAM_OUTPUT_KEY = "DataStream-Output-Encryption-Key"
        private const val DATASTREAM_INPUT_KEY = "DataStream-Input-Encryption-Key"
        private const val IAP_DATASTREAM_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"
        private const val VIDEO_SETTINGS_STREAM_ID = 2L
    }
}

/**
 * The reply-side unsigned representation for one plist connection value, kept next to the
 * feedback and buffered owners that still encode it on the platform. The SETUP interpretation
 * and reply layout live in protocols::airplay::setup.
 */
internal fun unsignedPlistInteger(value: Any?): Any = when (value) {
    is Long -> if (value < 0) BigInteger(java.lang.Long.toUnsignedString(value)) else value
    is Int -> if (value < 0) BigInteger(Integer.toUnsignedString(value)) else value
    else -> value ?: 0L
}
