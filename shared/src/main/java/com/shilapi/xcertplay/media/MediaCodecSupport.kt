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
        val frequencyIndex = aacFrequencyIndex(sampleRate)
        val channelConfig = channels.coerceIn(1, 7)
        val frameLength = accessUnitBytes + ADTS_HEADER_BYTES
        header[0] = 0xff.toByte()
        header[1] = 0xf1.toByte()
        header[2] = ((1 shl 6) or (frequencyIndex shl 2) or (channelConfig ushr 2)).toByte()
        header[3] = (((channelConfig and 0x3) shl 6) or (frameLength ushr 11)).toByte()
        header[4] = ((frameLength ushr 3) and 0xff).toByte()
        header[5] = (((frameLength and 0x7) shl 5) or 0x1f).toByte()
        header[6] = 0xfc.toByte()
    }

    /** Extracts one RFC 3640 AAC access unit from an RTP payload. */
    fun aacAccessUnit(rtpPayload: ByteArray): ByteArray {
        if (rtpPayload.size < 4) return ByteArray(0)
        val headerBits = readU16Be(rtpPayload, 0)
        if (headerBits < 16 || headerBits % 16 != 0) return ByteArray(0)
        val headerBytes = headerBits / 8
        if (2 + headerBytes > rtpPayload.size) return ByteArray(0)
        val auSize = (readU16Be(rtpPayload, 2) shr 3) and 0x1fff
        val start = 2 + headerBytes
        val end = minOf(start + auSize, rtpPayload.size)
        return if (end <= start) ByteArray(0) else rtpPayload.copyOfRange(start, end)
    }

    /** MPEG-4 sampling frequency index used by both ADTS and AudioSpecificConfig. */
    fun aacFrequencyIndex(sampleRate: Int): Int = when (sampleRate) {
        96_000 -> 0
        88_200 -> 1
        64_000 -> 2
        48_000 -> 3
        44_100 -> 4
        32_000 -> 5
        24_000 -> 6
        22_050 -> 7
        16_000 -> 8
        12_000 -> 9
        11_025 -> 10
        8_000 -> 11
        7_350 -> 12
        else -> 3
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

}
