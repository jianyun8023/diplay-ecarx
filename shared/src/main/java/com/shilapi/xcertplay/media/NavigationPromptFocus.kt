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
 * A prompt is the iPhone's duckAudio … unduckAudio, or navigation audio that is audible. The iPhone
 * sends no duckAudio when the navigation app is set to mix with music (AMap "coexist"); the sound
 * alone then holds the focus, or the car mutes the prompt (v129 car test, 2026-11-07: 56 prompts
 * without focus, none of them heard). The car ducks the music for as long as the request is held,
 * and the iPhone keeps a navigation stream open for up to 32 s after a 2 s prompt, so the request
 * follows the sound, not the open stream.
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
    private val audibleReleaseDelayMillis: Long = AUDIBLE_RELEASE_DELAY_MILLIS,
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
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
    private var audibleStreams = 0
    private var heldSince = 0L

    @Synchronized
    fun onDuck() {
        if (closed || !promptFocusEnabled) return
        iphoneDucking = true
        if (!acquire("duck")) return
        // Each duck restarts the timeout, and a duck right after an unduck keeps the same request.
        scheduleRelease("timeout", if (audibleStreams > 0) streamTimeoutMillis else timeoutMillis)
    }

    /** The prompt's last audio is still in DiPlay's buffers when the iPhone sends unduckAudio. */
    @Synchronized
    fun onUnduck() {
        iphoneDucking = false
        if (closed || request == null) return
        // Navigation sound still playing keeps the prompt going; its silence releases the focus.
        if (audibleStreams == 0) scheduleRelease("unduck", releaseDelayMillis)
    }

    /**
     * A navigation track started (true) or stopped (false) playing sound: its first audible PCM
     * after silence, and its silence, close or failure after that. Each renderer reports a pair.
     */
    @Synchronized
    fun onNavigationAudio(audible: Boolean) {
        audibleStreams = (audibleStreams + if (audible) 1 else -1).coerceAtLeast(0)
        if (closed || !promptFocusEnabled) return
        if (audible) {
            if (!acquire("audio")) return
            // Only bounds a renderer that never reports its silence.
            scheduleRelease("timeout", streamTimeoutMillis)
        } else if (audibleStreams == 0 && !iphoneDucking && request != null) {
            // The renderer reports silence after it has waited out its own playout delay.
            scheduleRelease("silence", audibleReleaseDelayMillis)
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
        if (granted) {
            request = next
            heldSince = clock()
        } else runCatching { next.abandon(manager) }
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
        // How long the car ducked the music for this prompt.
        val line = "Audio: navigation prompt focus released reason=$reason result=$result " +
            "heldMs=${(clock() - heldSince).coerceAtLeast(0)}"
        Log.i(TAG, line)
        runCatching { report(line) }
    }

    internal companion object {
        const val TAG = "DiPlay-AudioFocus"
        /** Covers the playout delay after unduckAudio and joins back-to-back prompts. */
        const val RELEASE_DELAY_MILLIS = 1_000L
        /** Longest hold without unduckAudio; reported prompts lasted up to about 15 s. */
        const val TIMEOUT_MILLIS = 30_000L
        /** Longest hold for navigation sound that never reports its end. */
        const val STREAM_TIMEOUT_MILLIS = 60_000L
        /** Added to the renderer's own silence wait; together about the old 1 s tail. */
        const val AUDIBLE_RELEASE_DELAY_MILLIS = 400L
    }
}
