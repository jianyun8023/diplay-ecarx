package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec

/**
 * Payload conversion adapters for the records Android MediaCodec and AudioTrack expect.
 * Video wire conversion shares the receiver's Rust owner; SDK format objects stay with the caller.
 */
object MediaCodecSupport {
    /** Splits the same AVC record consumed by either Android decoder path. */
    fun avcParameterSets(codecData: ByteArray): Pair<ByteArray, ByteArray> =
        com.shilapi.xcertplay.airplay.NativeScreenCodec.avcParameterSets(codecData).let { it[0] to it[1] }

    fun hevcCodecSpecificData(codecData: ByteArray): ByteArray =
        com.shilapi.xcertplay.airplay.NativeScreenCodec.hevcCsd(codecData)

    /** Existing Annex B input keeps its identity; length-prefixed input is copied, never mutated. */
    fun toAnnexB(lengthPrefixed: ByteArray): ByteArray =
        com.shilapi.xcertplay.airplay.NativeScreenCodec.annexBCopy(lengthPrefixed)

    fun isRandomAccess(annexB: ByteArray, codec: VideoCodec): Boolean =
        com.shilapi.xcertplay.airplay.NativeScreenCodec.randomAccess(annexB, codec == VideoCodec.H265)

    const val ADTS_HEADER_BYTES = 7

    /** Wraps one raw AAC-LC access unit in an MPEG-4 ADTS frame. */
    fun adtsFrame(accessUnit: ByteArray, sampleRate: Int, channels: Int): ByteArray =
        ByteArray(ADTS_HEADER_BYTES).also { writeAdtsHeader(it, accessUnit.size, sampleRate, channels) } + accessUnit

    /** Writes the ADTS header for an access unit of [accessUnitBytes] into [header] (7 bytes). */
    fun writeAdtsHeader(header: ByteArray, accessUnitBytes: Int, sampleRate: Int, channels: Int) {
        val failedIndex = NativeAac.writeAdtsHeader(header, accessUnitBytes, sampleRate, channels)
        // The original code wrote the valid prefix before the SDK array write failed. Let the
        // current VM perform that same failing write, preserving Android/JDK exception semantics.
        if (failedIndex >= 0) header[failedIndex] = 0
    }

    /** Extracts one RFC 3640 AAC access unit from an RTP payload. */
    fun aacAccessUnit(rtpPayload: ByteArray): ByteArray = NativeAac.accessUnit(rtpPayload)

    /** MPEG-4 sampling frequency index used by both ADTS and AudioSpecificConfig. */
    fun aacFrequencyIndex(sampleRate: Int): Int = NativeAac.frequencyIndex(sampleRate)

}
