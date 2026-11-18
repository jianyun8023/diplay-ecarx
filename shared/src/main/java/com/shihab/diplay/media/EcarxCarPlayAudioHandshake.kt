package com.shihab.diplay.media

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Marks eligible navigation prompts for ECARX volume-key grouping. Prompt focus drives the MCU duck. */
internal class EcarxCarPlayAudioHandshake(
    context: Context,
    private val report: (String) -> Unit = {},
    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java),
    private val broadcast: (Intent, (() -> Unit)?) -> Unit = { intent, delivered ->
        val receiver = delivered?.let {
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = it()
            }
        }
        context.sendOrderedBroadcast(intent, null, receiver, null, 0, null, null)
    },
    private val alternateAudioOn: () -> Boolean? = {
        runCatching {
            audioManager?.javaClass?.getMethod("isAlternateAudioOn")?.invoke(audioManager) as? Boolean
        }.getOrNull()
    },
    private val connectVolume: (Handler) -> EcarxCarAudioGroups = { EcarxCarAudioVolume(context, it, report) },
) {
    private enum class Probe { PENDING, VERIFIED, UNSUPPORTED }

    private val main = Handler(Looper.getMainLooper())
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        report("CarPlay audio mode focus change=$change")
    }
    private var alternateRequest: AudioFocusRequest? = null
    private var modeProbe = Probe.PENDING
    private var volume: EcarxCarAudioGroups? = null
    private var markedNavigationVolume: Int? = null
    private var awaitingUnduck = false
    private var closed = false

    /** Resolve the capability before choosing any renderer route or focus policy for this sink. */
    @Synchronized
    fun probe(): Boolean {
        if (closed) return false
        if (modeProbe == Probe.PENDING) {
            val whileOwned = openAlternate()
            val released = releaseAlternate()
            modeProbe = if (whileOwned && released) Probe.VERIFIED else Probe.UNSUPPORTED
            if (modeProbe == Probe.VERIFIED) {
                volume = connectVolume(main)
            } else {
                report("CarPlay audio modeProbe=unsupported; using DiPlay routing and focus")
            }
        }
        return modeProbe == Probe.VERIFIED
    }

    /** True while a marker was sent for the current prompt. */
    @Synchronized
    fun onPrompt(active: Boolean): Boolean {
        if (closed || modeProbe != Probe.VERIFIED) return false
        if (!active) {
            endPrompt()
            return false
        }
        if (markedNavigationVolume != null) return true
        if (awaitingUnduck || alternateRequest != null) return false
        val navigationVolume = volume?.markableNavigationVolume()
        if (navigationVolume == null) {
            report("CarPlay prompt signal skipped: volume group at zero or unavailable")
            return false
        }
        if (!openAlternate() || !send("duckAudio")) {
            releaseAlternate()
            return false
        }
        markedNavigationVolume = navigationVolume
        return true
    }

    @Synchronized
    fun close() {
        if (closed) return
        endPrompt()
        closed = true
        if (!awaitingUnduck) volume?.close()
    }

    @Synchronized
    fun state(): String = "ecarxAudioMode=${alternateAudioOn()} modeProbe=${modeProbe.name.lowercase()}" +
        " promptSignalSent=${markedNavigationVolume != null} unduckPending=$awaitingUnduck closed=$closed"

    private fun endPrompt() {
        // PAS can discard unduck entirely. Release our alternate request even in that case, since
        // the firmware selects the navigation group only while its marker AND alternate are true.
        releaseAlternate()
        val initial = markedNavigationVolume ?: return
        markedNavigationVolume = null
        val groups = checkNotNull(volume)
        val before = groups.navigationVolume()
        awaitingUnduck = true
        if (!send("unduckAudio") {
                synchronized(this) {
                    // Parking assist also writes media volume. Only reconcile the navigation group.
                    if (before != null && before != initial && groups.navigationVolume() == initial) {
                        groups.restoreNavigationVolume(before)
                    }
                    awaitingUnduck = false
                    if (closed) groups.close()
                }
            }) {
            awaitingUnduck = false
        }
    }

    private fun openAlternate(): Boolean {
        // A mode already on may belong to the OEM or another client. Never adopt a mode we cannot
        // release at the end of this prompt.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || alternateAudioOn() != false) return false
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
        report("CarPlay audio mode request result=$result")
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        alternateRequest = request
        return alternateAudioOn() == true
    }

    private fun releaseAlternate(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val held = alternateRequest ?: return true
        val result = runCatching { audioManager?.abandonAudioFocusRequest(held) }.getOrNull()
        val on = alternateAudioOn()
        if (on == false) {
            alternateRequest = null
            return true
        }
        report("CarPlay audio mode release unconfirmed result=$result state=$on")
        return false
    }

    /** ECARX uses content type 33. Only its Android 9 firmware needs the non-SDK field fallback. */
    @SuppressLint("WrongConstant", "SoonBlockedPrivateApi")
    private fun alternateAttributes(): AudioAttributes? {
        val builder = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        val requested = runCatching { builder.setContentType(CONTENT_TYPE_ALTERNATE).build() }.getOrNull()
        if (requested?.contentType == CONTENT_TYPE_ALTERNATE) return requested
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.P) return null
        return runCatching {
            val attributes = requested ?: builder.build()
            AudioAttributes::class.java.getDeclaredField("mContentType")
                .apply { isAccessible = true }
                .setInt(attributes, CONTENT_TYPE_ALTERNATE)
            attributes
        }.getOrNull()
    }

    private fun send(command: String, delivered: (() -> Unit)? = null): Boolean {
        val intent = Intent(ACTION_CAR_PLAY_DUCK_COMMAND)
            .putExtra(EXTRA_COMMAND, command)
            .putExtra(EXTRA_VOLUME, 0)
        return runCatching { broadcast(intent, delivered) }.fold(
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
