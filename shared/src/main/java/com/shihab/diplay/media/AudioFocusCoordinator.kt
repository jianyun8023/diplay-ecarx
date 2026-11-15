package com.shihab.diplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shihab.diplay.compat.AudioFocusRequestCompat

/** AudioTrack's attributes getter is only available from Android 10. */
internal fun audioTrackAttributesForFocus(track: AudioTrack, configured: AudioAttributes): AudioAttributes =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) track.audioAttributes else configured

/**
 * Executes the Rust core's audio-focus decisions for all eligible tracks in a CarPlay sink. The
 * core (`media::audio::scheduler`) owns the channel priority, the request intent and the target
 * gains; this class keeps the platform objects — the active outputs, the [AudioFocusRequestCompat],
 * the listener with its generation guard — and the applied-volume accounting: only a successful
 * [Entry.setVolume] updates it. Reported grant facts are the only success facts either side holds.
 */
internal class AudioFocusCoordinator(
    context: Context?,
    private val enabled: Boolean,
    private val muteMediaOnTransientLoss: Boolean = true,
    private val report: (String) -> Unit = {},
) {
    private data class Entry(
        val channel: AudioChannel,
        val attributes: AudioAttributes,
        val setVolume: (Float) -> Boolean,
        var appliedVolume: Float = FULL_VOLUME,
    )

    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    // Keyed by the renderer's output (an AudioTrack or a native output).
    private val active = LinkedHashMap<Any, Entry>()
    /** The core's value token: per-channel output counts, reported grant facts, media gain level. */
    private val core = LongArray(6)
    private var request: AudioFocusRequestCompat? = null
    private var closed = false
    private var focusGeneration = 0L
    private var currentListener = listenerFor(focusGeneration)
    internal val listener: AudioManager.OnAudioFocusChangeListener get() = currentListener

    private fun listenerFor(generation: Long) = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(this) {
            // Android may have queued callbacks before a request was abandoned or replaced.
            if (generation != focusGeneration || request == null || active.isEmpty()) {
                // Dropping this silently hides the one window that matters: between a telephony
                // track closing and media reopening, a head unit that keeps focus looks like
                // nothing at all. Say the callback arrived and why it was not acted on.
                runCatching {
                    report("Audio: focus change=$change dropped" +
                        " stale=${generation != focusGeneration} noRequest=${request == null}" +
                        " activeTracks=${active.size}")
                }
                return@synchronized
            }
            runCatching { report("Audio: focus change=$change activeTracks=${active.size}") }
            val event = when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> EVENT_DUCKED_LOSS
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> EVENT_TRANSIENT_LOSS
                AudioManager.AUDIOFOCUS_GAIN -> EVENT_GAIN
                // Preserve the existing permanent-loss workaround for ROMs that never send gain again.
                else -> return@synchronized
            }
            if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && muteMediaOnTransientLoss) {
                // This reports a temporary Android focus owner, not a confirmed call.
                Log.i(TAG, "Audio: muting media during transient focus loss")
            }
            execute(step(event, CHANNEL_NONE))
        }
    }

    fun acquire(track: AudioTrack, channel: AudioChannel, attributes: AudioAttributes) =
        acquire(track, channel, attributes) { volume -> track.setStereoVolume(volume, volume) == AudioTrack.SUCCESS }

    /** [owner] plays on [channel]; [setVolume] applies the ducking gain to it. */
    @Synchronized
    fun acquire(owner: Any, channel: AudioChannel, attributes: AudioAttributes, setVolume: (Float) -> Boolean) {
        if (closed || !enabled || manager == null || channel == AudioChannel.NAVIGATION) return
        val entry = Entry(channel, attributes, setVolume)
        active[owner] = entry
        val decision = step(EVENT_ACQUIRE, channel.coreCode())
        // A fresh request retargets every output once its result is reported; a covered output
        // only joins the held request at the current target.
        val applied = execute(decision)
        if (applied != null) applyGain(entry, applied.first, applied.second)
        else if (decision[SCOPE] == SCOPE_NEW_OUTPUT) applyGain(entry, decision[MEDIA_GAIN], decision[OTHER_GAIN])
    }

    /** CarPlay music holds granted focus now, so the car's audio controller already plays Android. */
    @Synchronized
    fun mediaHoldsFocus(): Boolean = NativeAudioScheduler.mediaFocusHeld(core)

    @Synchronized
    fun release(owner: Any) {
        val entry = active.remove(owner) ?: return
        execute(step(EVENT_RELEASE, entry.channel.coreCode()))
    }

    fun onExternalFocusChange(change: Int) {
        val current = synchronized(this) { currentListener }
        current.onAudioFocusChange(change)
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        active.clear()
        execute(step(EVENT_CLOSE, CHANNEL_NONE))
    }

    private fun step(event: Int, channel: Int): IntArray =
        NativeAudioScheduler.focusStep(core, event, channel, enabled, muteMediaOnTransientLoss)

    /**
     * Runs one core decision: abandon the held request when told, ask Android for the channel the
     * core chose and report the platform's result back as the only success fact. Returns the gain
     * codes a fresh request applied, so the caller can re-reach the output that triggered it.
     */
    private fun execute(decision: IntArray): Pair<Int, Int>? {
        if (decision[ABANDON] == 1 || decision[NEW_REQUEST] == 1) focusGeneration += 1
        if (decision[ABANDON] == 1) {
            val abandoned = request
            request = null
            manager?.let { abandoned?.abandon(it) }
            if (abandoned != null && decision[NEW_REQUEST] == 0) {
                runCatching { report("Audio: focus released activeTracks=0") }
            }
        }
        if (decision[NEW_REQUEST] == 1) return requestFocusFor(decision[CHANNEL], decision[GAIN_INTENT])
        if (decision[SCOPE] == SCOPE_ALL_OUTPUTS) applyAll(decision[MEDIA_GAIN], decision[OTHER_GAIN])
        return null
    }

    /** Asks Android for the channel the core chose, then reports the actual result. */
    private fun requestFocusFor(channel: Int, intent: Int): Pair<Int, Int>? {
        val primary = active.values.firstOrNull { it.channel.coreCode() == channel } ?: return null
        val gain = when (intent) {
            GAIN_INTENT_TRANSIENT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            GAIN_INTENT_TRANSIENT_MAY_DUCK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            else -> AudioManager.AUDIOFOCUS_GAIN
        }
        currentListener = listenerFor(focusGeneration)
        val next = AudioFocusRequestCompat(gain, primary.attributes, currentListener, Handler(Looper.getMainLooper()))
        request = next
        val result = manager?.let(next::request)
        val reported = step(
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) EVENT_REQUEST_GRANTED else EVENT_REQUEST_FAILED,
            channel,
        )
        // The request result retargets every output, the new one included.
        applyAll(reported[MEDIA_GAIN], reported[OTHER_GAIN])
        val line = "Audio: focus requested channel=${primary.channel} gain=$gain granted=$result " +
            "usage=${primary.attributes.usage} contentType=${primary.attributes.contentType} " +
            "api=${if (Build.VERSION.SDK_INT >= 26) "AudioFocusRequest" else "legacy"} activeTracks=${active.size}"
        Log.i(TAG, line)
        runCatching { report(line) }
        return reported[MEDIA_GAIN] to reported[OTHER_GAIN]
    }

    private fun applyAll(mediaGain: Int, otherGain: Int) {
        active.values.forEach { applyGain(it, mediaGain, otherGain) }
    }

    private fun applyGain(entry: Entry, mediaGain: Int, otherGain: Int) {
        // A rejected request silences its tracks; ordinary ducking affects only music.
        // This changes only the renderer's relative gain, never Android's user stream volume.
        val volume = if (entry.channel == AudioChannel.MEDIA) focusVolume(mediaGain) else focusVolume(otherGain)
        if (entry.appliedVolume == volume) return
        val applied = runCatching { entry.setVolume(volume) }.getOrDefault(false)
        if (applied) entry.appliedVolume = volume
    }

    /** The core's gain codes; the platform floats stay on this side. */
    private fun focusVolume(gain: Int) = when (gain) {
        GAIN_DUCKED -> DUCKED_VOLUME
        GAIN_MUTED -> 0f
        else -> FULL_VOLUME
    }

    /** The core's channel codes: 1 media, 2 phone, 3 assistant; navigation never enters. */
    private fun AudioChannel.coreCode(): Int = when (this) {
        AudioChannel.MEDIA -> 1
        AudioChannel.PHONE -> 2
        AudioChannel.ASSISTANT -> 3
        AudioChannel.NAVIGATION -> 0
    }

    private companion object {
        const val TAG = "DiPlay-AudioFocus"
        const val FULL_VOLUME = 1f
        const val DUCKED_VOLUME = 0.2f

        // Core events (NativeAudioScheduler.focusStep): 1 acquire, 2 release, 3 ducking loss,
        // 4 transient loss, 5 gain, 6/7 the requestAudioFocus result, 8 close.
        const val EVENT_ACQUIRE = 1
        const val EVENT_RELEASE = 2
        const val EVENT_DUCKED_LOSS = 3
        const val EVENT_TRANSIENT_LOSS = 4
        const val EVENT_GAIN = 5
        const val EVENT_REQUEST_GRANTED = 6
        const val EVENT_REQUEST_FAILED = 7
        const val EVENT_CLOSE = 8

        // Decision slots in the returned IntArray.
        const val ABANDON = 0
        const val NEW_REQUEST = 1
        const val CHANNEL = 2
        const val GAIN_INTENT = 3
        const val MEDIA_GAIN = 4
        const val OTHER_GAIN = 5
        const val SCOPE = 6
        const val SCOPE_NEW_OUTPUT = 1
        const val SCOPE_ALL_OUTPUTS = 2

        // Gain intents; the AudioManager constants are mapped in [requestFocusFor].
        const val GAIN_INTENT_TRANSIENT = 2
        const val GAIN_INTENT_TRANSIENT_MAY_DUCK = 3

        // Gain codes returned by the core.
        const val GAIN_DUCKED = 1
        const val GAIN_MUTED = 2

        const val CHANNEL_NONE = 0
    }
}
