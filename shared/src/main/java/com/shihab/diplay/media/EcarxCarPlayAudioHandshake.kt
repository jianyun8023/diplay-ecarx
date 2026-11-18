package com.shihab.diplay.media

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Marks navigation playback for the ECARX volume keys. Music attenuation belongs to the head unit's
 * focus policy: every broadcast uses volume=0, never the iPhone's requested duck depth. The marker
 * follows NavigationPromptFocus's audible/duck lifetime, independently of its optional focus request.
 *
 * The vendor mode is a probed capability, not an assumption: the first prompt verifies that the
 * out-of-stack request really turned `isAlternateAudioOn` on (GRANTED alone proves nothing, since
 * contentType 33 toggles rather than sets the mode). A head unit that fails the probe never signals
 * for the rest of the session — prompts still play and the MCU still ducks through the ordinary
 * prompt focus, only the volume-key grouping is not marked. A crashed earlier session can leave the
 * receiver's duck flag set with no firmware timeout to clear it, so this session's first marker is
 * preceded by one unduck; the duck that immediately follows bounds any misbehaving restore.
 */
internal class EcarxCarPlayAudioHandshake(
    context: Context?,
    private val report: (String) -> Unit = {},
    private val audioManager: AudioManager? = context?.applicationContext
        ?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager,
    private val broadcast: (Intent) -> Unit = { context?.applicationContext?.sendBroadcast(it) },
    private val alternateAudioOn: () -> Boolean? = {
        runCatching {
            audioManager?.javaClass?.getMethod("isAlternateAudioOn")?.invoke(audioManager) as? Boolean
        }.getOrNull()
    },
) {
    private val main = Handler(Looper.getMainLooper())
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        report("CarPlay audio mode focus change=$change")
    }
    private var alternateRequest: AudioFocusRequest? = null
    private var alternateAlreadyOn = false
    /** Latched once a prompt verified the mode applies; an unverified firmware never signals afterwards. */
    private var modeVerified = false
    private var modeUnsupported = false
    @Volatile private var signalled = false
    private var sweptResidue = false
    private var closed = false

    /** Returns a send fact, not a claim that the receiver applied the marker or granted focus. */
    @Synchronized
    fun onPrompt(active: Boolean): Boolean {
        if (closed || modeUnsupported) return false
        if (!active) {
            if (signalled) {
                // The receiver saves the current focus group's volume even for a zero-depth marker.
                // Focus may now be navigation, and wheel keys may have changed its volume. Refresh
                // that snapshot at zero depth before unduck, so it cannot restore an old nav level.
                if (send("duckAudio")) signalled = !send("unduckAudio")
            }
            return signalled
        }
        if (!openAlternate()) return false
        if (!sweptResidue) sweptResidue = send("unduckAudio")
        if (!signalled) signalled = send("duckAudio")
        return signalled
    }

    /** Read without taking the marker lock from the primary-focus callback. */
    fun isSignalling(): Boolean = signalled

    @Synchronized
    fun close() {
        if (closed) return
        onPrompt(false)
        closed = true
        alternateRequest?.let { request ->
            runCatching { audioManager?.abandonAudioFocusRequest(request) }
                .onFailure { report("CarPlay audio mode close failed: ${it.javaClass.simpleName}") }
        }
        alternateRequest = null
        alternateAlreadyOn = false
    }

    @Synchronized
    fun state(): String = "ecarxAudioMode=${when (alternateAudioOn()) {
        true -> if (alternateAlreadyOn) "already-on" else "on"
        false -> "off"
        null -> "unknown"
    }} modeProbe=${when {
        modeUnsupported -> "unsupported"
        modeVerified -> "verified"
        else -> "pending"
    }} promptSignalSent=$signalled closed=$closed"

    private fun openAlternate(): Boolean {
        val before = alternateAudioOn()
        if (before == true) {
            if (alternateRequest == null) alternateAlreadyOn = true
            modeVerified = true
            return true
        }
        if (modeVerified) {
            // Verified earlier this session; a later false or unreadable state is transient. The
            // marker stays as sent and the prompt focus still drives the MCU duck on its own.
            report("CarPlay audio mode unavailable state=$before")
            return false
        }
        // First use is the capability probe. GRANTED is not a mode-state read: contentType 33
        // toggles rather than sets the mode, so the post-request read decides support.
        val requested = before != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            requestAlternateMode()
        val on = requested && alternateAudioOn() == true
        if (on) {
            modeVerified = true
            return true
        }
        if (!modeUnsupported) {
            modeUnsupported = true
            report("CarPlay audio mode unsupported state=$before; prompt signal disabled")
            abandonProbeRequest()
        }
        return false
    }

    private fun requestAlternateMode(): Boolean {
        val manager = audioManager ?: return false
        val attributes = alternateAttributes() ?: return false
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(listener, main)
            .build()
        val result = runCatching { manager.requestAudioFocus(request) }.getOrElse {
            report("CarPlay audio mode request failed: ${it.javaClass.simpleName}")
            return false
        }
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) alternateRequest = request
        report("CarPlay audio mode request result=$result")
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** The probe failed after a grant: return the out-of-stack request rather than leave it behind. */
    private fun abandonProbeRequest() {
        alternateRequest?.let { request ->
            runCatching { audioManager?.abandonAudioFocusRequest(request) }
        }
        alternateRequest = null
    }

    /** Android 9 may rewrite unknown content types; preserve the vendor's out-of-stack type 33. */
    private fun alternateAttributes(): AudioAttributes? {
        val requested = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(CONTENT_TYPE_ALTERNATE)
            .build()
        if (requested.contentType == CONTENT_TYPE_ALTERNATE) return requested
        val base = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        return runCatching {
            AudioAttributes::class.java.getDeclaredField("mContentType")
                .apply { isAccessible = true }
                .setInt(base, CONTENT_TYPE_ALTERNATE)
            base
        }.getOrNull()
    }

    private fun send(command: String): Boolean {
        val intent = Intent(ACTION_CAR_PLAY_DUCK_COMMAND)
            .putExtra(EXTRA_COMMAND, command)
            .putExtra(EXTRA_VOLUME, 0)
        return runCatching { broadcast(intent) }.fold(
            onSuccess = {
                report("CarPlay prompt signal sent command=$command percent=0")
                true
            },
            onFailure = {
                report("CarPlay prompt signal failed command=$command detail=${it.javaClass.simpleName}")
                false
            },
        )
    }

    companion object {
        const val ACTION_CAR_PLAY_DUCK_COMMAND = "com.lge.ivi.cp.action.CAR_PLAY_DUCK_COMMAND"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_VOLUME = "volume"
        private const val CONTENT_TYPE_ALTERNATE = 33
    }
}
