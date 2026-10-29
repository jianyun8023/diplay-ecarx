package com.shilapi.xcertplay

import android.net.Uri
import com.shilapi.xcertplay.hud.EcarxOutputSettings
import com.shilapi.xcertplay.mediacenter.MediaCenterSessionEngine
import com.shilapi.xcertplay.mediacenter.EcarxMediaCenterTransport
import java.util.concurrent.TimeUnit
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.hud.EcarxCapabilities
import com.shilapi.xcertplay.hud.EcarxKeyBroadcasts
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * Publishes CarPlay through either Android MediaSession or ECARX MediaCenter.
 *
 * Android delivers media keys to a media session; some head units pick the session of the
 * audio-focus owner. When renderer focus is enabled, this session does not request music focus
 * again. Otherwise it holds focus until CarPlay ends, unless the media-key focus compatibility
 * setting disables that request. Keys go to the iPhone as CarPlay media HID presses
 * ([CarPlayMediaButton]). Only the selected media-session backend is published.
 */
internal object CarPlayMediaSession {
    private const val TAG = "DiPlay-MediaKeys"
    private const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS

    private val mainHandler = Handler(Looper.getMainLooper())
    private val artworkQueue = NowPlayingArtworkQueue(
        worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "diplay-now-playing-artwork").apply { isDaemon = true }
        },
        main = Executor { mainHandler.post(it) },
        decode = ::decodeAmbientArtwork,
        publish = ::onArtworkDecoded,
        discard = { it.bitmap.recycle() },
    )
    private var artworkOwner: Any? = null
    private val ambientColorCache = LinkedHashMap<Int, Int?>()
    private var initialAlbumRecovery = InitialAlbumRecovery()
    private var initialAlbumRetry: Runnable? = null
    private var controller: CarPlayController? = null
    private var session: MediaSession? = null
    private var focusRequest: AudioFocusRequestCompat? = null
    private var focusOwner: Any? = null
    private var focusEventRevision = 0L
    private var focusHeld = false
    /** Some car firmware never sends a gain callback after the phone call ends. */
    private var transientFocusInterrupted = false
    private var appContext: Context? = null
    private var mediaAudioActive = false
    private var androidActivation = "IDLE"
    // A single outstanding main-loop retry, never a Binder wait. Teardown cancels it.
    private val retryAndroidActivation = Runnable {
        synchronized(this) {
            if (controller != null && outputMode == MediaSessionMode.ANDROID && mediaAudioActive && session == null) {
                updateLocked(true)
            }
        }
    }
    private var nowPlaying = CarPlayNowPlaying()
    private var elapsedUpdatedAt = 0L
    private var artwork: Bitmap? = null
    private var artworkUri: Uri? = null
    @Volatile private var outputMode = MediaSessionMode.ANDROID
    private var clusterArtworkServer: ClusterArtworkServer? = null
    private var lastClusterArtworkReport = "Cluster artwork HTTP: not started"
    private var lastArtworkReceived = "none"
    private var lastArtworkDecoded = "none"
    private val clusterArtworkUris = LinkedHashMap<Int, android.net.Uri>()
    private val artworkCache = LinkedHashMap<Int, Bitmap?>()
    private var commandAttempts = 0L
    private var commandsSent = 0L
    private var lastCommand = "none"

    /** Fixed state only; do not export song titles, session tokens or device identifiers. */
    @Synchronized
    fun diagnosticReport(): String =
        "Android media session: controllerAttached=${controller != null} created=${session != null} " +
            "active=${session?.isActive == true} activation=$androidActivation mediaAudioActive=$mediaAudioActive focusHeld=$focusHeld " +
            "transientFocusInterrupted=$transientFocusInterrupted titlePresent=${!nowPlaying.title.isNullOrBlank()} " +
            "artistPresent=${!nowPlaying.artist.isNullOrBlank()} artworkDecoded=${artwork != null} " +
            "iphonePlaying=${nowPlaying.playing} elapsedMs=${nowPlaying.elapsedMillis} durationMs=${nowPlaying.durationMillis}\n" +
            "CarPlay media dispatch since process start: attempts=$commandAttempts sent=$commandsSent last=[$lastCommand]\n" +
            "ECARX voice key: shortPressBroadcastRegistered=${voiceKeyReceiver != null}"

    @Synchronized
    fun attach(context: Context, next: CarPlayController) {
        if (controller !== next) {
            releaseLocked()
            outputMode = MediaSessionSettings.mode(context)
            externalSiriKey.reset()
            artworkOwner = artworkQueue.newSession()
        }
        appContext = context.applicationContext
        controller = next
        if (EcarxCapabilities.detect(context).identified) EnvironmentBroadcastDiagnostics.start(context)
        if (outputMode == MediaSessionMode.ECARX) beginMediaCenter(context, next)
        GeelyWheelKeys.start(context)
        next.playbackListener = { playing ->
            onIphonePlaying(next, playing)
        }
        next.nowPlayingListener = { update ->
            synchronized(this) {
                if (controller === next) {
                    onNowPlayingChanged(next)
                    // The same metadata the car's media session shows is what an ECARX cluster's media card
                    // wants, so it is forwarded from here rather than read a second time off the controller.
                    publishMediaCenter(update, clusterArtworkUris[update.artworkTransferId], "metadata-received")
                }
            }
        }
        next.artworkListener = { id, bytes -> onArtworkChanged(next, id, bytes) }
        val retained = next.nowPlayingSnapshot()
        onNowPlayingChanged(next)
        next.matchingArtworkSnapshot(retained.artworkTransferId)?.let { onArtworkChanged(next, it.id, it.bytes) }
    }

    /** Ends key handling for [expected]; a newer controller's state is left alone. */
    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected == null || controller !== expected) return
        expected.playbackListener = null
        expected.nowPlayingListener = null
        expected.artworkListener = null
        controller = null
        GeelyWheelKeys.stop()
        releaseLocked()
    }

    /** Called when CarPlay music starts or stops; may run on any thread. */
    fun onMediaAudioChanged(active: Boolean) {
        mainHandler.post { synchronized(this) { updateLocked(active) } }
    }

    /** The iPhone started or stopped playing; may run on any thread. */
    private fun onIphonePlaying(expected: CarPlayController, playing: Boolean) {
        if (playing) mainHandler.post {
            synchronized(this) {
                if (controller === expected) regainFocusLocked(onIphonePlaybackResume = true)
            }
        }
    }

    /** Publishes the iPhone's retained metadata through Android's system media session. */
    private fun onNowPlayingChanged(expected: CarPlayController) {
        mainHandler.post {
            synchronized(this) {
                if (controller !== expected) return@synchronized
                // Resolve retained state at delivery: a queued callback cannot roll a replay back.
                val update = expected.nowPlayingSnapshot()
                val previousArtwork = artwork
                if (albumTrackChanged(nowPlaying, update)) {
                    val previousId = nowPlaying.artworkTransferId
                    if (previousId != null) {
                        ambientColorCache.remove(previousId)
                        if (previousId != update.artworkTransferId) {
                            artworkCache.remove(previousId)
                            clusterArtworkUris.remove(previousId)
                        }
                    }
                    if (previousId == update.artworkTransferId) {
                        // Metadata can arrive after its artwork. Keep the connection's decode token;
                        // cancelling it here loses a valid in-flight cover for a reused reference.
                        initialAlbumRecovery = InitialAlbumRecovery()
                    }
                    artwork = artworkCache[update.artworkTransferId]
                    artworkUri = clusterArtworkUris[update.artworkTransferId]
                }
                if (nowPlaying.elapsedMillis != update.elapsedMillis) elapsedUpdatedAt = SystemClock.elapsedRealtime()
                val metadataChanged = metadataChanged(nowPlaying, update) || artwork !== previousArtwork
                nowPlaying = update
                publishMediaCenter(update, clusterArtworkUris[update.artworkTransferId], "metadata-applied")
                if (metadataChanged) ClusterMusicState.publish(update, artwork)
                updateInitialAlbumRecovery(expected)
                // The iPhone repeats NowPlayingUpdate about twice a second for the position alone.
                // Republishing the metadata each time sent a copy of the artwork through system_server
                // to every media listener, and on one head unit that exhausted memory within
                // minutes. The position goes in the playback state.
                if (metadataChanged) {
                    session?.setMetadata(androidMetadata(update, artwork, artworkUri))
                }
                publishPlaybackStateLocked()
            }
        }
    }

    @Synchronized
    private fun onArtworkChanged(expected: CarPlayController, id: Int, bytes: ByteArray) {
        if (controller !== expected) return
        lastArtworkReceived = "id=$id bytes=${bytes.size} atEpochMs=${System.currentTimeMillis()}"
        artworkOwner?.let { artworkQueue.submit(it, id, bytes) }
    }

    @Synchronized
    private fun onArtworkDecoded(expected: Any, id: Int, result: AmbientArtwork?) {
        val decoded = result?.bitmap
        if (artworkOwner !== expected) {
            decoded?.recycle()
            return
        }
        lastArtworkDecoded = "id=$id decoded=${decoded != null} width=${decoded?.width ?: 0} height=${decoded?.height ?: 0} pngBytes=${result?.png?.size ?: 0}"
        // Empty/corrupt transfers are not a request to clear a valid cover. Session teardown
        // clears the image explicitly. Keep recovery eligible when decoding has failed.
        if (decoded == null) {
            Log.i(TAG, "artwork decode failed reference=$id; retaining cover")
            controller?.let(::updateInitialAlbumRecovery)
            return
        }
        clusterArtworkUris.remove(id)
        if (result != null) runCatching {
            val server = clusterArtworkServer ?: ClusterArtworkServer().also { clusterArtworkServer = it }
            clusterArtworkUris[id] = android.net.Uri.parse(server.publish(result.png))
            while (clusterArtworkUris.size > MAX_CACHED_ARTWORK) clusterArtworkUris.remove(clusterArtworkUris.keys.first())
        }.onFailure {
            lastClusterArtworkReport = "Cluster artwork HTTP: startup/publish failed=${it.javaClass.simpleName}"
            Log.w(TAG, "Cluster artwork HTTP handoff failed", it)
        }
        artworkCache.remove(id)
        artworkCache[id] = decoded
        ambientColorCache.remove(id)
        ambientColorCache[id] = result?.color
        while (ambientColorCache.size > MAX_CACHED_ARTWORK) ambientColorCache.remove(ambientColorCache.keys.first())
        while (artworkCache.size > MAX_CACHED_ARTWORK) artworkCache.remove(artworkCache.keys.first())
        if (nowPlaying.artworkTransferId == id) {
            artwork = decoded
            artworkUri = clusterArtworkUris[id]
            publishMediaCenter(nowPlaying, clusterArtworkUris[id], "artwork-decoded")
            ClusterMusicState.publish(nowPlaying, artwork)
            controller?.let(::updateInitialAlbumRecovery)
            session?.setMetadata(androidMetadata(nowPlaying, artwork, artworkUri))
        }
    }

    @Synchronized
    fun artworkDiagnosticReport(): String =
        "Cluster artwork: ownerActive=${controller != null} received=[$lastArtworkReceived] decoded=[$lastArtworkDecoded]\n" +
            (clusterArtworkServer?.diagnosticReport() ?: lastClusterArtworkReport)

    private fun hasCurrentAlbumColor(): Boolean =

        nowPlaying.artworkTransferId?.let { ambientColorCache.containsKey(it) } == true

    /** Cover art is retried after a reconnect while a context is available to ask on. */
    private fun albumRecoveryEnabled(): Boolean = appContext != null

    private fun logAlbumRecoveryEvents(recovery: InitialAlbumRecovery) {
        recovery.takeEvents().forEach { Log.i(TAG, "album recovery=$it") }
        if (!recovery.timerPending) {
            initialAlbumRetry?.let(mainHandler::removeCallbacks)
            initialAlbumRetry = null
        }
    }

    private fun updateInitialAlbumRecovery(expected: CarPlayController) {
        val recovery = initialAlbumRecovery
        val schedule = recovery.observe(nowPlaying, hasCurrentAlbumColor(), ::albumRecoveryEnabled)
        logAlbumRecoveryEvents(recovery)
        if (!schedule) return
        initialAlbumRetry?.let(mainHandler::removeCallbacks)
        initialAlbumRetry = null
        val token = recovery.pendingToken
        Log.i(TAG, "album recovery=scheduled reference=${nowPlaying.artworkTransferId}")
        val retry = Runnable {
            synchronized(this) {
                if (controller !== expected || initialAlbumRecovery !== recovery) return@Runnable
                val begin = recovery.beginRequest(token, nowPlaying, hasCurrentAlbumColor(), ::albumRecoveryEnabled)
                logAlbumRecoveryEvents(recovery)
                if (!begin) return@Runnable
                artworkQueue.execute(Runnable { expected.refreshInitialNowPlaying(
                    stillNeeded = {
                        synchronized(this) {
                            val needed = controller === expected && initialAlbumRecovery === recovery &&
                                recovery.stillNeeded(token, nowPlaying, hasCurrentAlbumColor(), ::albumRecoveryEnabled)
                            if (initialAlbumRecovery === recovery) logAlbumRecoveryEvents(recovery)
                            needed
                        }
                    },
                    beforeSend = {
                        synchronized(this) {
                            val claimed = controller === expected && initialAlbumRecovery === recovery &&
                                recovery.claimSend(token, nowPlaying, hasCurrentAlbumColor(), ::albumRecoveryEnabled)
                            if (initialAlbumRecovery === recovery) logAlbumRecoveryEvents(recovery)
                            if (claimed) Log.i(TAG, "album recovery=requested reference=${nowPlaying.artworkTransferId}")
                            claimed
                        }
                    },
                    onFinished = { result ->
                        synchronized(this) {
                            if (controller === expected && initialAlbumRecovery === recovery) {
                                recovery.finished(token)
                                val state = when (result) {
                                    CarPlayController.InitialAlbumRefreshResult.SENT -> "sent"
                                    CarPlayController.InitialAlbumRefreshResult.FAILED -> "failed"
                                    else -> "skipped reason=${result.name.lowercase()}"
                                }
                                Log.i(TAG, "album recovery=$state")
                                logAlbumRecoveryEvents(recovery)
                            }
                        }
                    },
                ) })
            }
        }
        initialAlbumRetry = retry
        mainHandler.postDelayed(retry, 2000)
    }

    // Another car app (its own Spotify, the radio) took audio focus and with it the steering-wheel
    // keys. When CarPlay starts playing again it becomes the car's media source again, as any player
    // would; only the start counts, so a car source picked while the iPhone plays on is not undone.
    private fun regainFocusLocked() = regainFocusLocked(onIphonePlaybackResume = false)

    private fun regainFocusLocked(onIphonePlaybackResume: Boolean) {
        val request = focusRequest ?: return
        // Do not compete with an ongoing call. Only a fresh iPhone play transition may
        // retry a transient loss for which the head unit never sent AUDIOFOCUS_GAIN.
        if (transientFocusInterrupted && !onIphonePlaybackResume) return
        if (focusHeld && !transientFocusInterrupted) return
        val audio = appContext?.getSystemService(AudioManager::class.java) ?: return
        focusHeld = request.request(audio) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (focusHeld) {
            transientFocusInterrupted = false
            forwardGrantedFocusLocked()
        }
        Log.i(TAG, "audio focus regained=$focusHeld")
    }

    private fun updateLocked(active: Boolean) {
        val context = appContext ?: return
        if (controller == null) return
        mediaAudioActive = active
        mainHandler.removeCallbacks(retryAndroidActivation)
        if (outputMode == MediaSessionMode.ANDROID) {
            if (active && session == null) {
                if (mediaCenterEngine.cleanForOtherBackend()) {
                    start(context)
                    androidActivation = "ACTIVE"
                } else {
                    val cleanup = mediaCenterEngine.snapshot()
                    val restartRequired = cleanup.uncertain && cleanup.phase == "CLEANUP_UNCERTAIN"
                    androidActivation = when {
                        restartRequired -> "BLOCKED_ECARX_RESTART_REQUIRED"
                        cleanup.uncertain -> "BLOCKED_ECARX_CLEANUP_UNCERTAIN"
                        else -> "WAITING_ECARX_CLEANUP"
                    }
                    // A timed-out IPC can still return a token that must be cleaned. A completed,
                    // rejected cleanup cannot heal itself: retain the block without polling forever.
                    if (!restartRequired) mainHandler.postDelayed(retryAndroidActivation, 1_000L)
                }
            } else if (active) regainFocusLocked()
            else if (session == null) androidActivation = "IDLE"
        }
        publishPlaybackStateLocked()
    }

    private fun start(context: Context) {
        val expectedController = controller ?: return
        val rendererOwnsFocus = AirPlayPersistence.loadAudioFocusEnabled(context)
        val focusDisabled = rendererOwnsFocus || AirPlayPersistence.loadDisableMediaKeyAudioFocus(context)
        transientFocusInterrupted = false
        val granted = if (focusDisabled) {
            // Some OEMs route physical volume buttons to the focus owner. Keep an active
            // MediaSession without permanent focus so those buttons remain vehicle-owned.
            focusOwner = null
            focusRequest = null
            focusHeld = false
            false
        } else {
            val owner = Any().also { focusOwner = it }
            focusEventRevision = 0L
            val audio = context.getSystemService(AudioManager::class.java)
            val request = AudioFocusRequestCompat(
                AudioManager.AUDIOFOCUS_GAIN,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                { change -> onFocusChanged(expectedController, owner, change) },
                mainHandler,
            )
            focusRequest = request
            (audio?.let(request::request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED).also {
                focusHeld = it
                if (it) forwardGrantedFocusLocked()
            }
        }
        session = MediaSession(context, "DiPlay CarPlay").apply {
            setCallback(callback, mainHandler)
            setMetadata(androidMetadata(nowPlaying, artwork, artworkUri))
            isActive = true
        }
        Log.i(TAG, "media keys active focusDisabled=$focusDisabled focusGranted=$granted rendererOwnsFocus=$rendererOwnsFocus")
        WheelKeyLog.note("media keys active focusDisabled=$focusDisabled focusGranted=$granted rendererOwnsFocus=$rendererOwnsFocus")
    }

    private fun forwardGrantedFocusLocked() {
        val expectedController = controller ?: return
        val owner = focusOwner ?: return
        val revision = focusEventRevision
        // Immediate grants do not promise a later focus callback. Defer dispatch until the caller
        // releases the media-key monitor. A newer real focus event invalidates this observation,
        // as do a controller or request replacement while the queued work waits.
        mainHandler.post { onFocusChanged(expectedController, owner, AudioManager.AUDIOFOCUS_GAIN, revision) }
    }

    private fun onFocusChanged(expectedController: CarPlayController, owner: Any, change: Int,
        grantedRevision: Long? = null) {
        val current = synchronized(this) {
            if (controller !== expectedController || focusOwner !== owner ||
                (grantedRevision != null && grantedRevision != focusEventRevision)) false
            else {
                focusEventRevision += 1
                // Only permanent loss moves media keys elsewhere; transient losses come back.
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        focusHeld = false
                        transientFocusInterrupted = false
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> transientFocusInterrupted = true
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        focusHeld = true
                        transientFocusInterrupted = false
                    }
                }
                true
            }
        }
        if (!current) return
        Log.i(TAG, "audio focus change=$change")
        WheelKeyLog.note("media keys audio focus change=$change")
        // Resolve the matching sink and invoke it outside the media-key monitor. An abandoned
        // request must never mute a newer controller, and these owners must not nest locks.
        val background = CarPlayBackgroundSession.snapshot()
        if (background?.controller === expectedController) background.sink.onMediaAudioFocusChanged(change)
    }

    private fun releaseLocked() {
        mainHandler.removeCallbacks(retryAndroidActivation)
        androidActivation = "IDLE"
        EnvironmentBroadcastDiagnostics.stop()
        releaseVoiceKeyLocked()
        endMediaCenter()
        outputMode = MediaSessionMode.ANDROID
        focusOwner = null
        artworkOwner = null
        initialAlbumRetry?.let(mainHandler::removeCallbacks)
        initialAlbumRetry = null
        initialAlbumRecovery = InitialAlbumRecovery()
        ambientColorCache.clear()
        artworkQueue.clear()
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        mediaAudioActive = false
        nowPlaying = CarPlayNowPlaying()
        // Whatever the cluster's media card was showing belongs to a session that just ended. The
        // controller pushes its own cleared frame on teardown, but that goes through the listener
        // this method may itself be clearing, so the blank state is pushed from here as well.
        publishMediaCenter(nowPlaying, reason = "clear")
        artwork = null
        artworkUri = null
        artworkCache.clear()
        clusterArtworkUris.clear()
        clusterArtworkServer?.let {
            it.close()
            lastClusterArtworkReport = it.diagnosticReport()
        }
        clusterArtworkServer = null
        ClusterMusicState.publish(CarPlayNowPlaying(), null)
        focusRequest?.let { request -> appContext?.getSystemService(AudioManager::class.java)?.let(request::abandon) }
        focusRequest = null
        focusHeld = false
        transientFocusInterrupted = false
    }

    private fun publishPlaybackStateLocked() {
        val playing = if (nowPlaying.elapsedMillis != null || nowPlaying.title != null) {
            nowPlaying.playing
        } else {
            mediaAudioActive
        }
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    nowPlaying.elapsedMillis ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f,
                    // The iPhone sends elapsed time only on play, pause or seek, so Android must
                    // extrapolate from when it arrived, not from this republish.
                    elapsedUpdatedAt,
                )
                .build(),
        )
    }

    private fun send(index: Int, source: String) {
        // While the car's video player is on screen the wheel drives it: a CarPlay play/pause would
        // make the iPhone end the video session.
        if (CarPlayVideo.onMediaKey(index)) {
            Log.i(TAG, "media key $source -> car video player $index")
            WheelKeyLog.note("media key $source -> car video player $index")
            return
        }
        val sent = synchronized(this) { controller }?.sendMediaButton(index) ?: false
        synchronized(this) {
            commandAttempts++
            if (sent) commandsSent++
            lastCommand = "source=$source index=$index sent=$sent atEpochMs=${System.currentTimeMillis()}"
        }
        Log.i(TAG, "media key $source -> CarPlay $index sent=$sent")
        WheelKeyLog.note("media key $source -> CarPlay $index sent=$sent")
    }

    private fun notUsed(source: String) {
        Log.i(TAG, "media key $source not used by CarPlay")
        WheelKeyLog.note("media key $source not used by CarPlay")
    }

    /** A steering-wheel media key captured outside the Android key stream (Geely/Lynk). */
    internal fun onExternalMediaKey(index: Int) {
        if (managesVehicleKeys() && !isMediaCenterActive()) send(index, "geely-wheel")
    }

    private fun onMediaCenterKey(expected: CarPlayController, index: Int) {
        if (synchronized(this) { controller === expected }) send(index, "mediacenter")
    }

    /** The steering-wheel microphone key captured outside the Android key stream (Geely/Lynk). */
    private val externalSiriKey = WheelSiriKey()
    private var voiceKeyReceiver: BroadcastReceiver? = null
    private var voiceKeySession: AirPlaySession? = null
    private var delayedVoiceSiri: Runnable? = null

    @Synchronized
    internal fun onSessionActive(expected: CarPlayController, session: AirPlaySession) {
        if (controller !== expected || expected.activeAirPlaySession() !== session || voiceKeySession === session) return
        releaseVoiceKeyLocked()
        val context = appContext ?: return
        if (!EcarxCapabilities.detect(context).identified) return
        voiceKeySession = session
        externalSiriKey.reset()
        voiceKeyReceiver = runCatching {
            EcarxKeyBroadcasts.registerVoice(context, shouldHandle = {
                synchronized(this) {
                    val eligible = controller === expected && voiceKeySession === session &&
                        expected.activeAirPlaySession() === session && !inCall(context)
                    if (!eligible) {
                        externalSiriKey.reset()
                        delayedVoiceSiri?.let(mainHandler::removeCallbacks)
                        delayedVoiceSiri = null
                    }
                    eligible
                }
            }) { ordered ->
                synchronized(this) {
                    // An unordered broadcast cannot suppress the factory assistant.
                    if (!ordered) {
                        WheelKeyLog.note("ECARX voice short press ordered=false queued=false")
                        return@synchronized
                    }
                    val assignedVoice = WheelZoomSettings.key(context, WheelZoomSettings.Role.SIRI)?.takeIf {
                        WheelZoomSettings.siriKey(context) && CarPlayMediaButton.opensSiri(it.code)
                    }
                    if (assignedVoice != null && WheelZoomSettings.siriDoublePress(context)) {
                        val interval = WheelZoomSettings.siriDoublePressIntervalMillis(context)
                        val action = externalSiriKey.onDoublePress(SystemClock.uptimeMillis(), interval.toLong())
                        if (action == WheelSiriKey.DoublePressAction.CONFIRMED) {
                            val delay = WheelZoomSettings.siriTriggerDelayMillis(context)
                            delayedVoiceSiri?.let(mainHandler::removeCallbacks)
                            val request = Runnable {
                                synchronized(this) delayed@{
                                    delayedVoiceSiri = null
                                    if (controller !== expected || voiceKeySession !== session ||
                                        expected.activeAirPlaySession() !== session || inCall(context) ||
                                        !WheelZoomSettings.isSiriKey(context, assignedVoice) ||
                                        !WheelZoomSettings.siriDoublePress(context)) return@delayed
                                    val queued = expected.requestSiri()
                                    WheelKeyLog.note("ECARX voice double press queued=$queued intervalMs=$interval delayMs=$delay")
                                }
                            }
                            delayedVoiceSiri = request
                            mainHandler.postDelayed(request, delay.toLong())
                        }
                        WheelKeyLog.note("ECARX voice double press action=$action")
                    } else {
                        delayedVoiceSiri?.let(mainHandler::removeCallbacks)
                        delayedVoiceSiri = null
                        val queued = externalSiriKey.opens(SystemClock.uptimeMillis()) && expected.requestSiri()
                        WheelKeyLog.note("ECARX voice short press ordered=true queued=$queued")
                    }
                }
            }
        }.onFailure {
            WheelKeyLog.note("ECARX voice registration failed=${it.javaClass.simpleName}")
        }.getOrNull()
        if (voiceKeyReceiver == null) voiceKeySession = null
        WheelKeyLog.note("ECARX voice short press registered=${voiceKeyReceiver != null} priority=${Int.MAX_VALUE}")
    }

    @Synchronized
    internal fun onSessionEnded(session: AirPlaySession) {
        if (voiceKeySession === session) releaseVoiceKeyLocked()
    }

    @Synchronized
    internal fun ecarxVoiceKeyActive(): Boolean = voiceKeyReceiver != null

    private fun releaseVoiceKeyLocked() {
        delayedVoiceSiri?.let(mainHandler::removeCallbacks)
        delayedVoiceSiri = null
        externalSiriKey.reset()
        voiceKeyReceiver?.let { receiver ->
            runCatching { appContext?.unregisterReceiver(receiver) }
                .onSuccess { WheelKeyLog.note("ECARX voice short press unregistered") }
                .onFailure { WheelKeyLog.note("ECARX voice unregister failed=${it.javaClass.simpleName}") }
        }
        voiceKeyReceiver = null
        voiceKeySession = null
    }

    @Synchronized
    internal fun onExternalSiriKey(keyCode: Int = CarPlayMediaButton.ECARX_VOICE_200231): Boolean {
        val active = controller ?: return false
        if (active.activeAirPlaySession() == null) return false
        if (voiceKeyReceiver != null && CarPlayMediaButton.opensSiri(keyCode)) return false
        if (appContext?.let(::inCall) == true) return false
        // One wheel press can reach both the ECARX callback and the Geely listener.
        if (!externalSiriKey.opens(android.os.SystemClock.uptimeMillis())) return false
        return runCatching { active.requestSiri() }.getOrDefault(false)
    }

    private val callback = CarPlayMediaCallback(notUsed = ::notUsed, send = ::send)

    /** Whether [next] changes what the media session's metadata shows; position and play state do not. */
    internal fun metadataChanged(previous: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        previous.copy(elapsedMillis = null, playing = false) != next.copy(elapsedMillis = null, playing = false)

    internal fun androidMetadata(info: CarPlayNowPlaying, artwork: Bitmap? = null, artworkUri: Uri? = null): MediaMetadata =
        MediaMetadata.Builder().apply {
            info.title?.let {
                putString(MediaMetadata.METADATA_KEY_TITLE, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, it)
            }
            info.artist?.let {
                putString(MediaMetadata.METADATA_KEY_ARTIST, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it)
            }
            info.album?.let { putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
            info.durationMillis?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
            info.sourceApp?.let { putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, it) }
            artwork?.let {
                putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
            }
            artworkUri?.toString()?.let {
                putString(MediaMetadata.METADATA_KEY_ART_URI, it)
                putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, it)
            }
        }.build()

    private data class AmbientArtwork(val bitmap: Bitmap, val color: Int?, val png: ByteArray)

    private fun decodeAmbientArtwork(bytes: ByteArray): AmbientArtwork? {
        val bitmap = decodeArtwork(bytes) ?: return null
        val png = java.io.ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                bitmap.recycle()
                return null
            }
            output.toByteArray()
        }
        return AmbientArtwork(bitmap, null, png)
    }

    private fun decodeArtwork(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_ARTWORK_SOURCE_DIMENSION ||
            bounds.outHeight !in 1..MAX_ARTWORK_SOURCE_DIMENSION
        ) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_ARTWORK_DIMENSION * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        return squareArtwork(decoded).also { square -> if (square !== decoded) decoded.recycle() }
    }

    /**
     * Fits [source] inside a transparent square of at most [MAX_ARTWORK_DIMENSION]. Car clusters draw
     * art in a square box and stretch it, so 16:9 video thumbnails looked squashed.
     */
    internal fun squareArtwork(source: Bitmap): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (source.width == source.height && largest <= MAX_ARTWORK_DIMENSION) return source
        val side = minOf(largest, MAX_ARTWORK_DIMENSION)
        val scale = side.toFloat() / largest
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val left = (side - width) / 2
        val top = (side - height) / 2
        return Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { square ->
            Canvas(square).drawBitmap(
                source,
                null,
                Rect(left, top, left + width, top + height),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
    }

    private const val MAX_ARTWORK_DIMENSION = 384
    private const val MAX_ARTWORK_SOURCE_DIMENSION = 8_192
    private const val MAX_CACHED_ARTWORK = 4
    // Keep MediaCenter lifecycle serialization separate from the Android media-session monitor.
    private val mediaCenterLock = Any()
    private var lastMediaPublication: CarPlayNowPlaying? = null
    private var lastMediaArtwork: Uri? = null
    private var mediaPublicationCount = 0L
    private var mediaProgressOnlyCount = 0L
    private val mediaPublicationEvents = ArrayDeque<String>()
    private data class MediaCenterOwner(val context: Context, val controller: CarPlayController)
    @Volatile private var mediaCenterOwner: MediaCenterOwner? = null
    private val mediaCenterMain = Handler(Looper.getMainLooper())
    private val mediaCenterWorker = Executors.newSingleThreadExecutor { Thread(it, "diplay-mediacenter").apply { isDaemon = true } }
    private val mediaCenterTimer = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-mediacenter-watch").apply { isDaemon = true } }
    private val mediaCenterEngine = MediaCenterSessionEngine(mediaCenterWorker, { identity, valid ->
        val entry = identity as MediaCenterOwner
        EcarxMediaCenterTransport(entry.context, { command -> dispatchMediaCenter(entry, command) }, valid)
    })
    private var mediaCenterTicking = false

    private fun beginMediaCenter(context: Context, controller: CarPlayController): Unit = synchronized(mediaCenterLock) {
        endMediaCenter()
        val entry = MediaCenterOwner(context.applicationContext, controller)
        mediaCenterOwner = entry
        lastMediaPublication = null
        lastMediaArtwork = null
        if (!mediaCenterTicking) {
            mediaCenterTicking = true
            mediaCenterTimer.scheduleAtFixedRate({ mediaCenterEngine.tick() }, 1, 1, TimeUnit.SECONDS)
        }
    }

    private fun endMediaCenter(): Unit = synchronized(mediaCenterLock) {
        val previous = mediaCenterOwner ?: return@synchronized
        val event = "elapsedMs=${SystemClock.elapsedRealtime()} reason=owner-stop artwork=${lastMediaArtwork != null}->false"
        while (mediaPublicationEvents.size >= 32) mediaPublicationEvents.removeFirst()
        mediaPublicationEvents.addLast(event)
        Log.i(TAG, "ECARX media request $event")
        mediaCenterOwner = null
        mediaCenterEngine.stop(previous)
    }

    private fun publishMediaCenter(now: CarPlayNowPlaying, artwork: Uri? = null, reason: String = "metadata"): Unit = synchronized(mediaCenterLock) {
        val entry = mediaCenterOwner ?: return@synchronized
        val previous = lastMediaPublication
        val trackChanged = previous != null && albumTrackChanged(previous, now)
        val artworkChanged = artwork != lastMediaArtwork
        mediaPublicationCount++
        if (previous != null && !metadataChanged(previous, now) && previous.playing == now.playing &&
            !artworkChanged && previous.elapsedMillis != now.elapsedMillis) {
            mediaProgressOnlyCount++
        }
        // Requests may coalesce before Binder publication; never log URI contents or track text.
        if (previous == null || trackChanged || artworkChanged || reason == "clear") {
            val event = "elapsedMs=${SystemClock.elapsedRealtime()} reason=$reason trackChanged=$trackChanged " +
                "reference=${now.artworkTransferId ?: -1} artwork=${lastMediaArtwork != null}->${artwork != null} " +
                "uriChanged=$artworkChanged playing=${now.playing}"
            while (mediaPublicationEvents.size >= 32) mediaPublicationEvents.removeFirst()
            mediaPublicationEvents.addLast(event)
            Log.i(TAG, "ECARX media request $event")
        }
        lastMediaPublication = now
        lastMediaArtwork = artwork
        mediaCenterEngine.update(MediaCenterSessionEngine.Desired(entry, now, artwork) {
            mediaCenterOwner === entry && outputMode == MediaSessionMode.ECARX && EcarxOutputSettings.mediaEnabled(entry.context) &&
                CarPlayBackgroundSession.active &&
                CarPlayBackgroundSession.snapshot()?.controller === entry.controller
        })
    }

    internal fun managesVehicleKeys(): Boolean = outputMode == MediaSessionMode.ECARX

    fun isMediaCenterActive(controller: CarPlayController? = null): Boolean {
        val entry = mediaCenterOwner ?: return false
        return (controller == null || entry.controller === controller) &&
            mediaCenterEngine.ownsMediaKeys(entry)
    }

    private fun dispatchMediaCenter(entry: MediaCenterOwner, command: Int): Boolean {
        if (mediaCenterOwner !== entry || !mediaCenterEngine.ownsCallbacks(entry)) return false
        if (command == EcarxMediaCenterTransport.SELECT_SOURCE && !mediaCenterEngine.canSelect(entry)) return false
        mediaCenterMain.post {
            if (mediaCenterOwner !== entry || !mediaCenterEngine.ownsCallbacks(entry)) return@post
            val index = if (command == EcarxMediaCenterTransport.SELECT_SOURCE) {
                mediaCenterEngine.select(entry)
                CarPlayMediaButton.PLAY
            } else command
            onMediaCenterKey(entry.controller, index)
        }
        return true
    }

    /** Fixed state only: never a token, device identifier, track title, or Binder object string. */
    fun mediaCenterDiagnosticReport(): String = synchronized(mediaCenterLock) {
        val current = mediaCenterOwner
        val state = mediaCenterEngine.snapshot()
        "ECARX MediaCenter session=${if (current == null) "NO_SESSION" else "ACTIVE"} " +
            "phase=${state.phase} reason=${state.detail} registered=${state.registered} " +
            "playbackAccepted=${state.accepted} playClaimed=${state.claimed} artworkSupported=${state.artworkSupported} uncertain=${state.uncertain}\n" +
            "Media output active=$outputMode saved=${appContext?.let(MediaSessionSettings::mode)}\n" +
            "ECARX media requests=$mediaPublicationCount progressOnly=$mediaProgressOnlyCount (requests may coalesce)\n" +
            mediaPublicationEvents.joinToString("\n") + "\n" + EnvironmentBroadcastDiagnostics.report()
    }
}

