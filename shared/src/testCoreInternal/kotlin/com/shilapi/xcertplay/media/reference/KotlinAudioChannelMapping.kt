@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package com.shilapi.xcertplay.media.reference

import com.shilapi.xcertplay.media.AudioChannel
import com.shilapi.xcertplay.media.AudioChannelMappingMode
import com.shilapi.xcertplay.media.AudioChannelSelection
import com.shilapi.xcertplay.media.AudioContentType

/**
 * Maps CarPlay stream metadata to Android audio routing values.
 *
 * Each selection carries the [AudioChannel] / [AudioContentType] pair used for usage-based
 * AudioAttributes, plus the stream type used when the stream-type AudioTrack path is active.
 * Guidance resolves to [AudioChannel.NAVIGATION]. Automotive routing keeps compatibility
 * audio on the media bus, so it receives the configured media buffer and output route.
 */
internal object KotlinAudioChannelMapper {
    const val STREAM_TYPE_MAIN_HIGH_AUDIO = 102
    const val DEFAULT_NAVIGATION_STREAM_TYPE = 14

    fun map(
        audioType: String,
        payloadType: Int,
        mode: AudioChannelMappingMode,
        navigationStreamType: Int = DEFAULT_NAVIGATION_STREAM_TYPE,
    ): AudioChannelSelection {
        val normalized = audioType.lowercase()
        return when (mode) {
            AudioChannelMappingMode.MOBILE_COMPATIBLE -> mapMobileCompatible(normalized, payloadType, navigationStreamType)
            AudioChannelMappingMode.AUTOMOTIVE_BUS -> mapAutomotiveBus(normalized, payloadType, navigationStreamType)
        }
    }

    fun usesNavigationStream(audioType: String, payloadType: Int, advanced: Boolean): Boolean =
        !advanced && map(audioType, payloadType, AudioChannelMappingMode.MOBILE_COMPATIBLE).channel == AudioChannel.NAVIGATION

    /**
     * When the car Bluetooth radio is off there is no SCO, so a CarPlay telephony stream has to
     * play on the media speaker instead of the phone usage.
     */
    fun playCallOnSpeaker(selection: AudioChannelSelection): AudioChannelSelection =
        if (selection.channel == AudioChannel.PHONE) {
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.SPEECH, selection.streamType)
        } else {
            selection
        }

    private fun mapMobileCompatible(
        audioType: String,
        payloadType: Int,
        navigationStreamType: Int,
    ): AudioChannelSelection = when (audioType) {
        "telephony" -> AudioChannelSelection(AudioChannel.PHONE, AudioContentType.SPEECH)
        "speechrecognition" ->
            AudioChannelSelection(AudioChannel.ASSISTANT, AudioContentType.SPEECH)
        "media" -> AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        "default", "alert", "compatibility" ->
            AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, navigationStreamType)
        else -> mainHighAudioOrNavigation(payloadType, navigationStreamType)
    }

    private fun mapAutomotiveBus(
        audioType: String,
        payloadType: Int,
        navigationStreamType: Int,
    ): AudioChannelSelection = when (audioType) {
        "telephony" -> AudioChannelSelection(AudioChannel.PHONE, AudioContentType.SPEECH)
        "speechrecognition" ->
            AudioChannelSelection(AudioChannel.ASSISTANT, AudioContentType.SPEECH)
        "media", "compatibility" ->
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        "default", "alert" -> AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, navigationStreamType)
        else -> mainHighAudioOrNavigation(payloadType, navigationStreamType)
    }

    private fun mainHighAudioOrNavigation(payloadType: Int, navigationStreamType: Int): AudioChannelSelection =
        if (payloadType == STREAM_TYPE_MAIN_HIGH_AUDIO) {
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        } else {
            AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, navigationStreamType)
        }
}
