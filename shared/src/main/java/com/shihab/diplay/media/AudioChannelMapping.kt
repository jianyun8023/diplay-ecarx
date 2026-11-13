package com.shihab.diplay.media

import android.media.AudioManager
import com.shihab.diplay.airplay.NativeMedia

internal enum class AudioChannelMappingMode {
    MOBILE_COMPATIBLE,
    AUTOMOTIVE_BUS,
}

internal enum class AudioChannel {
    MEDIA,
    PHONE,
    ASSISTANT,
    NAVIGATION,
}

internal enum class AudioContentType {
    MUSIC,
    SPEECH,
}

internal data class AudioChannelSelection(
    val channel: AudioChannel,
    val contentType: AudioContentType,
    val streamType: Int = AudioManager.STREAM_MUSIC,
)

/**
 * Maps CarPlay stream metadata to Android audio routing values.
 *
 * Each selection carries the [AudioChannel] / [AudioContentType] pair used for usage-based
 * AudioAttributes, plus the stream type used when the stream-type AudioTrack path is active.
 * Guidance resolves to [AudioChannel.NAVIGATION]. Automotive routing keeps compatibility
 * audio on the media bus, so it receives the configured media buffer and output route.
 */
internal object AudioChannelMapper {
    const val STREAM_TYPE_MAIN_HIGH_AUDIO = 102
    const val DEFAULT_NAVIGATION_STREAM_TYPE = 14

    fun map(
        audioType: String,
        payloadType: Int,
        mode: AudioChannelMappingMode,
        navigationStreamType: Int = DEFAULT_NAVIGATION_STREAM_TYPE,
    ): AudioChannelSelection {
        val selection = NativeAudioRouting.map(audioType.lowercase(), payloadType, mode.ordinal, navigationStreamType)
        return AudioChannelSelection(AudioChannel.entries[selection[0]], AudioContentType.entries[selection[1]], selection[2])
    }

    fun usesNavigationStream(audioType: String, payloadType: Int, advanced: Boolean): Boolean =
        !advanced && map(audioType, payloadType, AudioChannelMappingMode.MOBILE_COMPATIBLE).channel == AudioChannel.NAVIGATION

    /**
     * When the car Bluetooth radio is off there is no SCO, so a CarPlay telephony stream has to
     * play on the media speaker instead of the phone usage.
     */
    fun playCallOnSpeaker(selection: AudioChannelSelection): AudioChannelSelection {
        val routed = NativeAudioRouting.speaker(selection.channel.ordinal, selection.contentType.ordinal, selection.streamType)
        return AudioChannelSelection(AudioChannel.entries[routed[0]], AudioContentType.entries[routed[1]], routed[2])
    }
}

internal object NativeAudioRouting {
    init { NativeMedia.requireAvailable() }
    @JvmStatic external fun map(audioType: String, payload: Int, mode: Int, navigationStream: Int): IntArray
    @JvmStatic external fun speaker(channel: Int, content: Int, stream: Int): IntArray
}
