package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaPacket
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.telecom.PhoneAudioRoute
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Each audio stream keeps its own track and usage so
 * media and navigation guidance stay independently routable. Call [close]
 * when the session tears down.
 */
class AndroidMediaSink(
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    /** The size negotiated for the instrument-cluster stream (111); 0 uses the main size. */
    private val clusterVideoWidth: Int = 0,
    private val clusterVideoHeight: Int = 0,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    private val audioFocusEnabled: Boolean = false,
    private val audioFocusAutoYield: Boolean = true,
    private val mediaChannel: Int = 0,
    private val navigationChannel: Int = 0,
    navigationAttenuationDb: Int = 0,
    context: Context? = null,
    private val navigationStreamType: Int = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    private val onAudioDiagnostic: (String) -> Unit = {},
    /** True while any music ("media") audio stream is running; called from media threads. */
    private val onMediaAudioChanged: (Boolean) -> Unit = {},
    /** Opt-in call echo cancellation, replacing a controllable platform canceller while available. */
    private val callEchoCancellation: Boolean = false,
    /** Cut the bass that head units add when they play a call as music. */
    private val callVoiceFilter: Boolean = false,
    /** Read at each call: a phone or tablet used as the head unit plays calls on its loudspeaker (#701). */
    private val callSpeakerphone: () -> Boolean = { false },
    /**
     * Smooth video: show main-screen frames at the iPhone's frame time plus a delay that starts here and
     * then follows how late the decoder releases frames ([PacingDelay]); 0 shows each as soon as it is
     * decoded. Only a SurfaceView honours the timestamps, so the host sets it with one.
     */
    private val videoPacingDelayMillis: Int = 0,
    /** The main screen's frame rate (the frame-rate setting), requested from its decoder as the operating rate; 0 for none. */
    private val mainVideoFrameRate: Int = 0,
    /**
     * Low-latency decoding (a setting): ask Qualcomm decoders to output each frame as soon as it is decoded,
     * and hand decoded frames to the screen from a thread of their own.
     */
    private val vendorLowLatencyDecoder: Boolean = false,
    /** Opt-in MTK ACodec low-latency/no-reorder modes, with per-session automatic fallback. */
    private val mtkDecoderTuningEnabled: Boolean = false,
    /** Opt-in (#946): navigation prompts request duckable focus while the iPhone ducks audio. Needs [audioFocusEnabled]. */
    navigationPromptAudioFocus: Boolean = false,
    private val navigationOemContent: Boolean = false,
    /**
     * ECARX: each navigation stream picks its route from the iPhone's duckAudio ([NavigationAudioRoute]),
     * and on the music stream follows the car's navigation volume ([CarNavigationVolume]).
     */
    private val navigationFollowsIphone: Boolean = false,
    private val debugLogsEnabled: () -> Boolean = { false },
) : MediaSink {
    // Each downlink publishes its own reference; a mic must match that stream and sample rate.
    private val callEchoReferences = ConcurrentHashMap<AudioStreamId, EchoReference>()
    private val appContext = context?.applicationContext
    private val audioManager = appContext?.getSystemService(AudioManager::class.java)
    private val audioFocusCoordinator = AudioFocusCoordinator(
        appContext,
        audioFocusEnabled,
        audioFocusAutoYield,
        onAudioDiagnostic,
    )

    private val navigationPromptFocus = NavigationPromptFocus(
        appContext,
        promptFocusEnabled = navigationPromptAudioFocus,
        audioFocusEnabled = audioFocusEnabled,
        attributes = { navigationRouteAttributes(promptRoute(), navigationOemContent) },
        // On the music stream a prompt reaches the speakers through the focus CarPlay music already
        // holds. Asking again made the ECARX head unit resend its amplifier source each time, a hitch
        // in the music (car test 2026-11-07). Without music it still asks: after a cold boot the
        // amplifier has no source until something holds focus, and a first prompt was silent.
        skipRequest = {
            navigationFollowsIphone && promptRoute() == NavigationAudioRoute.MUSIC_STREAM &&
                audioFocusCoordinator.mediaHoldsFocus()
        },
        report = onAudioDiagnostic,
    )

    /** The iPhone's latest duckAudio/unduckAudio, for the per-prompt log line. */
    @Volatile private var iphoneDucking = false

    /**
     * The route of the current or next prompt. Following the iPhone, each navigation renderer moves
     * to it between prompts; a duckAudio arrives before the prompt's sound, in time for the move.
     */
    private fun navigationRoute(): Int = NavigationAudioRoute.choose(navigationFollowsIphone, navigationChannel, iphoneDucking)

    /** The open navigation renderer that follows the iPhone, if any. */
    @Volatile private var followingNavigationRenderer: AudioRenderer? = null

    /**
     * The route a prompt's focus is asked for. A duck asks for the car channel the renderer moves to
     * before the sound arrives. Without one, the renderer's actual route: a prompt that starts within
     * the silence wait after a ducked one still plays on the car channel, which is silent without focus.
     */
    private fun promptRoute(): Int = if (iphoneDucking) navigationRoute()
        else followingNavigationRenderer?.navigationRoute ?: navigationRoute()

    override fun onAudioDuck(ducked: Boolean) {
        iphoneDucking = ducked
        if (ducked) navigationPromptFocus.onDuck() else navigationPromptFocus.onUnduck()
    }

    /** dB for a navigation stream on [route]: the car's navigation volume on the music stream. */
    private fun navigationAttenuationFor(route: Int): Int =
        if (navigationFollowsIphone && route == NavigationAudioRoute.MUSIC_STREAM) {
            appContext?.let(CarNavigationVolume::relativeDb) ?: navigationAttenuationDb
        } else navigationAttenuationDb

    private fun isNavigationStream(format: AudioFormat): Boolean = AudioChannelMapper.map(
        audioType = format.audioType,
        payloadType = format.payloadType,
        mode = if (advancedAudioChannelMapping) AudioChannelMappingMode.AUTOMOTIVE_BUS else AudioChannelMappingMode.MOBILE_COMPATIBLE,
    ).channel == AudioChannel.NAVIGATION

    private fun navigationPromptState(): String =
        "iphoneDuck=$iphoneDucking promptFocus=${navigationPromptFocus.state()}"

    /** The media-key session may own Android's current focus request for this same sink. */
    fun onMediaAudioFocusChanged(change: Int) {
        audioFocusCoordinator.onExternalFocusChange(change)
    }

    private val screenStateLock = Any()
    private val activeScreenTypes = mutableSetOf<Int>()
    private var defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    // Surface capture, worker publication, retirement and detach snapshots share one ownership boundary.
    // Native codec work and waits never run under this lock.
    private val videoOwnershipLock = Any()
    private var videoClosed = false
    private val videoReleasedListeners = ArrayList<() -> Unit>()
    // Closed decoders whose workers may still hold a codec on a surface until they exit; a surface
    // detach also waits for them.
    private val closingVideoDecoders = ConcurrentHashMap.newKeySet<VideoDecoder>()
    // How long a surface detach waits for decoders to confirm, and then for an unconfirmed one to exit
    // after it is closed. Variables so tests can shorten them.
    internal var detachTimeoutNanos = 2_000_000_000L
    // Builds the fanout a stream's decoder draws its mirrors with; a variable so tests can replace OpenGL.
    internal var videoFanoutFactory: (Int, Int, (String) -> Unit) -> VideoFanout = { w, h, r -> GlVideoFanout(w, h, r) }
    internal var detachGraceNanos = 500_000_000L
    private val mediaAudioTypes = mutableSetOf<AudioStreamId>()
    private val audioRenderers = ConcurrentHashMap<AudioStreamId, AudioRenderer>()
    @Volatile private var navigationAttenuationDb = NavigationAudioGain.clamp(navigationAttenuationDb)

    /** Apply a saved Audio setting to existing navigation tracks without reconnecting CarPlay. */
    fun setNavigationAttenuationDb(db: Int) {
        navigationAttenuationDb = NavigationAudioGain.clamp(db)
        audioRenderers.values.forEach { it.setNavigationAttenuationDb(navigationAttenuationFor(it.navigationRoute)) }
    }
    private val microphoneUplinks = ConcurrentHashMap<AudioStreamId, MicrophoneUplink>()
    private val audioModeLock = Any()
    private var communicationModeStream: AudioStreamId? = null // includes a request waiting for its grant
    private var communicationModeActive = false // guarded by audioModeLock
    private var callSpeaker: CallSpeaker? = null // guarded by audioModeLock
    private var earpieceFallbackTried = false // guarded by audioModeLock
    // Retain a revoked request for onResume; only stream teardown releases it. Guarded by audioModeLock.
    private var micHeld: com.shilapi.xcertplay.hud.EcarxMicPolicy.Held? = null
    private var microphoneClosed = false // guarded by audioModeLock
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val videoDiagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val recoveryPending = AtomicBoolean(false)
    // Other surfaces that show a stream too, such as the centre card. The main screen gives each its own
    // decoder; other streams draw them from the stream's decoder ([mirrorsShareDecoder]).
    private val mirrorLock = Any()
    private val mirrorSurfaces = HashMap<Pair<Int, String>, Surface>()
    private val mirrorDecoders = HashMap<Pair<Int, String>, VideoDecoder>()
    private val lastVideoConfig = ConcurrentHashMap<Int, Pair<VideoCodec, ByteArray>>()
    private val recoveryExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "carplay-video-recovery").apply { isDaemon = true }
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        videoRecoveryHandlers[type] = handler
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        videoDiagnosticHandlers[type] = handler
    }

    private fun requestVideoRecovery(type: Int) {
        if (!recoveryPending.compareAndSet(false, true)) return
        try {
            recoveryExecutor.execute {
                try { videoRecoveryHandlers[type]?.invoke() }
                catch (error: Exception) { Log.w("xcertplay-usb", "Video keyframe request failed", error) }
                finally { recoveryPending.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { recoveryPending.set(false) }
    }

    fun setSurface(type: Int, surface: Surface) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            surfaces[type] = surface
            videoDecoders[type]?.setSurface(surface)
        }
    }

    fun clearSurface(type: Int, surface: Surface) {
        synchronized(videoOwnershipLock) {
            if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
        }
    }

    /**
     * Starts moving every decoder of this sink off [surface], which a SurfaceHolder destroy callback must
     * not return before. Each worker decides from the surface its codec actually renders to, after any
     * surface change queued before this: one already elsewhere has nothing to do. With [parkMain] the
     * main-screen decoder moves to an offscreen consumer it owns and keeps its state (smooth video, so the
     * picture can return at once); other decoders are released. Decoders closed earlier whose workers have
     * not exited yet are waited for too. [SurfaceDetach.await] returns the result.
     */
    fun beginSurfaceDetach(surface: Surface, parkMain: Boolean): SurfaceDetach {
        val deadline = System.nanoTime() + detachTimeoutNanos
        val requests = ArrayList<SurfaceDetachRequest>()
        val steps = ArrayList<() -> Boolean>()
        synchronized(videoOwnershipLock) {
            surfaces.entries.removeIf { it.value === surface } // future workers must not start on it
            if (defaultSurface === surface) defaultSurface = null
            for ((type, decoder) in videoDecoders.entries.toList()) {
                val request = decoder.requestDetach(surface, park = parkMain && type == MAIN_SCREEN_TYPE)
                requests += request
                steps += { settleDetach(type, decoder, request, deadline) }
            }
            closingVideoDecoders.removeIf { it.exited }
            closingVideoDecoders.toList().forEach { closing -> steps += { closing.awaitExitUntil(deadline + detachGraceNanos) } }
        }
        return SurfaceDetach(requests, steps)
    }

    /** A surface detach in flight across one sink's decoders; see [beginSurfaceDetach]. */
    class SurfaceDetach internal constructor(
        internal val requests: List<SurfaceDetachRequest>,
        private val steps: List<() -> Boolean>,
    ) {
        /**
         * Waits until every decoder has let go of the surface (moved, parked, released, or its worker
         * exited). False when one did not confirm in time and its worker, closed then, did not exit within
         * the grace period either; nothing more can be done from here, as with any native call that hangs.
         */
        fun await(): Boolean = steps.map { it() }.all { it }
    }

    private fun settleDetach(type: Int, decoder: VideoDecoder, request: SurfaceDetachRequest, deadline: Long): Boolean {
        if (decoder.awaitDetach(request, deadline)) return true
        Log.w("xcertplay-usb", "Video detach not confirmed in time type=$type; closing that decoder")
        videoDiagnosticHandlers[type]?.invoke("surface detach not confirmed; decoder closed")
        // The stream's next frame starts a fresh decoder on no surface, so it cannot compete for this one.
        retire(type, decoder)
        return decoder.awaitExitUntil(deadline + detachGraceNanos)
    }

    /** Closes a stream's decoder and keeps it visible to surface detaches until its worker exits. */
    private fun retire(type: Int, decoder: VideoDecoder) {
        synchronized(videoOwnershipLock) {
            // A timed-out detach can reach here after the worker has already completed cleanup. Its
            // onExit removed both registrations; do not republish it after its sole release notification.
            if (videoDecoders[type] !== decoder && decoder !in closingVideoDecoders) return
            closingVideoDecoders += decoder
            videoDecoders.remove(type, decoder)
            decoder.close()
        }
    }

    /** After [close], waits up to [timeoutMillis] for this sink's decoders to release their codecs. */
    fun awaitVideoReleased(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        val closing = synchronized(videoOwnershipLock) { closingVideoDecoders.toList() }
        return closing.map { it.awaitExitUntil(deadline) }.all { it }
    }

    /** After [close], observes actual release of the tracked stream codecs, outside ownership locks. */
    fun whenVideoReleased(onReleased: () -> Unit) {
        val alreadyReleased = synchronized(videoOwnershipLock) {
            check(videoClosed) { "close before observing video release" }
            if (videoDecoders.isEmpty() && closingVideoDecoders.isEmpty()) true
            else {
                videoReleasedListeners += onReleased
                false
            }
        }
        if (alreadyReleased) runCatching(onReleased)
    }

    private fun onVideoDecoderExit(decoder: VideoDecoder) {
        val listeners = synchronized(videoOwnershipLock) {
            closingVideoDecoders -= decoder
            videoDecoders.entries.removeIf { it.value === decoder }
            if (videoClosed && videoDecoders.isEmpty() && closingVideoDecoders.isEmpty()) {
                videoReleasedListeners.toList().also { videoReleasedListeners.clear() }
            } else emptyList()
        }
        listeners.forEach { runCatching(it) }
    }

    /** True when this sink paces main-screen frames (smooth video); fixed for its lifetime. */
    val videoPacingEnabled: Boolean get() = videoPacingDelayMillis > 0

    /**
     * False until the main screen's first video frame. The screen stream's keepalives never reach the sink,
     * so a stream that carries only those, as from the stuck iPhones in #975 and #249, leaves it false.
     */
    @Volatile var mainVideoReceived = false
        private set

    /** Requests a keyframe for stream [type], e.g. after its surface came back. */
    /** Running totals of the main screen's decoder for a live FPS counter; null while it has none. */
    fun liveVideoCounters(): LiveVideoCounters? = videoDecoders[MAIN_SCREEN_TYPE]?.liveCounters()

    fun refreshPicture(type: Int) {
        videoDecoders[type]?.refreshPicture()
    }

    /**
     * Also shows stream [type] on [surface], one per [key]; null stops it. The stream's own surface is not
     * affected. The main screen decodes it with a decoder of its own, which starts at the next keyframe it asks
     * for. Other streams draw it from their one decoder, so a mirror costs no second hardware decoder.
     */
    fun setMirrorSurface(type: Int, key: String, surface: Surface?) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            val id = type to key
            synchronized(mirrorLock) {
                if (surface != null && mirrorSurfaces[id] === surface) return
                mirrorDecoders.remove(id)?.close()
                if (surface == null) mirrorSurfaces.remove(id) else mirrorSurfaces[id] = surface
            }
            if (mirrorsShareDecoder(type)) {
                // A decoder created later picks up every mirror when it starts.
                videoDecoders[type]?.setMirror(key, surface)
                return
            }
            if (surface == null) return
            lastVideoConfig[type]?.let { (codec, data) -> mirrorDecoders(type).forEach { it.configure(codec, data) } }
        }
    }

    private fun mirrorDecoders(type: Int): List<VideoDecoder> = synchronized(videoOwnershipLock) {
        if (videoClosed || mirrorsShareDecoder(type)) return emptyList()
        synchronized(mirrorLock) {
            if (mirrorSurfaces.isEmpty()) return emptyList()
            mirrorSurfaces.filterKeys { it.first == type }.map { (id, surface) ->
                mirrorDecoders.getOrPut(id) { newVideoDecoder(type, surface, " stream=$type mirror=${id.second}") }
            }
        }
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        synchronized(screenStateLock) {
            screenStreamActiveChanged = listener
            activeScreenTypes.forEach { listener?.invoke(it, true) }
        }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        synchronized(videoOwnershipLock) {
            if (!videoClosed) pendingVideoCodec[type] = codec
        }
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            val codec = pendingVideoCodec[type] ?: VideoCodec.H264
            lastVideoConfig[type] = codec to codecData
            videoDecoder(type).configure(codec, codecData)
            mirrorDecoders(type).forEach { it.configure(codec, codecData) }
        }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        if (type == MAIN_SCREEN_TYPE) mainVideoReceived = true
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            videoDecoder(type).submit(naluBytes)
            mirrorDecoders(type).forEach { it.submit(naluBytes) }
        }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
        if (type == MAIN_SCREEN_TYPE) mainVideoReceived = true
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            videoDecoder(type).submit(naluBytes, senderNanos, arrivalNanos)
            mirrorDecoders(type).forEach { it.submit(naluBytes) }
        }
    }

    override fun onVideoFrame(type: Int, frame: MediaPacket, senderNanos: Long, arrivalNanos: Long) {
        if (type == MAIN_SCREEN_TYPE) mainVideoReceived = true
        synchronized(videoOwnershipLock) {
            if (videoClosed) { frame.release(); return }
            // Each mirror decoder holds its own reference; the main decoder takes this one.
            mirrorDecoders(type).forEach { it.submit(frame.share()) }
            videoDecoder(type).submit(frame, senderNanos, arrivalNanos)
        }
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            if (!active) {
                videoRecoveryHandlers.remove(type)
                videoDiagnosticHandlers.remove(type)
                videoDecoders[type]?.let { retire(type, it) }
                synchronized(mirrorLock) {
                    mirrorDecoders.keys.filter { it.first == type }.forEach { mirrorDecoders.remove(it)?.close() }
                }
                lastVideoConfig.remove(type)
                pendingVideoCodec.remove(type)
            }
        }
        synchronized(screenStateLock) {
            if (active) activeScreenTypes.add(type) else activeScreenTypes.remove(type)
            screenStreamActiveChanged?.invoke(type, active)
        }
    }

    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        audioRenderer(id, format).start()
        if (format.audioType == "media") updateMediaAudio(id, true)
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audioRenderer(id, format).submit(rtp, sample)
    }

    override fun onAudioPacket(id: AudioStreamId, format: AudioFormat, rtp: MediaPacket, sample: Int) {
        audioRenderer(id, format).submit(rtp, sample)
    }

    override fun onAudioStopped(id: AudioStreamId) {
        synchronized(this) {
            audioRenderers.remove(id)?.also { if (followingNavigationRenderer === it) followingNavigationRenderer = null }?.close()
            callEchoReferences.remove(id)
        }
        updateMediaAudio(id, false)
    }

    private fun updateMediaAudio(id: AudioStreamId, active: Boolean) {
        val (before, after) = synchronized(mediaAudioTypes) {
            val before = mediaAudioTypes.isNotEmpty()
            if (active) mediaAudioTypes.add(id) else mediaAudioTypes.remove(id)
            before to mediaAudioTypes.isNotEmpty()
        }
        if (before != after) onMediaAudioChanged(after)
    }

    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        // This callback runs on the downlink thread; microphone failures must not stop playback.
        synchronized(audioModeLock) {
            if (microphoneClosed) return
            reportMicrophoneState(id, "before-start")
            if (config.audioType == "telephony" && audioManager != null) {
                if (communicationModeStream == null) communicationModeStream = id
                if (communicationModeStream == id && requestMicArbitration(id, config)) return
            }
            startMicrophoneCapture(id, config)
        }
    }

    private fun startMicrophoneCapture(id: AudioStreamId, config: MicrophoneConfig) {
        try {
            if (config.audioType == "telephony") enterCommunicationMode(id)
            val uplink = microphoneUplinks.computeIfAbsent(id) {
                MicrophoneUplink(config, onAudioDiagnostic,
                    speakerphoneCall = false,
                    echoReference = if (config.audioType == TELEPHONY_AUDIO_TYPE) callEchoReferences[id] else null,
                    audioManager = audioManager,
                    onCaptureState = { reportMicrophoneState(id, "capture") })
            }
            if (!uplink.start()) {
                microphoneUplinks.remove(id, uplink)
                restoreAudioMode(id)
            }
        } catch (error: Exception) {
            Log.e("xcertplay-usb", "microphone start failed stream=$id", error)
            MicrophoneCaptureStats.reportStartFailure(config, error, onAudioDiagnostic)
            onMicrophoneStopped(id)
        }
    }

    override fun onMicrophoneStopped(id: AudioStreamId) {
        synchronized(audioModeLock) {
            try {
                microphoneUplinks.remove(id)?.close()
            } finally {
                restoreAudioMode(id)
                reportMicrophoneState(id, "stopped")
            }
        }
    }

    /** One snapshot at start/end and per five-second stats window, never per PCM read. */
    private fun reportMicrophoneState(id: AudioStreamId, phase: String) {
        val manager = audioManager ?: return
        val mode = runCatching { manager.mode.toString() }.getOrDefault("unknown")
        val mute = runCatching { manager.isMicrophoneMute.toString() }.getOrDefault("unknown")
        runCatching {
            onAudioDiagnostic("Microphone: environment stream=$id phase=$phase mode=$mode microphoneMute=$mute")
        }
    }

    private fun enterCommunicationMode(id: AudioStreamId) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            if (communicationModeActive || (communicationModeStream != null && communicationModeStream != id)) return
            // Select the HAL communication path before AudioRecord is created.
            val before = manager.mode
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            // 820A (HiCar) routes the mic through vendor parameters, and its factory assistant or
            // navigation may already hold the mic resource; ask for both before AudioRecord exists.
            apply820MicParameters(manager, enabled = true)
            communicationModeStream = id
            communicationModeActive = true
            earpieceFallbackTried = false
            val speakerRequested = runCatching(callSpeakerphone).getOrDefault(false)
            if (speakerRequested) callSpeaker = CallSpeaker.on(manager)
            Log.i("xcertplay-usb", "audio mode $before -> ${manager.mode} for communication stream=$id")
            // The report, not just logcat: a head unit left in the voice path plays no media,
            // and that cannot be diagnosed from a car without adb. A failing report must not
            // disturb the mode, so it is the last thing done and it cannot throw out of here.
            runCatching {
                onAudioDiagnostic("Audio: mode entered communication from=$before" +
                    " now=${manager.mode} stream=$id" +
                    if (speakerRequested) " loudspeaker=${callSpeaker != null}" else "")
            }
        }
    }

    /**
     * A head unit built on a phone chip plays communication audio on an earpiece, which nobody hears in a car: the
     * Podofo SM4250 in #782 routed a whole call there (`routeType=1`), and the navigation prompts during it. The
     * head units route calls to the loudspeaker (`routeType=2`) and never get here. Once a call's track is routed
     * to the earpiece, the call moves to the loudspeaker as **Calls on the loudspeaker** would, once per call.
     */
    private fun moveCallToLoudspeaker(id: AudioStreamId) {
        val manager = audioManager ?: return
        val line = synchronized(audioModeLock) {
            if (communicationModeStream != id || callSpeaker != null || earpieceFallbackTried) return
            earpieceFallbackTried = true
            callSpeaker = CallSpeaker.on(manager)
            "Audio: call routed to the earpiece; loudspeaker=${callSpeaker != null} stream=$id"
        }
        runCatching { onAudioDiagnostic(line) }
    }

    /** A pending request defers capture without blocking the downlink thread. */
    private fun requestMicArbitration(id: AudioStreamId, config: MicrophoneConfig): Boolean {
        if (micHeld != null) return true
        val context = appContext ?: return false
        val policy = com.shilapi.xcertplay.hud.EcarxMicPolicy
        micHeld = policy.requestMic(context, policy.REQUEST_TYPE_CALL, object :
            com.shilapi.xcertplay.hud.EcarxMicPolicy.Listener {
            override fun onResume() {
                synchronized(audioModeLock) {
                    if (!microphoneClosed && communicationModeStream == id) startMicrophoneCapture(id, config)
                }
            }
            override fun onAbort(requestType: Int) {
                synchronized(audioModeLock) {
                    if (communicationModeStream == id) {
                        try {
                            microphoneUplinks.remove(id)?.close()
                        } finally {
                            restoreCommunicationMode(id)
                        }
                    }
                }
            }
            override fun onFallback(reason: String) { onResume() }
            override fun onDiagnostic(line: String) {
                runCatching { onAudioDiagnostic("$line stream=$id") }
            }
        })
        runCatching {
            onAudioDiagnostic("Audio: ECARX mic policy stream=$id policyAvailable=${micHeld != null}" +
                " requestType=CALL(2) ${policy.describe()}")
        }
        return micHeld != null
    }

    /** Hands the ECARX mic resource back when its communication capture ends. */
    private fun releaseMicArbitration(id: AudioStreamId) {
        val held = micHeld
        micHeld = null
        held?.release()
        if (held == null) runCatching {
            onAudioDiagnostic("Audio: ECARX mic released held=false ok=true stream=$id")
        }
    }

    /**
     * 820A (HiCar) vendor audio parameters. Mirrors the reference app's
     * AudioSessionController#setMic820HicarParametersLocked: open the mic route before capture and
     * close it after. The values are the exact strings the verified reference app writes on this head
     * unit; the parameter readback is always empty here, so success is judged by the write not
     * throwing rather than by getParameters.
     */
    private fun apply820MicParameters(manager: AudioManager, enabled: Boolean) {
        for (parameter in if (enabled) {
            listOf("hicar_enable=true", "AudioControlMode=01121001", "ASR_AEC=ON")
        } else {
            listOf("hicar_enable=false", "AudioControlMode=01121000", "ASR_AEC=OFF")
        }) {
            runCatching { manager.setParameters(parameter) }.onFailure { error ->
                Log.w("xcertplay-usb", "could not set $parameter", error)
                runCatching {
                    onAudioDiagnostic("Audio: 820 mic parameter $parameter failed=${error.javaClass.simpleName}")
                }
            }
        }
    }

    private fun restoreAudioMode(id: AudioStreamId?) {
        synchronized(audioModeLock) {
            val active = communicationModeStream ?: return
            if (id != null && id != active) return
            communicationModeStream = null
            try {
                restoreCommunicationMode(active)
            } finally {
                releaseMicArbitration(active)
            }
        }
    }

    /** Called under audioModeLock; a revoked grant keeps its request but returns the HAL route. */
    private fun restoreCommunicationMode(id: AudioStreamId) {
        if (!communicationModeActive) return
        communicationModeActive = false
        val manager = audioManager ?: return
        callSpeaker?.restore()
        callSpeaker = null
        val line = try {
            // MODE_NORMAL withdraws our request instead of retaining another process's old mode (#987).
            manager.mode = AudioManager.MODE_NORMAL
            Log.i("xcertplay-usb", "audio mode restored to ${manager.mode}")
            "Audio: mode restored requested=${AudioManager.MODE_NORMAL} now=${manager.mode} stream=$id"
        } catch (error: RuntimeException) {
            Log.w("xcertplay-usb", "could not restore audio mode", error)
            val currentMode = runCatching { manager.mode.toString() }.getOrDefault("unknown")
            "Audio: mode restore failed requested=${AudioManager.MODE_NORMAL} now=$currentMode" +
                " error=${error.javaClass.simpleName}"
        }
        apply820MicParameters(manager, enabled = false)
        runCatching { onAudioDiagnostic(line) }
    }

    fun close() {
        synchronized(audioModeLock) { microphoneClosed = true }
        synchronized(videoOwnershipLock) {
            videoClosed = true
            videoDecoders.entries.toList().forEach { (type, decoder) -> retire(type, decoder) }
            synchronized(mirrorLock) {
                mirrorDecoders.values.forEach(VideoDecoder::close)
                mirrorDecoders.clear()
                mirrorSurfaces.clear()
            }
        }
        synchronized(screenStateLock) {
            activeScreenTypes.forEach { screenStreamActiveChanged?.invoke(it, false) }
            activeScreenTypes.clear()
            screenStreamActiveChanged = null
        }
        videoRecoveryHandlers.clear()
        videoDiagnosticHandlers.clear()
        recoveryExecutor.shutdownNow()
        // Audio workers close asynchronously; none may reacquire focus after their sink closes.
        audioFocusCoordinator.close()
        navigationPromptFocus.close()
        audioRenderers.values.forEach(AudioRenderer::close)
        audioRenderers.clear()
        followingNavigationRenderer = null
        callEchoReferences.clear()
        val hadMedia = synchronized(mediaAudioTypes) { mediaAudioTypes.isNotEmpty().also { mediaAudioTypes.clear() } }
        if (hadMedia) onMediaAudioChanged(false)
        synchronized(audioModeLock) {
            try {
                microphoneUplinks.values.forEach(MicrophoneUplink::close)
            } finally {
                microphoneUplinks.clear()
                restoreAudioMode(null)
            }
        }
    }

    private fun videoDecoder(type: Int): VideoDecoder = synchronized(videoOwnershipLock) {
        videoDecoders[type] ?: run {
            // A decoder that replaces a closed one picks up the stream's codec configuration.
            val decoder = newVideoDecoder(type, surfaces[type] ?: defaultSurface, startImmediately = false)
            videoDecoders[type] = decoder
            if (mirrorsShareDecoder(type)) synchronized(mirrorLock) {
                mirrorSurfaces.filterKeys { it.first == type }.forEach { (id, surface) -> decoder.setMirror(id.second, surface) }
            }
            lastVideoConfig[type]?.let { (codec, data) -> decoder.configure(codec, data) }
            decoder.start()
            decoder
        }
    }

    // Once one decoder had to drop the QTI low-latency request (refused, failed or stalled), the decoders
    // this sink builds later, including the cluster's, start without it instead of stalling again.
    @Volatile private var vendorLowLatencyRefused = false

    private fun newVideoDecoder(type: Int, surface: Surface?, statsLabel: String? = null, startImmediately: Boolean = true): VideoDecoder {
        val cluster = type == 111 && clusterVideoWidth > 0 && clusterVideoHeight > 0
        lateinit var video: VideoDecoder
        video = VideoDecoder(
            type,
            surface,
            if (cluster) clusterVideoWidth else videoWidth,
            if (cluster) clusterVideoHeight else videoHeight,
            preferSoftwareHevcDecoder,
            requestKeyFrame = { requestVideoRecovery(type) },
            debugLogsEnabled = debugLogsEnabled,
            report = {
                val message = if (type == 111 && (statsLabel != null || videoDecoders[type] !== video)) "inactive decoder $it" else it
                videoDiagnosticHandlers[type]?.invoke(message)
            },
            statsLabel = statsLabel,
            onExit = ::onVideoDecoderExit,
            // Only the main screen goes to the host's SurfaceView; mirrors and the cluster keep their path.
            pacingDelayNanos = if (type == MAIN_SCREEN_TYPE && statsLabel == null) videoPacingDelayMillis * 1_000_000L else 0L,
            operatingRate = videoOperatingRate(type, statsLabel, mainVideoFrameRate),
            vendorLowLatency = vendorLowLatencyDecoder,
            vendorLowLatencyRefusedEarlier = vendorLowLatencyRefused,
            onVendorLowLatencyRefused = { vendorLowLatencyRefused = true },
            dedicatedOutputThread = vendorLowLatencyDecoder,
            framesAtOnce = type == MAIN_SCREEN_TYPE && statsLabel == null && videoPacingDelayMillis > 0,
            // Keep the experimental MTK mode on the main CarPlay picture only. Mirrors and the
            // cluster have separate timing and must retain their established decoder configuration.
            mtkDecoderTuningEnabled = mtkDecoderTuningEnabled && type == MAIN_SCREEN_TYPE && statsLabel == null,
            fanoutFactory = videoFanoutFactory,
            // Falls back to the Kotlin loop without the library (host tests) or before Android 9.
            nativeDecodeLoop = true,
        )
        if (startImmediately) video.start()
        return video
    }

    @Synchronized
    private fun audioRenderer(id: AudioStreamId, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[id]
        if (existing?.format == format) return existing
        existing?.close()
        // A replacement owns a fresh ring: the old worker can still finish a blocking write.
        // The car's phone audio state cancels the echo itself; a second canceller would work against it.
        val echoReference = if (callEchoCancellation && format.audioType == TELEPHONY_AUDIO_TYPE && format.sampleRate > 0 &&
            !PhoneAudioRoute.carPlayCallsUseCarAudio()) {
            EchoReference(format.sampleRate).also { callEchoReferences[id] = it }
        } else {
            callEchoReferences.remove(id)
            null
        }
        val followRoute = navigationFollowsIphone && isNavigationStream(format)
        val route = if (followRoute) navigationRoute() else navigationChannel
        if (followRoute) runCatching {
            onAudioDiagnostic("Audio: navigation route=${NavigationAudioRoute.label(route)} iphoneDuck=$iphoneDucking")
        }
        return AudioRenderer(
            format,
            advancedAudioChannelMapping,
            audioFocusEnabled,
            mediaChannel,
            route,
            audioFocusCoordinator,
            navigationStreamType,
            mediaBufferMillis,
            onAudioDiagnostic,
            speakerphoneCall = false,
            echoReference = echoReference,
            voiceFilter = callVoiceFilter && format.audioType == TELEPHONY_AUDIO_TYPE,
            navigationOemContent = navigationOemContent,
        ).also {
            it.debugLogsEnabled = debugLogsEnabled
            it.navigationPromptState = ::navigationPromptState
            it.onNavigationAudio = { audible ->
                // The car's volumes may have changed since the stream opened.
                if (audible) it.setNavigationAttenuationDb(navigationAttenuationFor(it.navigationRoute))
                navigationPromptFocus.onNavigationAudio(audible)
            }
            if (followRoute) {
                followingNavigationRenderer = it
                it.navigationRouteWanted = ::navigationRoute
                it.onNavigationRouteChanged = { moved -> it.setNavigationAttenuationDb(navigationAttenuationFor(moved)) }
            }
            it.setNavigationAttenuationDb(navigationAttenuationFor(route))
            if (format.audioType == TELEPHONY_AUDIO_TYPE) it.onRoutedToEarpiece = { moveCallToLoudspeaker(id) }
            audioRenderers[id] = it
            // Cover a Settings change made while this renderer was being prepared.
            it.setNavigationAttenuationDb(navigationAttenuationFor(route))
        }
    }

    private companion object {
        const val TELEPHONY_AUDIO_TYPE = "telephony"
    }
}

internal fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }
