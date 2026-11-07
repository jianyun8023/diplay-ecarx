package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat

/** The attributes the navigation renderer plays with, for a focus request made before its track exists. */
internal fun navigationRouteAttributes(navigationChannel: Int, oemContent: Boolean = false): AudioAttributes =
    AudioRouteAttributes.navigation(navigationChannel, oemContent)

/**
 * Opt-in audio focus for navigation prompts (#946, #545). Navigation audio takes no focus by default,
 * so a prompt plays over music without ducking it. With the option on, this holds a duckable temporary
 * request with the navigation route's attributes while a prompt plays. A head unit that plays only the
 * focus owner's sound then plays the prompt, and other sound can duck instead of being muted. Nothing
 * is requested while the Audio focus setting is off.
 *
 * A prompt is the iPhone's duckAudio … unduckAudio, or a navigation audio stream that is open. The
 * iPhone sends no duckAudio when the navigation app is set to mix with music (AMap "coexist"); the
 * stream alone then holds the focus, or the car mutes the prompt (v129 car test, 2026-11-07: 56
 * prompts without focus, none of them heard).
 */
internal class NavigationPromptFocus(
    context: Context?,
    private val promptFocusEnabled: Boolean,
    private val audioFocusEnabled: Boolean,
    private val attributes: () -> AudioAttributes,
    private val report: (String) -> Unit = {},
    private val releaseDelayMillis: Long = RELEASE_DELAY_MILLIS,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
    private val streamTimeoutMillis: Long = STREAM_TIMEOUT_MILLIS,
) {
    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        runCatching { report("Audio: navigation prompt focus change=$change") }
    }
    private var request: AudioFocusRequestCompat? = null
    private var pendingRelease: Runnable? = null
    private var skipReported = false
    private var closed = false
    private var iphoneDucking = false
    private var navigationStreams = 0

    @Synchronized
    fun onDuck() {
        if (closed || !promptFocusEnabled) return
        iphoneDucking = true
        if (!acquire("duck")) return
        // Each duck restarts the timeout, and a duck right after an unduck keeps the same request.
        scheduleRelease("timeout", if (navigationStreams > 0) streamTimeoutMillis else timeoutMillis)
    }

    /** The prompt's last audio is still in DiPlay's buffers when the iPhone sends unduckAudio. */
    @Synchronized
    fun onUnduck() {
        iphoneDucking = false
        if (closed || request == null) return
        // An open navigation stream keeps the prompt going; its end releases the focus.
        if (navigationStreams == 0) scheduleRelease("unduck", releaseDelayMillis)
    }

    /** A navigation audio track started (true) or closed (false). */
    @Synchronized
    fun onNavigationStream(active: Boolean) {
        navigationStreams = (navigationStreams + if (active) 1 else -1).coerceAtLeast(0)
        if (closed || !promptFocusEnabled) return
        if (active) {
            if (!acquire("stream")) return
            // Streams lasted 1.3–32 s on v129; this only bounds a stream that never closes.
            scheduleRelease("timeout", streamTimeoutMillis)
        } else if (navigationStreams == 0 && !iphoneDucking && request != null) {
            scheduleRelease("stream-end", releaseDelayMillis)
        }
    }

    /** Holds the request, asking for it if needed; false when it is not held. */
    private fun acquire(trigger: String): Boolean {
        if (!audioFocusEnabled) {
            if (!skipReported) {
                skipReported = true
                runCatching { report("Audio: navigation prompt focus skipped audioFocus=off") }
            }
            return false
        }
        val manager = manager ?: return false
        return request != null || requestFocus(manager, trigger)
    }

    /**
     * For the per-prompt log line: whether this prompt plays with its own focus. "none" with the
     * option on means the iPhone sent no duckAudio for it, as when the navigation app mixes with music.
     */
    @Synchronized
    fun state(): String = when {
        !promptFocusEnabled -> "off"
        !audioFocusEnabled -> "skipped-audioFocusOff"
        request != null -> "held"
        else -> "none"
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        release("closed")
    }

    private fun requestFocus(manager: AudioManager, trigger: String): Boolean {
        val routeAttributes = runCatching { attributes() }.getOrElse {
            report("Audio: navigation prompt attributes unavailable detail=${it.message}")
            return false
        }
        val next = AudioFocusRequestCompat(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, routeAttributes, listener, handler,
        )
        val result = runCatching { next.request(manager) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) request = next else runCatching { next.abandon(manager) }
        val line = "Audio: navigation prompt focus requested trigger=$trigger " +
            "gain=${AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK} " +
            "usage=${routeAttributes.usage} contentType=${routeAttributes.contentType} granted=$result"
        Log.i(TAG, line)
        runCatching { report(line) }
        return granted
    }

    private fun scheduleRelease(reason: String, delayMillis: Long) {
        cancelRelease()
        val task = object : Runnable {
            override fun run() {
                synchronized(this@NavigationPromptFocus) {
                    if (pendingRelease === this) release(reason)
                }
            }
        }
        pendingRelease = task
        handler.postDelayed(task, delayMillis)
    }

    private fun cancelRelease() {
        pendingRelease?.let(handler::removeCallbacks)
        pendingRelease = null
    }

    private fun release(reason: String) {
        cancelRelease()
        val held = request ?: return
        request = null
        val result = manager?.let { runCatching { held.abandon(it) }.getOrNull() }
        val line = "Audio: navigation prompt focus released reason=$reason result=$result"
        Log.i(TAG, line)
        runCatching { report(line) }
    }

    internal companion object {
        const val TAG = "DiPlay-AudioFocus"
        /** Covers the playout delay after unduckAudio and joins back-to-back prompts. */
        const val RELEASE_DELAY_MILLIS = 1_000L
        /** Longest hold without unduckAudio; reported prompts lasted up to about 15 s. */
        const val TIMEOUT_MILLIS = 30_000L
        /** Longest hold for one open navigation stream. */
        const val STREAM_TIMEOUT_MILLIS = 60_000L
    }
}
