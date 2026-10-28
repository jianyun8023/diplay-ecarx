package com.shilapi.xcertplay

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.hud.EcarxOutputSettings
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.mediacenter.MediaCenterSessionEngine
import com.shilapi.xcertplay.mediacenter.EcarxMediaCenterTransport
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The only media-output router. This does not route navigation or alter the Android media session. */
internal object CarPlayMediaOutput {
    private data class Owner(val context: Context, val controller: CarPlayController)
    @Volatile private var owner: Owner? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "diplay-mediacenter").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-mediacenter-watch").apply { isDaemon = true } }
    private val engine = MediaCenterSessionEngine(worker, { identity, valid ->
        val entry = identity as Owner
        EcarxMediaCenterTransport(entry.context, { command -> dispatch(entry, command) }, valid)
    })
    private var ticking = false

    @Synchronized fun begin(context: Context, controller: CarPlayController) {
        end()
        val entry = Owner(context.applicationContext, controller)
        owner = entry
        if (!ticking) {
            ticking = true
            timer.scheduleAtFixedRate({ engine.tick() }, 1, 1, TimeUnit.SECONDS)
        }
    }

    @Synchronized fun end() {
        val previous = owner ?: return
        owner = null
        engine.stop(previous)
    }

    @Synchronized fun publish(now: CarPlayNowPlaying, artwork: Uri? = null) {
        val entry = owner ?: return
        engine.update(MediaCenterSessionEngine.Desired(entry, now, artwork) {
            owner === entry && EcarxOutputSettings.mediaEnabled(entry.context) &&
                CarPlayBackgroundSession.active &&
                CarPlayBackgroundSession.snapshot()?.controller === entry.controller
        })
    }

    fun isMediaCenterActive(controller: CarPlayController? = null): Boolean {
        val entry = owner ?: return false
        return (controller == null || entry.controller === controller) &&
            engine.ownsMediaKeys(entry)
    }

    private fun dispatch(entry: Owner, command: Int): Boolean {
        if (owner !== entry || !engine.ownsCallbacks(entry)) return false
        if (command == EcarxMediaCenterTransport.SELECT_SOURCE && !engine.canSelect(entry)) return false
        main.post {
            if (owner !== entry || !engine.ownsCallbacks(entry)) return@post
            val index = if (command == EcarxMediaCenterTransport.SELECT_SOURCE) {
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
        return "ECARX MediaCenter session=${if (current == null) "NO_SESSION" else "ACTIVE"} " +
            "phase=${state.phase} reason=${state.detail} registered=${state.registered} " +
            "playbackAccepted=${state.accepted} playClaimed=${state.claimed} artworkSupported=${state.artworkSupported} uncertain=${state.uncertain}"
    }
}
