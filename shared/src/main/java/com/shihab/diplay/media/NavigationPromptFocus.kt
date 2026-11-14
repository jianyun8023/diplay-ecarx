package com.shihab.diplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shihab.diplay.compat.AudioFocusRequestCompat

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
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
    /**
     * True when a new request is not needed: the prompt plays through focus the music already holds.
     * Without any focus the ECARX head unit leaves its amplifier on no source after a cold boot, and
     * a first prompt before any music was silent, so this never skips while nothing holds focus.
     */
    private val skipRequest: () -> Boolean = { false },
) {
    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        runCatching { report("Audio: navigation prompt focus change=$change") }
    }
    private var request: AudioFocusRequestCompat? = null
    private var pendingRelease: Runnable? = null
    private var skipReported = false
    private val promptState = LongArray(3)
    private var heldSince = 0L
    private var skipping = false

    @Synchronized
    fun onDuck() {
        if (NativeAudioScheduler.promptFocus(promptState, DUCK, promptFocusEnabled, request != null) != ACQUIRE) return
        if (!acquire("duck")) return
        // Each duck restarts the timeout, and a duck right after an unduck keeps the same request.
        val timeout = NativeAudioScheduler.promptFocus(promptState, DUCK_TIMEOUT, promptFocusEnabled, request != null)
        scheduleRelease("timeout", timeout.toLong())
    }

    /** The prompt's last audio is still in DiPlay's buffers when the iPhone sends unduckAudio. */
    @Synchronized
    fun onUnduck() {
        // Navigation sound still playing keeps the prompt going; its silence releases the focus.
        val delay = NativeAudioScheduler.promptFocus(promptState, UNDUCK, promptFocusEnabled, request != null)
        if (delay > 0) scheduleRelease("unduck", delay.toLong())
    }

    /**
     * A navigation track started (true) or stopped (false) playing sound: its first audible PCM
     * after silence, and its silence, close or failure after that. Each renderer reports a pair.
     */
    @Synchronized
    fun onNavigationAudio(audible: Boolean) {
        val action = NativeAudioScheduler.promptFocus(promptState, if (audible) AUDIBLE else SILENT, promptFocusEnabled, request != null)
        if (action == ACQUIRE) {
            if (!acquire("audio")) return
            // Only bounds a renderer that never reports its silence.
            val timeout = NativeAudioScheduler.promptFocus(promptState, AUDIO_TIMEOUT, promptFocusEnabled, request != null)
            scheduleRelease("timeout", timeout.toLong())
        } else if (action > 0) {
            // The renderer reports silence after it has waited out its own playout delay.
            scheduleRelease("silence", action.toLong())
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
        if (request != null) return true
        if (runCatching(skipRequest).getOrDefault(false)) {
            if (!skipping) {
                skipping = true
                runCatching { report("Audio: navigation prompt focus skipped trigger=$trigger music holds focus") }
            }
            return false
        }
        skipping = false
        return requestFocus(manager, trigger)
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
        skipping -> "skipped-musicHoldsFocus"
        else -> "none"
    }

    @Synchronized
    fun close() {
        if (NativeAudioScheduler.promptFocus(promptState, CLOSE, promptFocusEnabled, request != null) == 0) return
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
        private const val DUCK = 0
        private const val UNDUCK = 1
        private const val AUDIBLE = 2
        private const val SILENT = 3
        private const val CLOSE = 4
        private const val DUCK_TIMEOUT = 5
        private const val AUDIO_TIMEOUT = 6
        private const val ACQUIRE = 1
        const val TAG = "DiPlay-AudioFocus"
    }
}
