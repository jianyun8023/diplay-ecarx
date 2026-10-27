package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat

/** The attributes the navigation renderer plays with, for a focus request made before its track exists. */
internal fun navigationRouteAttributes(navigationChannel: Int): AudioAttributes =
    LegacyAudioFallback.attributes(navigationChannel) ?: AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

/** Opt-in, temporary duckable focus around iPhone navigation prompts; no renderer gain changes. */
internal class NavigationPromptFocus(
    context: Context?,
    private val promptFocusEnabled: Boolean,
    private val audioFocusEnabled: Boolean,
    private val attributes: () -> AudioAttributes,
    private val report: (String) -> Unit = {},
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

    @Synchronized
    fun onDuck() {
        if (closed || !promptFocusEnabled) return
        if (!audioFocusEnabled) {
            if (!skipReported) {
                skipReported = true
                runCatching { report("Audio: navigation prompt focus skipped audioFocus=off") }
            }
            return
        }
        val manager = manager ?: return
        if (request == null) {
            val routeAttributes = attributes()
            val next = AudioFocusRequestCompat(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, routeAttributes, listener, handler,
            )
            val result = runCatching { next.request(manager) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (granted) request = next else runCatching { next.abandon(manager) }
            val line = "Audio: navigation prompt focus requested gain=${AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK} " +
                "usage=${routeAttributes.usage} contentType=${routeAttributes.contentType} granted=$result"
            Log.i(TAG, line)
            runCatching { report(line) }
            if (!granted) return
        }
        // Each duck restarts the timeout, and a duck right after an unduck keeps the same request.
        scheduleRelease("timeout", TIMEOUT_MILLIS)
    }

    /** The prompt's last audio is still in DiPlay's buffers when the iPhone sends unduckAudio. */
    @Synchronized
    fun onUnduck() {
        if (closed || request == null) return
        scheduleRelease("unduck", RELEASE_DELAY_MILLIS)
    }

    /** Session teardown has no buffered prompt to retain; the sink may serve another session. */
    @Synchronized
    fun onSessionClosed() {
        release("session closed")
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        release("closed")
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
    }
}
