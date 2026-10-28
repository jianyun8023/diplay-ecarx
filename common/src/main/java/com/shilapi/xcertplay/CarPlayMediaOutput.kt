package com.shilapi.xcertplay

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.hud.EcarxOutputSettings
import com.shilapi.xcertplay.hud.MediaOutputBackendSettings
import com.shilapi.xcertplay.hud.NavigationOutputs
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.mediacenter.MediaCenterSessionEngine
import com.shilapi.xcertplay.mediacenter.UniMediaCenterTransport
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The only media-output router. This does not route navigation or alter the Android media session. */
internal object CarPlayMediaOutput {
    private data class Owner(val context: Context, val controller: CarPlayController,
        val backend: MediaOutputBackendSettings.Backend) {
        @Volatile var latest: Pair<CarPlayNowPlaying, Uri?>? = null
    }
    @Volatile private var owner: Owner? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "diplay-mediacenter").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-mediacenter-watch").apply { isDaemon = true } }
    private val engine = MediaCenterSessionEngine(worker, { identity, valid ->
        val entry = identity as Owner
        UniMediaCenterTransport(entry.context, { command -> dispatch(entry, command) }, valid)
    })
    private var ticking = false

    @Synchronized fun begin(context: Context, controller: CarPlayController) {
        end()
        val entry = Owner(context.applicationContext, controller, MediaOutputBackendSettings.load(context))
        owner = entry
        MediaOutputBackendSettings.blockDim()
        MediaOutputBackendSettings.begin(entry, entry.backend)
        if (!ticking) {
            ticking = true
            timer.scheduleAtFixedRate({ refreshDimBarrier(); engine.tick() }, 1, 1, TimeUnit.SECONDS)
        }
    }

    @Synchronized fun end() {
        val previous = owner ?: return
        owner = null
        engine.stop(previous)
        if (previous.backend == MediaOutputBackendSettings.Backend.DIM) NavigationOutputs.onNowPlaying(CarPlayNowPlaying())
        MediaOutputBackendSettings.end(previous)
    }

    @Synchronized fun publish(now: CarPlayNowPlaying, artwork: Uri? = null) {
        val entry = owner ?: return
        if (entry.backend == MediaOutputBackendSettings.Backend.DIM) {
            entry.latest = now to artwork
            if (!MediaOutputBackendSettings.dimBlocked()) NavigationOutputs.onNowPlaying(now, artwork)
        } else if (entry.backend == MediaOutputBackendSettings.Backend.UNI_MEDIACENTER) {
            engine.update(MediaCenterSessionEngine.Desired(entry, now, artwork) {
                owner === entry && EcarxOutputSettings.mediaEnabled(entry.context) &&
                    CarPlayBackgroundSession.active &&
                    CarPlayBackgroundSession.snapshot()?.controller === entry.controller
            })
        }
    }

    /** Runs on the process timer, never waits for Binder or a cleanup on the UI thread. */
    private fun refreshDimBarrier() = refreshDimBarrier(NavigationOutputs::mediaBackendReady)

    private fun refreshDimBarrier(initialize: (Context) -> Unit) {
        val current = synchronized(this) {
            val observed = owner
            if (observed?.backend != MediaOutputBackendSettings.Backend.DIM ||
                !MediaOutputBackendSettings.dimBlocked() || !engine.cleanForDim()) return
            MediaOutputBackendSettings.releaseDimBarrier()
            observed
        }
        // Never hold the router monitor while entering an OEM bridge.
        if (current != null && owner === current && !MediaOutputBackendSettings.dimBlocked()) {
            initialize(current.context)
            // Initialization can block. Re-check ownership and take the newest frame only after it
            // returns, ordered with publish/end/begin; an old timer must not overwrite a new song.
            synchronized(this) {
                if (owner === current && !MediaOutputBackendSettings.dimBlocked()) {
                    current.latest?.let { NavigationOutputs.onNowPlaying(it.first, it.second) }
                }
            }
        }
    }

    fun isMediaCenterActive(controller: CarPlayController? = null): Boolean {
        val entry = owner ?: return false
        return (controller == null || entry.controller === controller) &&
            entry.backend == MediaOutputBackendSettings.Backend.UNI_MEDIACENTER && engine.ownsMediaKeys(entry)
    }

    private fun dispatch(entry: Owner, command: Int): Boolean {
        if (owner !== entry || !engine.ownsCallbacks(entry)) return false
        if (command == UniMediaCenterTransport.SELECT_SOURCE && !engine.canSelect(entry)) return false
        main.post {
            if (owner !== entry || !engine.ownsCallbacks(entry)) return@post
            val index = if (command == UniMediaCenterTransport.SELECT_SOURCE) {
                engine.select(entry)
                CarPlayMediaButton.PLAY
            } else command
            CarPlayMediaKeys.onMediaCenterKey(entry.controller, index)
        }
        return true
    }

    /** Fixed state only: never a token, device identifier, track title, or Binder object string. */
    fun diagnostics(): String {
        val current = owner
        val state = engine.snapshot()
        return "Media output backend=${current?.backend ?: "NO_SESSION"} " +
            "dimBlocked=${MediaOutputBackendSettings.dimBlocked()} phase=${state.phase} reason=${state.detail} registered=${state.registered} " +
            "playbackAccepted=${state.accepted} playClaimed=${state.claimed} artworkSupported=${state.artworkSupported} uncertain=${state.uncertain}; " +
            "UNI failures do not fall back to DIM"
    }
}