/**
 * Media-session input → CarPlay presses. Hardware keys arrive as button events and keep the toggle;
 * media controllers (not hardware keys) call [onPlay] and [onPause] with an explicit intent.
 * A button that is not a CarPlay key goes to [notUsed]: a car that sends its own code for a wheel key
 * then shows that code in the report, instead of looking like a car that sent nothing (#610).
 */
internal class CarPlayMediaCallback(
    private val notUsed: (source: String) -> Unit = {},
    private val send: (index: Int, source: String) -> Unit,
) : MediaSession.Callback() {
    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        val press = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        val index = CarPlayMediaButton.forKeyCode(event.keyCode)
        if (index == null) {
            if (press) notUsed(KeyEvent.keyCodeToString(event.keyCode))
            return super.onMediaButtonEvent(mediaButtonIntent)
        }
        if (press) send(index, KeyEvent.keyCodeToString(event.keyCode))
        return true
    }

    override fun onPlay() = send(CarPlayMediaButton.PLAY, "play")
    override fun onPause() = send(CarPlayMediaButton.PAUSE, "pause")
    override fun onSkipToNext() = send(CarPlayMediaButton.NEXT, "next")
    override fun onSkipToPrevious() = send(CarPlayMediaButton.PREVIOUS, "previous")

}
