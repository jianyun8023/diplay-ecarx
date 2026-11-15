package com.shihab.diplay.media

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shihab.diplay.airplay.AudioCodecKind
import com.shihab.diplay.airplay.AudioFormat
import com.shihab.diplay.airplay.HeapPacket
import com.shihab.diplay.airplay.MediaPacket
import com.shihab.diplay.airplay.toHexString
import java.io.Closeable
import java.nio.ByteBuffer

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
internal class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val audioFocusEnabled: Boolean,
    private val mediaChannel: Int,
    initialNavigationChannel: Int,
    private val audioFocusCoordinator: AudioFocusCoordinator,
    private val navigationStreamType: Int,
    private val mediaBufferMillis: Int,
    private val report: (String) -> Unit,
    private val speakerphoneCall: Boolean = false,
    /** Receives the played call audio so the call microphone can cancel its echo. */
    private val echoReference: EchoReference? = null,
    voiceFilter: Boolean = false,
    private val navigationOemContent: Boolean = false,
    private val outputDeviceResolver: ((Int) -> android.media.AudioDeviceInfo?)? = null,
) : Closeable {
    /** The configured navigation channel, or the route chosen for the current prompt ([navigationRouteWanted]). */
    @Volatile private var navigationChannel = initialNavigationChannel
    val navigationRoute: Int get() = navigationChannel
    /**
     * The route the next prompt wants, read from this worker. The iPhone keeps a navigation stream open
     * for up to 32 s, and AMap asks to lower the music for some prompts but not others, so one stream
     * carries both kinds (car test 2026-11-07); the track is rebuilt on the wanted route between prompts.
     */
    @Volatile var navigationRouteWanted: (() -> Int)? = null
    @Volatile var onNavigationRouteChanged: (Int) -> Unit = {}
    var debugLogsEnabled: () -> Boolean = { false }
    /** The sink's iPhone-duck and prompt-focus state, for the per-prompt log line. */
    var navigationPromptState: () -> String = { "" }
    /** This navigation track started (true) or stopped (false) playing sound; always reported in pairs. */
    var onNavigationAudio: (Boolean) -> Unit = {}
    private val navigationAudioLock = Any()
    @Volatile private var navigationAudible = false
    private var navigationAudioEnded = false
    @Volatile private var lastAudibleNs = 0L
    /** A queued RTP packet; whoever takes it off the queue releases [rtp]. */
    private class AudioPacket(val rtp: MediaPacket, val sample: Int, val arrivedNs: Long)

    private val pcmChannels = if (format.channels >= 2) 2 else 1
    private val voiceFilterEnabled = voiceFilter && format.sampleRate > 0
    /** Created and closed by the output worker; PCM storage belongs to Rust. */
    private var nativePcm: NativePcm? = null

    private var trackAttributes: AudioAttributes? = null
    private var mappedChannel: AudioChannel? = null
    private val navigationPlaybackToken = NavigationPlayback.open()
    private var actualLegacyStreamType: Int? = null
    private val queueDropSnapshot = LongArray(1)
    private val queueStats = LongArray(5)
    private val nativeQueue = NativeAudioQueue.create(format.rtpClockRate, format.audioType == "media")
    private val queuedPacketCount: Int get() = nativeQueue.size
    @Volatile private var running = true
    @Volatile private var started = false
    @Volatile private var workerFailed = false

    /** The worker stopped on its own failure; this renderer can never start or play again. */
    internal val isTerminal: Boolean get() = workerFailed
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    // AAudio in place of [track] for a stream routed by usage, when the native PCM path runs.
    private var nativeOut: NativeAudioOut? = null
    private var trackOutput: TrackOutput? = null
    /** Where the worker plays: the AAudio output when open, else [track] (which tests may set directly). */
    private val output: PcmOutput?
        get() = nativeOut ?: track?.let { current ->
            trackOutput?.takeIf { it.track === current } ?: TrackOutput(current).also { trackOutput = it }
        }
    @Volatile private var navigationAttenuationDb = 0
    private var appliedNavigationAttenuationDb: Int? = null
    private var packetKind = ""
    private val playbackStarted: Boolean get() = bufferProgress.playing
    private val prebufferBytes: Int get() = bufferProgress.prebufferBytes
    private val startThresholdBytes: Int get() = bufferProgress.startBytes
    private var fadeApplied = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var shortOpusPackets = 0
    private var decoderUnavailablePackets = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private val frameBytes = if (format.channels >= 2) 4 else 2
    private var totalWrittenFrames = 0L
    private var writtenFramesThisWindow = 0L
    private var writeErrorsThisWindow = 0
    private var lastWriteErrorCode: Int? = null
    private var zeroWritesThisWindow = 0
    private var partialWritesThisWindow = 0
    private var lastPlaybackHeadFrames: Long? = null
    private var maxWriteMs = 0L
    private var statsWindowStartNs = 0L
    private var statsLastUnderruns = 0
    private var bytesPerSecond = 0
    // Only the worker uses this owner; a renderer closed before start must not allocate it.
    private val bufferProgress by lazy(LazyThreadSafetyMode.NONE) {
        AudioBufferProgress(if (format.channels >= 2) 4 else 2)
    }
    private var underrunsAtPlaybackStart = 0
    private var headAtPlaybackStart = 0
    private var droppedAtLastTrim = 0
    private var clockTrim: MediaClockTrim? = null
    /** Called when this call's track is routed to the earpiece; the sink moves the call to the loudspeaker. */
    @Volatile var onRoutedToEarpiece: (() -> Unit)? = null
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null
    private var rebufferCount = 0
    private var rebufferPauseReportCount = 0
    private var rebufferRecoverReportCount = 0
    private var diagnosticStage = "starting"
    private var lastDecoderOutputMetadata: String? = null
    private var decoderOutputReports = 0
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    /** Called after the output worker has released all of its platform resources. */
    internal var onExit: (AudioRenderer) -> Unit = {}

    @Synchronized
    fun start() {
        if (started || !running) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) = submit(HeapPacket(rtp), sample)

    internal fun retainNativeQueue(): Long = if (started && running) nativeQueue.retain() else 0L

    /** Takes ownership of [rtp]. Capture and non-native sinks retain the packet callback path. */
    fun submit(rtp: MediaPacket, sample: Int) {
        if (!started || !running) { rtp.release(); return }
        val result = nativeQueue.push(rtp, sample, System.nanoTime())
        if (result and 1 == 0 || result ushr 1 != 0) {
            val event = LongArray(3)
            while (nativeQueue.event(event)) queueEvent(event[0].toInt(), event[1], event[2])
        }
        if (!running) releaseQueued()
    }

    /** Queue report limits and counts are owned by Rust, including direct receiver pushes. */
    internal fun queueEvent(kind: Int, value: Long, extra: Long) {
        when (kind) {
            1 -> report("Audio: stale media packet backlog recovered audioType=${format.audioType} " +
                "packetsDiscarded=$value remaining=$extra")
            2 -> {
                if (extra != 0L) Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
                if (value > 0L) report("Audio: queue full audioType=${format.audioType} count=$value")
            }
        }
    }

    private fun releaseQueued() {
        nativeQueue.close()
    }

    override fun close() {
        running = false
        nativeQueue.close()
        if (!started) echoReference?.close()
        NavigationPlayback.close(navigationPlaybackToken)
        reportNavigationAudio(false, ended = true)
        thread.interrupt()
    }

    /**
     * Reports each change between sound and silence once. After [ended] (close, or the worker
     * stopping) a late write from the worker cannot start a new prompt that nobody would end.
     */
    private fun reportNavigationAudio(audible: Boolean, ended: Boolean = false) {
        val notify = synchronized(navigationAudioLock) {
            if (ended) navigationAudioEnded = true
            if (audible == navigationAudible || (audible && navigationAudioEnded)) false
            else { navigationAudible = audible; true }
        }
        if (!notify) return
        runCatching { onNavigationAudio(audible) }
        // One line per prompt: whether the iPhone asked to duck the music and whether the prompt
        // plays with its own focus. The session log's repeat limiter never drops it.
        if (audible) runCatching { report("Audio: navigation prompt start ${navigationPromptState()} " +
            "usage=${trackAttributes?.usage} contentType=${trackAttributes?.contentType}") }
    }

    /** Called from the worker loop: silence long enough to have played out ends the prompt. */
    private fun endNavigationSoundIfSilent() {
        if (navigationAudible && System.nanoTime() - lastAudibleNs >= NAVIGATION_SILENCE_NS) {
            reportNavigationAudio(false)
        }
    }

    private fun run() {
        val nativePacketInfo = LongArray(3)
        var consumer: NativeAudioQueue.Consumer? = null
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
        try {
            val reader = nativeQueue.consumer() ?: return
            consumer = reader
            runCatching { report("Audio: starting api=${Build.VERSION.SDK_INT} " +
                "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} channels=${format.channels} " +
                "mapping=${if (advancedAudioChannelMapping) "automotive" else "mobile"} " +
                "mediaChannel=$mediaChannel navigationChannel=$navigationChannel navigationOemContent=$navigationOemContent focus=$audioFocusEnabled") }
            val native = NativePcm.create(format.sampleRate, format.channels, voiceFilterEnabled, format.codec)
            nativePcm = native
            val configuration = native.configuration()
            if (configuration != null) configureCodec(configuration)
            else if (format.codec == AudioCodecKind.OPUS) {
                runCatching { report("Audio: decoder ready audioType=${format.audioType} codec=${format.codec} name=libopus") }
            }
            createTrack()
            diagnosticStage = "focus"
            requestAudioFocus()
            while (running) {
                // Report Settings changes from this worker, never from the UI thread, also between prompts.
                applyNavigationAttenuation()
                diagnosticStage = "packet"
                val packet = reader.poll(nativePacketInfo, AUDIO_POLL_MILLIS)
                if (packet != null) handle(AudioPacket(packet, nativePacketInfo[0].toInt(), nativePacketInfo[1]))
                // Output becomes ready asynchronously, including after the last packet of a burst.
                // Waiting for the next UDP packet can strand decoded sound for hundreds of ms.
                diagnosticStage = "decoder-output"
                codec?.let(::drainCodec)
                diagnosticStage = "buffer-maintenance"
                maintainPlaybackBuffer()
                diagnosticStage = "stats"
                logStatsIfDue()
                endNavigationSoundIfSilent()
                switchNavigationRouteIfWanted()
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) {
                workerFailed = true
                Log.e(TAG, "audio renderer worker failed", error)
                reportFailure(error)
            }
        } catch (error: LinkageError) {
            if (running) {
                workerFailed = true
                reportFailure(error)
            }
            throw error
        } finally {
            running = false
            // A worker that stops on an error must still end its prompt focus.
            reportNavigationAudio(false, ended = true)
            runCatching { logStatsIfDue(force = true) }
            try { release() } finally {
                try {
                    try { consumer?.close() } finally { echoReference?.close() }
                } finally { runCatching { onExit(this) } }
            }
        }
    }

    /** Only while no prompt plays: the old track then holds the iPhone's digital silence at most. */
    private fun switchNavigationRouteIfWanted() {
        val wanted = navigationRouteWanted?.let { runCatching(it).getOrNull() } ?: return
        if (wanted == navigationChannel || mappedChannel != AudioChannel.NAVIGATION || navigationAudible || output == null) return
        diagnosticStage = "navigation-route"
        val from = navigationChannel
        releaseTrack()
        navigationChannel = wanted
        bufferProgress.reset()
        fadeApplied = false
        appliedNavigationAttenuationDb = null
        createTrack()
        runCatching {
            report("Audio: navigation route=${NavigationAudioRoute.label(wanted)} " +
                "from=${NavigationAudioRoute.label(from)} switchedBetweenPrompts=true")
        }
        runCatching { onNavigationRouteChanged(wanted) }
    }

    private fun configureCodec(configuration: NativePcm.Configuration) {
        val mime = configuration.mime
        diagnosticStage = "decoder-format"
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (configuration.adts) setInteger(MediaFormat.KEY_IS_ADTS, 1)
            configuration.csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${configuration.csd[0].toHexString()}",
            )
        }
        codec = try {
            MediaCodecStartup.create(
                create = { diagnosticStage = "decoder-create"; MediaCodec.createDecoderByType(mime) },
                configure = { diagnosticStage = "decoder-configure"; it.configure(mediaFormat, null, null, 0) },
                start = { diagnosticStage = "decoder-start"; it.start() },
                release = { it.release() },
            ).also {
                // A vendor name getter or diagnostic callback must not discard a started codec.
                runCatching {
                    val name = it.name
                    Log.i(TAG, "audio decoder configured mime=$mime name=$name")
                    report("Audio: decoder ready audioType=${format.audioType} codec=${format.codec} name=$name")
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            reportFailure(error)
            null
        }
    }

    private fun createTrack() {
        diagnosticStage = "track-buffer-size"
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            runCatching { report("Audio: track unavailable api=${Build.VERSION.SDK_INT} stage=$diagnosticStage " +
                "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} " +
                "channels=${format.channels} minBufferResult=$minBuffer") }
            return
        }
        val selection = mappedSelection()
        mappedChannel = selection.channel
        val streamOverride = channelOverride(selection.channel)
        actualLegacyStreamType = streamOverride.takeIf { it != 0 }
        var attributes = audioAttributesFor(selection, streamOverride)
        trackAttributes = attributes
        val plan = MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA,
            format.sampleRate, format.channels, minBuffer, mediaBufferMillis)
        bytesPerSecond = format.sampleRate * frameBytes
        diagnosticStage = "track-build"
        // AAudio for music and guidance routed by usage; calls, Siri and legacy stream types keep AudioTrack.
        val native = if (streamOverride == 0 && nativePcm != null &&
            (selection.channel == AudioChannel.MEDIA || selection.channel == AudioChannel.NAVIGATION)) {
            val nativeAttributes = audioAttributesFor(selection)
            NativeAudioOut.open(format.sampleRate, pcmChannels, nativeAttributes.usage, nativeAttributes.contentType,
                plan.trackBufferBytes / frameBytes, outputDeviceResolver)?.also { attributes = nativeAttributes }
        } else null
        if (native != null) {
            nativeOut = native
            prepareOutput(native, selection, streamOverride, plan, attributes, "usage(aaudio)")
            return
        }
        val built: AudioTrack
        var routeLabel: String
        if (streamOverride == 0) {
            attributes = audioAttributesFor(selection)
            routeLabel = "usage"
            built = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(pcmFormat(encoding, channelMask))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(plan.trackBufferBytes)
                .build()
        } else {
            val streamType = streamOverride
            routeLabel = "streamType=$streamType"
            built = LegacyAudioFallback.build(
                createLegacy = {
                    AudioTrack(streamType, format.sampleRate, channelMask, encoding,
                        plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                },
                isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                release = { it.release() },
                createFallback = {
                    diagnosticStage = "track-fallback-build"
                    routeLabel = "streamType=$streamType(fallback=usage)"
                    actualLegacyStreamType = null
                    Log.w(TAG, "streamType=$streamType rejected by this ROM; falling back to usage-based track")
                    attributes = audioAttributesFor(selection)
                    AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(pcmFormat(encoding, channelMask))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(plan.trackBufferBytes)
                        .build()
                },
            )
        }
        track = built
        if (mappedChannel == AudioChannel.PHONE) {
            val listener = AudioRouting.OnRoutingChangedListener { routing -> checkCallRoute(routing.routedDevice) }
            if (runCatching { built.addOnRoutingChangedListener(listener, Handler(Looper.getMainLooper())) }.isSuccess) {
                routingListener = listener
            }
        }
        diagnosticStage = "track-attributes"
        prepareOutput(checkNotNull(output), selection, streamOverride, plan,
            audioTrackAttributesForFocus(built, attributes), routeLabel)
    }

    /** What every output gets once built: thresholds, clock trim, and the ready/route lines. */
    private fun prepareOutput(
        built: PcmOutput,
        selection: AudioChannelSelection,
        streamOverride: Int,
        plan: MediaAudioBuffer.Plan,
        attributes: AudioAttributes,
        routeLabel: String,
    ) {
        applyNavigationAttenuation()
        trackAttributes = attributes
        diagnosticStage = "track-capacity"
        val capacityBytes = built.bufferSizeInFrames * frameBytes
        bufferProgress.configure(plan.startBytes, capacityBytes, PREBUFFER_WRITE_CHUNK_BYTES)
        // Only music plays long enough for the clocks to drift apart; calls keep their echo reference exact.
        clockTrim = if (mappedChannel == AudioChannel.MEDIA) MediaClockTrim(format.sampleRate, (startThresholdBytes / frameBytes).toLong(),
            measuresTarget = true) else null
        val trackMetadata = runCatching {
            "api=${Build.VERSION.SDK_INT} trackState=${built.state} trackRate=${built.sampleRate} " +
                "usage=${trackAttributes?.usage} contentType=${trackAttributes?.contentType} " +
                "attributesSource=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "track" else "configured"}"
        }.getOrDefault("api=${Build.VERSION.SDK_INT} trackMetadata=unavailable")
        report("Audio: ready audioType=${format.audioType} codec=${format.codec} " +
            "rate=${format.sampleRate} channels=${format.channels} " +
            "route=$routeLabel " +
            "bufferMs=${capacityBytes * 1000L / bytesPerSecond} startMs=${startThresholdBytes * 1000L / bytesPerSecond} " +
            trackMetadata)
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "route=$routeLabel " +
                "buffer=${capacityBytes * 1000L / bytesPerSecond}ms start=${startThresholdBytes * 1000L / bytesPerSecond}",
        )
        Log.i(
            TAG,
            "audio route type=${format.payloadType} audioType=${format.audioType} " +
                "mode=${if (advancedAudioChannelMapping) AudioChannelMappingMode.AUTOMOTIVE_BUS else AudioChannelMappingMode.MOBILE_COMPATIBLE} " +
                "channel=${selection.channel} usage=${usageFor(selection.channel)} " +
                "contentType=${contentTypeFor(selection.contentType)} " +
                "streamOverride=$streamOverride " +
                "focus=${if (audioFocusEnabled) "on" else "off"}",
        )
    }

    /** 0 uses usage-based routing; 1–20 attempt legacy stream types supported by the head unit. */
    private fun channelOverride(channel: AudioChannel): Int = when (channel) {
        // ECARX can silently consume USAGE_ASSISTANT; use the driver's working media output for Siri.
        AudioChannel.MEDIA, AudioChannel.ASSISTANT -> mediaChannel
        AudioChannel.NAVIGATION -> navigationChannel
        else -> 0
    }

    fun setNavigationAttenuationDb(db: Int) {
        // The Settings callback must never wait for release(), which may be blocked in
        // vendor MediaCodec.stop()/release() while holding the renderer monitor.
        navigationAttenuationDb = NavigationAudioGain.clamp(db)
    }

    /**
     * Worker-thread only. The guidance is scaled by native PCM processing as it is written, not with
     * AudioTrack.setVolume: on one head unit the track accepted every setVolume and the guidance on
     * stream 14 stayed as loud (#719). Each change is reported once, when it reaches this stream.
     */
    private fun applyNavigationAttenuation() {
        if (mappedChannel != AudioChannel.NAVIGATION) return
        val requestedDb = navigationAttenuationDb
        // Leave default playback exactly as it was before this setting existed.
        if (requestedDb == 0 && appliedNavigationAttenuationDb == null) return
        if (appliedNavigationAttenuationDb == requestedDb) return
        appliedNavigationAttenuationDb = requestedDb
        runCatching { report("Audio: navigation attenuation=" + requestedDb + "dB applied=true") }
    }

    private fun audioAttributesFor(
        selection: AudioChannelSelection,
        streamOverride: Int,
    ): AudioAttributes = AudioRouteAttributes.resolve(streamOverride) { audioAttributesFor(selection) }

    private fun mappedSelection(): AudioChannelSelection {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        val selection = AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
        )
        return if (speakerphoneCall) AudioChannelMapper.playCallOnSpeaker(selection) else selection
    }

    private fun audioAttributesFor(selection: AudioChannelSelection): AudioAttributes =
        if (selection.channel == AudioChannel.NAVIGATION && navigationChannel == 0) {
            NavigationAudioAttributes.create(navigationOemContent)
        } else AudioAttributes.Builder()
            .setUsage(usageFor(selection.channel))
            .setContentType(contentTypeFor(selection.contentType))
            .build()

    /**
     * Shares a sink-level focus request across all active non-navigation renderers.
     * Navigation guidance takes no focus here: it overlays media without ducking it. The opt-in
     * [NavigationPromptFocus] requests focus for each prompt, between duckAudio and unduckAudio.
     */
    private fun requestAudioFocus() {
        val channel = mappedChannel ?: return
        val attributes = trackAttributes ?: return
        if (channel == AudioChannel.NAVIGATION) {
            Log.i(TAG, "audio focus skipped channel=NAVIGATION; overlays without ducking")
            return
        }
        output?.let { out -> audioFocusCoordinator.acquire(focusOwner(out), channel, attributes, out::setVolume) }
    }

    private fun abandonAudioFocus() {
        output?.let { audioFocusCoordinator.release(focusOwner(it)) }
    }

    /** The focus coordinator keys an AudioTrack by itself, as before. */
    private fun focusOwner(out: PcmOutput): Any = (out as? TrackOutput)?.track ?: out

    private fun pcmFormat(encoding: Int, channelMask: Int) = AndroidAudioFormat.Builder()
        .setSampleRate(format.sampleRate)
        .setChannelMask(channelMask)
        .setEncoding(encoding)
        .build()

    private fun streamType(): Int {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
            navigationStreamType = navigationStreamType,
        ).streamType
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        // Keep Siri's transient focus identity while playing speech through the working media route.
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_MEDIA
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    private fun handle(packet: AudioPacket) {
        try {
            handleRtp(packet)
        } finally {
            packet.rtp.release()
        }
    }

    private fun handleRtp(packet: AudioPacket) {
        packetKind = packet.rtp.javaClass.simpleName
        checkNotNull(nativePcm).feed(packet.rtp, packet.sample, codec, this)
    }

    /** Diagnostics only: codec dispatch and input results are owned by Rust. */
    private fun pcmInputResult(event: Int, payloadBytes: Int, length: Int) {
        when (event) {
            0 -> if (!firstAacPayloadLogged) {
                firstAacPayloadLogged = true
                Log.i(TAG, "audio AAC access unit bytes=$payloadBytes")
            }
            1 -> decoderUnavailablePackets++
            2 -> diagnosticStage = "decoder-input"
            3 -> {
                shortOpusPackets++
                if (!firstOpusShortPacketLogged) {
                    firstOpusShortPacketLogged = true
                    Log.i(TAG, "audio Opus skipping short packet bytes=$payloadBytes")
                }
            }
            4 -> {
                if (length < 0) {
                    inputDropped++
                    if (inputDropped == 1) Log.w(TAG, "audio Opus packet did not decode bytes=$payloadBytes error=$length")
                    return
                }
                inputQueued++
                if (!firstInputQueuedLogged) {
                    firstInputQueuedLogged = true
                    Log.i(TAG, "audio decoder first input codec=${format.codec} bytes=$payloadBytes packet=$packetKind decoder=libopus")
                }
                if (length > 0) {
                    outputBuffers++
                    if (outputBuffers == 1) Log.i(TAG, "audio decoder output codec=${format.codec} buffers=1 bytes=$length decoder=libopus")
                }
            }
        }
    }

    private fun aacInputRunning(): Boolean = running

    /** SDK diagnostics for the native AAC driver and platform Opus input. */
    private fun decoderInputResult(result: Int, length: Int) {
        when (result) {
            1 -> {
                inputDropped++
                if (inputDropped == 1) Log.w(TAG,
                    "audio decoder input unavailable codec=${format.codec} queued=$inputQueued dropped=$inputDropped")
            }
            2 -> {
                inputQueued++
                if (!firstInputQueuedLogged) {
                    firstInputQueuedLogged = true
                    Log.i(TAG, "audio decoder first input codec=${format.codec} bytes=$length packet=$packetKind")
                }
            }
            3 -> inputDropped++
        }
    }

    private fun drainCodec(codec: MediaCodec) {
        diagnosticStage = "decoder-output"
        checkNotNull(nativePcm).drain(codec, MediaCodec.BufferInfo(), this)
    }

    private fun decoderOutputRunning(): Boolean = running.also { if (it) diagnosticStage = "decoder-output" }

    private fun decoderOutputFormat(codec: MediaCodec) {
        // Vendor metadata inspection must not interrupt otherwise working playback.
        if (decoderOutputReports < 4) runCatching {
            val outputFormat = codec.outputFormat
            val metadata = "rate=${outputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE)} " +
                "channels=${outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)} " +
                "pcmEncoding=${outputFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING)}"
            if (metadata != lastDecoderOutputMetadata) {
                lastDecoderOutputMetadata = metadata
                decoderOutputReports++
                report("Audio: decoded format audioType=${format.audioType} codec=${format.codec} $metadata")
            }
        }
    }

    private fun decoderOutputSize(size: Int) {
        outputBuffers++
        if (outputBuffers == 1) Log.i(TAG, "audio decoder output codec=${format.codec} " +
            "buffers=$outputBuffers bytes=$size queued=$inputQueued dropped=$inputDropped")
    }

    private fun writePcm(buffer: ByteBuffer, length: Int) {
        val track = output ?: return
        val native = checkNotNull(nativePcm)
        diagnosticStage = "track-write"
        logFirstPcm(length)
        val navigation = mappedChannel == AudioChannel.NAVIGATION
        applyNavigationAttenuation()
        var flags = 0
        if (navigation) flags = flags or NativePcm.DETECT_AUDIBLE or NativePcm.ATTENUATE
        if (!fadeApplied) { flags = flags or NativePcm.FADE_IN; fadeApplied = true }
        if (native.process(buffer, length, flags, navigationAttenuationDb, NAVIGATION_AUDIBLE_PEAK)) {
            lastAudibleNs = System.nanoTime()
            if (!navigationAudible) reportNavigationAudio(true)
        }
        writeTrack(track, length,
            write = { at, count -> track.write(buffer, at, count) },
            echo = { reference, at, count, pending ->
                reference.append(buffer, at, count, pcmChannels, pending, System.nanoTime())
            })
    }

    private fun logFirstPcm(length: Int) {
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length native=${nativePcm != null}",
            )
        }
    }

    /** Writes [length] bytes to the track through [write] (offset, count), prebuffering before playback. */
    private inline fun writeTrack(
        track: PcmOutput,
        length: Int,
        write: (Int, Int) -> Int,
        echo: (EchoReference, Int, Int, Long?) -> Unit,
    ) {
        var written = 0
        while (written < length && running) {
            val writeLength = bufferProgress.writeLimit(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            val writeStarted = System.nanoTime()
            diagnosticStage = "track-write"
            val count = write(written, writeLength)
            maxWriteMs = maxOf(maxWriteMs, (System.nanoTime() - writeStarted) / 1_000_000L)
            when (val action = NativeAudioScheduler.writeDisposition(count, track is NativeAudioOut)) {
                1, 4 -> {
                    writeErrorsThisWindow++
                    lastWriteErrorCode = count
                    check(action != 4) { "AAudio write failed: $count" }
                    break
                }
                2 -> {
                    zeroWritesThisWindow++
                    Thread.sleep(AUDIO_POLL_MILLIS)
                    continue
                }
                3 -> { zeroWritesThisWindow++; break }
            }
            if (count < writeLength) partialWritesThisWindow++
            val framesWritten = count / frameBytes
            written += count
            totalWrittenFrames += framesWritten
            writtenFramesThisWindow += framesWritten
            val startWanted = bufferProgress.completedWrite(count, System.nanoTime())
            if (playbackStarted) reportNavigationPlayback(track)
            if (playbackStarted) trimClock(track)
            if (startWanted) {
                startPlayback(track)
                Log.i(TAG, "audio playback started type=${format.payloadType}")
            }
            echoReference?.let { reference ->
                val pending = if (playbackStarted) {
                    totalWrittenFrames - (track.playbackHeadPosition.toLong() and 0xffffffffL)
                } else {
                    null
                }
                echo(reference, written - count, count, pending)
            }
        }
    }

    private fun startPlayback(track: PcmOutput) {
        diagnosticStage = "track-play"
        underrunsAtPlaybackStart = track.underrunCount
        headAtPlaybackStart = track.playbackHeadPosition
        clockTrim?.measureAgain()
        track.play()
        bufferProgress.playback(true)
        reportNavigationPlayback(track)
        if (rebufferCount > 0 && rebufferRecoverReportCount < MAX_REBUFFER_REPORTS) {
            rebufferRecoverReportCount++
            report("Audio: rebuffer recovered audioType=${format.audioType} rebufferCount=$rebufferCount")
        }
    }

    private fun reportNavigationPlayback(track: PcmOutput) {
        if (!running) return
        val priorityVoice = mappedChannel == AudioChannel.PHONE || mappedChannel == AudioChannel.ASSISTANT
        if (mappedChannel != AudioChannel.NAVIGATION && !priorityVoice) return
        // The optional key-routing hint must never interrupt audio on unusual vendor ROMs.
        runCatching {
            val pendingFrames = bufferProgress.queuedBytes(track.playbackHeadPosition) / frameBytes
            val bufferedMillis = (pendingFrames * 1000L + format.sampleRate - 1L) / format.sampleRate
            NavigationPlayback.played(navigationPlaybackToken, actualLegacyStreamType, bufferedMillis, priorityVoice)
        }
    }

    private fun checkCallRoute(device: AudioDeviceInfo?) {
        if (device?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) onRoutedToEarpiece?.invoke()
    }

    private fun trimClock(track: PcmOutput) {
        val trim = clockTrim ?: return
        val now = System.nanoTime()
        val compressedRtpFrames = nativeQueue.pendingFrames(queueDropSnapshot)
        val compressedPcmFrames = compressedRtpFrames * format.sampleRate /
            format.rtpClockRate.coerceAtLeast(1)
        val dropped = queueDropSnapshot[0].toInt() + inputDropped
        if (dropped != droppedAtLastTrim) {
            droppedAtLastTrim = dropped
            trim.measureAgain()
        }
        val head = track.playbackHeadPosition
        trim.sample(bufferProgress.queuedBytes(head) / frameBytes, now, compressedPcmFrames,
            headMoving = head != headAtPlaybackStart, arrivedFrameNs = queuedFrameNsSince(trim.lastSampleNs, now))
        val rate = trim.rateDue(now) ?: return
        val result = runCatching { track.setPlaybackRate(rate) }.getOrDefault(AudioTrack.ERROR)
        if (result != AudioTrack.SUCCESS) {
            // The track keeps its rate; music then plays as it did before the trim.
            trim.close()
            clockTrim = null
            report("Audio: clock trim off audioType=${format.audioType} rate=$rate result=$result")
        }
    }

    /** For the packets queued since [sinceNs], the frames each adds to the queue times how long it has waited. */
    private fun queuedFrameNsSince(sinceNs: Long, nowNs: Long): Double =
        nativeQueue.arrivedFrameNs(sinceNs, nowNs, format.sampleRate)

    private fun maintainPlaybackBuffer() {
        val track = output ?: return
        if (track is NativeAudioOut) {
            val result = track.drain()
            check(result >= 0) { "AAudio drain failed: $result" }
        }
        if (bufferProgress.needsRebuffer(mappedChannel == AudioChannel.MEDIA,
                track.underrunCount > underrunsAtPlaybackStart, queuedPacketCount == 0, track.playbackHeadPosition)) {
            // The hardware buffer has starved below the recovery floor. Pause without flushing
            // or discarding PCM, then use the configured start threshold again when music resumes.
            track.pause()
            bufferProgress.playback(false)
            // Pausing retains queued PCM. Count it toward the restart threshold so a
            // blocking write cannot fill the paused track before we call play().
            bufferProgress.retainAfterPause(track.playbackHeadPosition, System.nanoTime())
            rebufferCount++
            if (rebufferPauseReportCount < MAX_REBUFFER_REPORTS) {
                rebufferPauseReportCount++
                val retainedMs = if (bytesPerSecond > 0) prebufferBytes * 1000L / bytesPerSecond else 0L
                report("Audio: rebuffer paused audioType=${format.audioType} rebufferCount=$rebufferCount retainedMs=$retainedMs")
            }
        }
        // A short final burst may never reach the start threshold. Play it after a bounded wait.
        if (bufferProgress.waitingForTail(queuedPacketCount == 0) &&
            bufferProgress.tailReady(System.nanoTime(), BUFFER_TAIL_WAIT_NS)) {
            startPlayback(track)
        }
    }

    // Persist counters even during packet starvation, and flush before disconnect releases the track.
    private fun logStatsIfDue(force: Boolean = false) {
        val now = System.nanoTime()
        if (statsWindowStartNs == 0L) statsWindowStartNs = now
        if (!force && now - statsWindowStartNs < STATS_WINDOW_NS) return
        val underruns = output?.underrunCount ?: 0
        nativeQueue.stats(queueStats, reset = true)
        val lastRx = queueStats[3]
        val currentTrack = output
        val playbackHeadFrames = currentTrack?.playbackHeadPosition
            ?.toLong()?.and(0xffff_ffffL)
        val playbackAdvanceFrames = playbackHeadFrames?.let { current ->
            val previous = lastPlaybackHeadFrames
            lastPlaybackHeadFrames = current
            previous?.let { (current - it) and 0xffff_ffffL }
        }
        val queuedFrames = playbackHeadFrames?.let { (totalWrittenFrames - it).coerceAtLeast(0L) }
        // A route decided before the listener was added reaches no callback; the stats see it.
        if (mappedChannel == AudioChannel.PHONE) runCatching { checkCallRoute(currentTrack?.routedDevice) }
        // "PHONE " marks the iPhone's own log lines, which the report drops, and it dropped every
        // call's playback line with it (#647).
        val received = queueStats[0].toInt()
        val dropped = queueStats[1].toInt()
        val arrivalGapMs = queueStats[4]
        if (debugLogsEnabled()) {
            val channelName = if (mappedChannel == AudioChannel.PHONE) "CALL" else mappedChannel?.name
            val line = "audio stats audioType=${format.audioType} payloadType=${format.payloadType} channel=$channelName " +
                "routeId=${currentTrack?.routedDevice?.id ?: -1} routeType=${currentTrack?.routedDevice?.type ?: -1} routeAddress=${currentTrack?.routedDevice?.address ?: "none"} " +
                "usage=${trackAttributes?.usage} contentType=${trackAttributes?.contentType} codec=${format.codec} " +
                "attributesSource=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "track" else "configured"} legacyStream=${actualLegacyStreamType ?: "none"} " +
                "trackState=${currentTrack?.state ?: -1} playState=${currentTrack?.playState ?: -1} " +
                "sampleRate=${currentTrack?.sampleRate ?: format.sampleRate} " +
                "trackBufferFrames=${currentTrack?.bufferSizeInFrames ?: -1} " +
                "rx=${received} " +
                "dropped=${dropped} underruns=+${underruns - statsLastUnderruns} queue=$queuedPacketCount " +
                "playing=$playbackStarted maxGapMs=${arrivalGapMs} " +
                "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$maxWriteMs " +
                "writtenFrames=$writtenFramesThisWindow totalWrittenFrames=$totalWrittenFrames " +
                "playbackHeadFrames=${playbackHeadFrames ?: -1} playbackAdvanceFrames=${playbackAdvanceFrames ?: -1} " +
                "estimatedQueuedFrames=${queuedFrames ?: -1} writeErrors=$writeErrorsThisWindow " +
                "lastWriteError=${lastWriteErrorCode ?: "none"} zeroWrites=$zeroWritesThisWindow " +
                "partialWrites=$partialWritesThisWindow " +
                "decoderDroppedTotal=$inputDropped outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount " +
                "playbackRate=${clockTrim?.rate ?: -1} ended=$force"
            Log.i(STATS_TAG, line)
            report(line)
            if (format.codec != AudioCodecKind.LPCM) {
                // Keep this separate: the exported diagnostic recorder caps each line at 700 characters.
                val decoderLine = "Audio: decoder stats audioType=${format.audioType} codec=${format.codec} " +
                    "inputQueuedTotal=$inputQueued inputDroppedTotal=$inputDropped " +
                    "shortOpusPacketsTotal=$shortOpusPackets decoderUnavailablePacketsTotal=$decoderUnavailablePackets " +
                    "outputBuffersTotal=$outputBuffers ended=$force"
                Log.i(STATS_TAG, decoderLine)
                runCatching { report(decoderLine) }
            }
        }
        statsLastUnderruns = underruns
        maxWriteMs = 0L
        writtenFramesThisWindow = 0L
        writeErrorsThisWindow = 0
        lastWriteErrorCode = null
        zeroWritesThisWindow = 0
        partialWritesThisWindow = 0
        statsWindowStartNs = now
    }

    private fun reportFailure(error: Throwable) {
        runCatching { report("Audio: renderer failed api=${Build.VERSION.SDK_INT} " +
            "audioType=${format.audioType} codec=${format.codec} stage=$diagnosticStage " +
            MediaFailureSummary.describe(error)) }
    }

    @Synchronized
    private fun release() {
        releaseQueued()
        bufferProgress.close()
        nativePcm?.close()
        nativePcm = null
        NavigationPlayback.close(navigationPlaybackToken)
        abandonAudioFocus()
        val codec = codec
        this.codec = null
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
        releaseTrack()
    }

    @Synchronized
    private fun releaseTrack() {
        clockTrim?.close()
        clockTrim = null
        nativeOut?.let { native ->
            nativeOut = null
            runCatching { native.pause() }
            runCatching { native.release() }
        }
        val track = track
        this.track = null
        if (track != null) {
            routingListener?.let { listener -> runCatching { track.removeOnRoutingChangedListener(listener) } }
            routingListener = null
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "diplay-usb"
        const val AUDIO_POLL_MILLIS = 10L
        /** About -54 dBFS: above dither, below any spoken prompt. */
        const val NAVIGATION_AUDIBLE_PEAK = 64
        /** Covers the track's playout delay so the prompt's tail is not cut by the release. */
        const val NAVIGATION_SILENCE_NS = 600_000_000L
        const val BUFFER_TAIL_WAIT_NS = 500_000_000L
        // Holds a burst after a Wi-Fi gap (~4 s of AAC) instead of dropping it.
        const val MAX_QUEUED_PACKETS = 192
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val STATS_TAG = "DiPlay-AudioStats"
        const val STATS_WINDOW_NS = 5_000_000_000L
        const val MAX_REBUFFER_REPORTS = 5
    }
}
