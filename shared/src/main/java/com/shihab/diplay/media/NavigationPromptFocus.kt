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
    /** Opens/closes the vendor prompt marker; true means the platform accepted the signal send. */
    private val promptSignal: ((Boolean) -> Boolean)? = null,
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
    private val diagnostics = LongArray(10)
    private var heldSince = 0L
    private var skipping = false
    private var promptSignalled = false

    @Synchronized
    fun onDuck() {
        if (NativeAudioScheduler.promptFocus(
                promptState, DUCK, promptFocusEnabled || promptSignal != null, request != null || promptSignalled,
            ) != ACQUIRE) return
        if (!acquire("duck")) return
        // Each duck restarts the timeout, and a duck right after an unduck keeps the same request.
        val timeout = NativeAudioScheduler.promptFocus(promptState, DUCK_TIMEOUT, promptFocusEnabled, request != null)
        scheduleRelease("timeout", timeout.toLong())
    }

    /** The prompt's last audio is still in DiPlay's buffers when the iPhone sends unduckAudio. */
    @Synchronized
    fun onUnduck() {
        // Navigation sound still playing keeps the prompt going; its silence releases the focus.
        val delay = NativeAudioScheduler.promptFocus(
            promptState, UNDUCK, promptFocusEnabled || promptSignal != null, request != null || promptSignalled,
        )
        if (delay > 0) scheduleRelease("unduck", delay.toLong())
    }

    /**
     * A navigation track started (true) or stopped (false) playing sound: its first audible PCM
     * after silence, and its silence, close or failure after that. Each renderer reports a pair.
     */
    @Synchronized
    fun onNavigationAudio(audible: Boolean) {
        val action = NativeAudioScheduler.promptFocus(
            promptState, if (audible) AUDIBLE else SILENT,
            promptFocusEnabled || promptSignal != null, request != null || promptSignalled,
        )
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

    /** Acquires the configured platform resources; a marker can stay active with focus switched off. */
    private fun acquire(trigger: String): Boolean {
        if (!promptSignalled) promptSignalled = promptSignal?.invoke(true) == true
        if (!promptFocusEnabled) return promptSignalled
        if (!audioFocusEnabled) {
            if (!skipReported) {
                skipReported = true
                runCatching { report("Audio: navigation prompt focus skipped audioFocus=off") }
            }
            return promptSignalled
        }
        val manager = manager ?: return promptSignalled
        if (request != null) return true
        if (runCatching(skipRequest).getOrDefault(false)) {
            if (!skipping) {
                skipping = true
                runCatching { report("Audio: navigation prompt focus skipped trigger=$trigger music holds focus") }
            }
            return promptSignalled
        }
        skipping = false
        return requestFocus(manager, trigger) || promptSignalled
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

    /**
     * True while a prompt plays through its own focus or the vendor marker. The head unit then owns
     * the duck — its MCU ducks the music while the prompt focus is granted (car test 2026-11-07),
     * and the vendor marker only adds the volume-key grouping — so a CAN_DUCK loss to the music in
     * that window must not also lower DiPlay's own PCM. Send failure does not shorten the window.
     */
    @Synchronized
    fun platformOwnsPromptDuck(): Boolean = request != null || promptSignalled

    @Synchronized fun diagnosticReport(): String = "Focus navigation " +
        NativeAudioScheduler.focusDiagnostics(diagnostics, 0, 0) +
        " trackedRequest=${request != null} state=${state()} platformOwner=unknown promptSignalled=$promptSignalled"

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
        val attempt = runCatching { next.request(manager) }
        NativeAudioScheduler.focusDiagnostics(diagnostics, 1, attempt.getOrDefault(Int.MIN_VALUE))
        val result = attempt.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            request = next
            heldSince = clock()
        } else {
            val abandoned = runCatching { next.abandon(manager) }
            NativeAudioScheduler.focusDiagnostics(diagnostics, 2, abandoned.getOrDefault(Int.MIN_VALUE))
        }
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
        if (promptSignalled) {
            promptSignal?.invoke(false)
            promptSignalled = false
        }
        val held = request ?: return
        request = null
        val result = manager?.let { runCatching { held.abandon(it) }.getOrNull() }
        if (manager != null) NativeAudioScheduler.focusDiagnostics(diagnostics, 2, result ?: Int.MIN_VALUE)
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
