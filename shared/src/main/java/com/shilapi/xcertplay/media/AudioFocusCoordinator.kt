package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat

/** AudioTrack's attributes getter is only available from Android 10. */
internal fun audioTrackAttributesForFocus(track: AudioTrack, configured: AudioAttributes): AudioAttributes =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) track.audioAttributes else configured

/** Owns one focus request for all eligible tracks in a CarPlay sink. */
internal class AudioFocusCoordinator(
    context: Context?,
    private val enabled: Boolean,
    private val muteMediaOnTransientLoss: Boolean = true,
    private val report: (String) -> Unit = {},
) {
    private data class Entry(val channel: AudioChannel, val attributes: AudioAttributes, var appliedVolume: Float = FULL_VOLUME)

    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val active = LinkedHashMap<AudioTrack, Entry>()
    private var request: AudioFocusRequestCompat? = null
    private var requestedChannel: AudioChannel? = null
    private var mediaVolume = FULL_VOLUME
    private var focusGranted = true
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
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setMediaVolume(DUCKED_VOLUME)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (muteMediaOnTransientLoss) {
                        // This reports a temporary Android focus owner, not a confirmed call.
                        Log.i(TAG, "Audio: muting media during transient focus loss")
                        setMediaVolume(0f)
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    focusGranted = true
                    setMediaVolume(FULL_VOLUME)
                }
                // Preserve the existing permanent-loss workaround for ROMs that never send gain again.
            }
        }
    }

    @Synchronized
    fun acquire(track: AudioTrack, channel: AudioChannel, attributes: AudioAttributes) {
        if (closed || !enabled || manager == null || channel == AudioChannel.NAVIGATION) return
        active[track] = Entry(channel, attributes)
        refreshRequest()
        applyMediaVolume(track, active.getValue(track))
    }

    /** CarPlay music holds granted focus now, so the car's audio controller already plays Android. */
    @Synchronized
    fun mediaHoldsFocus(): Boolean = request != null && requestedChannel == AudioChannel.MEDIA && focusGranted

    @Synchronized
    fun release(track: AudioTrack) {
        if (active.remove(track) != null) refreshRequest()
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
        refreshRequest()
    }

    private fun refreshRequest() {
        val primary = active.values.maxByOrNull { it.channel.focusPriority() }
        if (primary == null) {
            focusGeneration += 1
            val abandoned = request
            request = null
            requestedChannel = null
            mediaVolume = FULL_VOLUME
            focusGranted = true
            manager?.let { abandoned?.abandon(it) }
            if (abandoned != null) runCatching { report("Audio: focus released activeTracks=0") }
            return
        }
        if (request != null && requestedChannel == primary.channel && focusGranted) return
        focusGeneration += 1
        manager?.let { request?.abandon(it) }
        val gain = when (primary.channel) {
            AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            AudioChannel.PHONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.ASSISTANT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioChannel.NAVIGATION -> return
        }
        currentListener = listenerFor(focusGeneration)
        val next = AudioFocusRequestCompat(gain, primary.attributes, currentListener, Handler(Looper.getMainLooper()))
        request = next
        requestedChannel = primary.channel
        val result = manager?.let(next::request)
        focusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        setMediaVolume(if (focusGranted) FULL_VOLUME else 0f)
        val line = "Audio: focus requested channel=${primary.channel} gain=$gain granted=$result " +
            "usage=${primary.attributes.usage} contentType=${primary.attributes.contentType} " +
            "api=${if (Build.VERSION.SDK_INT >= 26) "AudioFocusRequest" else "legacy"} activeTracks=${active.size}"
        Log.i(TAG, line)
        runCatching { report(line) }
    }

    private fun setMediaVolume(volume: Float) {
        mediaVolume = volume
        active.forEach { (track, entry) -> applyMediaVolume(track, entry) }
    }

    private fun applyMediaVolume(track: AudioTrack, entry: Entry) {
        // A rejected request silences its tracks; ordinary ducking affects only music.
        // This changes only the renderer's relative gain, never Android's user stream volume.
        val volume = if (!focusGranted) 0f else if (entry.channel == AudioChannel.MEDIA) mediaVolume else FULL_VOLUME
        if (entry.appliedVolume == volume) return
        val applied = runCatching { track.setStereoVolume(volume, volume) == AudioTrack.SUCCESS }.getOrDefault(false)
        if (applied) entry.appliedVolume = volume
    }

    private fun AudioChannel.focusPriority(): Int = when (this) {
        AudioChannel.MEDIA -> 1
        AudioChannel.PHONE -> 3
        AudioChannel.ASSISTANT -> 2
        AudioChannel.NAVIGATION -> 0
    }

    private companion object {
        const val TAG = "DiPlay-AudioFocus"
        const val FULL_VOLUME = 1f
        const val DUCKED_VOLUME = 0.2f
    }
}
