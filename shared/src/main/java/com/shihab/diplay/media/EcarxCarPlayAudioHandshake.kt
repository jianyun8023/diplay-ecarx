package com.shihab.diplay.media

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Replays the head unit's own CarPlay audio handshake.
 *
 * ECARX's `CarAudioImplDCY11` keeps two extra states beside Android's audio focus:
 * `mCPDuckingState`, set only by [ACTION_CAR_PLAY_DUCK_COMMAND], and the alternate-audio mode opened
 * by an out-of-stack audio-focus request whose content type is 33. A volume key then selects the
 * navigation usage (12) instead of falling back to the last active playback (music). DiPlay does not
 * need to write the navigation volume group: the car changes its own group.
 *
 * The alternate request never enters the focus stack, so it does not own focus or change
 * `getCurrentAudioPeekInfo()`. The vendor mode still changes the head unit's `AudioControlMode`; this
 * is why the handshake is enabled only by the experimental wheel-volume setting and is closed with
 * the sink.
 */
internal class EcarxCarPlayAudioHandshake(
    context: Context?,
    private val enabled: () -> Boolean,
    private val report: (String) -> Unit = {},
    private val audioManager: AudioManager? = context?.applicationContext
        ?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager,
    private val broadcast: (Intent) -> Unit = { context?.applicationContext?.sendBroadcast(it) },
) {
    private val main = Handler(Looper.getMainLooper())
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        report("CarPlay audio mode focus change=$change")
    }
    private var alternateRequest: AudioFocusRequest? = null
    private var alternateAlreadyOn = false
    private var ducked = false

    /** Opens the vendor alternate-audio mode when the setting is on and this is an ECARX unit. */
    fun start() {
        if (!enabled()) return
        openAlternate()
    }

    /** Called for each iPhone duckAudio (true) / unduckAudio (false), with its dB volume when sent. */
    fun onDuck(ducked: Boolean, volumeDb: Float?) {
        if (!enabled()) {
            close()
            return
        }
        if (ducked) {
            openAlternate()
            if (!canSignal()) {
                report("CarPlay duck signal skipped: vendor audio mode is not open")
                return
            }
            this.ducked = true
            sendDuck("duckAudio", duckPercent(volumeDb), volumeDb)
        } else if (this.ducked) {
            this.ducked = false
            sendDuck("unduckAudio", 0, volumeDb)
        }
    }

    /** Clears a prompt the phone left open and releases the alternate mode this sink opened. */
    fun close() {
        if (ducked) {
            ducked = false
            sendDuck("unduckAudio", 0, null)
        }
        alternateRequest?.let { request ->
            runCatching { audioManager?.abandonAudioFocusRequest(request) }
                .onFailure { report("CarPlay audio mode close failed: ${it.javaClass.simpleName}") }
        }
        alternateRequest = null
        alternateAlreadyOn = false
    }

    fun state(): String = "ecarxAudioMode=${when {
        alternateRequest != null -> "opened"
        alternateAlreadyOn -> "already-on"
        else -> "off"
    }} ducked=$ducked enabled=${enabled()}"

    private fun openAlternate() {
        if (!enabled() || alternateRequest != null || alternateAlreadyOn) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            report("CarPlay audio mode unavailable api=${Build.VERSION.SDK_INT}")
            return
        }
        val manager = audioManager ?: return
        val attributes = alternateAttributes()
        if (attributes == null) {
            report("CarPlay audio mode attributes unavailable")
            return
        }
        val request = runCatching {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(listener, main)
                .build()
        }.getOrElse {
            report("CarPlay audio mode request unavailable: ${it.javaClass.simpleName}")
            return
        }
        val result = runCatching { manager.requestAudioFocus(request) }.getOrElse {
            report("CarPlay audio mode request failed: ${it.javaClass.simpleName}")
            return
        }
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            alternateRequest = request
            report("CarPlay audio mode opened result=$result")
        } else if (alternateAudioOn(manager)) {
            // A previous process opened the mode and did not close it. Reuse it, but never close it.
            alternateAlreadyOn = true
            report("CarPlay audio mode already on result=$result")
        } else {
            report("CarPlay audio mode rejected result=$result")
        }
    }

    private fun canSignal(): Boolean {
        if (alternateRequest != null || alternateAlreadyOn) return true
        val manager = audioManager ?: return false
        return alternateAudioOn(manager)
    }

    /**
     * Stock Android 9 rewrites an unknown content type to 0, so build a normal media request and
     * move the field afterwards; a vendor build that accepts 33 directly is used as-is.
     */
    private fun alternateAttributes(): AudioAttributes? {
        val requested = runCatching {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(CONTENT_TYPE_ALTERNATE)
                .build()
        }.getOrNull()
        if (requested?.contentType == CONTENT_TYPE_ALTERNATE) return requested
        val base = runCatching {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        }.getOrNull() ?: return null
        return runCatching {
            AudioAttributes::class.java.getDeclaredField("mContentType")
                .apply { isAccessible = true }
                .setInt(base, CONTENT_TYPE_ALTERNATE)
            base
        }.getOrNull()
    }

    /** The vendor framework's hidden read-only check; absent on ordinary Android builds. */
    private fun alternateAudioOn(manager: AudioManager): Boolean = runCatching {
        manager.javaClass.getMethod("isAlternateAudioOn").invoke(manager) as? Boolean
    }.getOrNull() == true

    private fun sendDuck(command: String, percent: Int, volumeDb: Float?) {
        val intent = Intent(ACTION_CAR_PLAY_DUCK_COMMAND)
            .putExtra(EXTRA_COMMAND, command)
            .putExtra(EXTRA_VOLUME, percent)
        runCatching { broadcast(intent) }
            .onFailure { report("CarPlay duck broadcast failed: ${it.javaClass.simpleName}") }
        report("CarPlay duck=$command percent=$percent db=${volumeDb ?: "none"} alternate=${canSignal()}")
    }

    companion object {
        const val ACTION_CAR_PLAY_DUCK_COMMAND = "com.lge.ivi.cp.action.CAR_PLAY_DUCK_COMMAND"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_VOLUME = "volume"

        /** The vendor content type that opens `AudioControlMode=01123101` without joining the focus stack. */
        private const val CONTENT_TYPE_ALTERNATE = 33

        /**
         * The vendor receiver lowers the current group by `(100 - volume)%`: a -13.98 dB prompt
         * becomes 80, while an omitted volume leaves the level alone and only marks the prompt.
         */
        internal fun duckPercent(volumeDb: Float?): Int {
            if (volumeDb == null || volumeDb.isNaN() || volumeDb.isInfinite()) return 0
            val gain = 10.0.pow(volumeDb / 20.0)
            return ((1.0 - gain) * 100.0).roundToInt().coerceIn(0, 100)
        }
    }
}
