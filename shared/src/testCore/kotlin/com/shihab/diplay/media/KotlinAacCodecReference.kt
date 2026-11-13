package com.shihab.diplay.media

/** Independent test-only AAC formulas formerly in MediaCodecSupport and AudioRenderer. */
object KotlinAacCodecReference {
    fun writeAdtsHeader(header: ByteArray, accessUnitBytes: Int, sampleRate: Int, channels: Int) {
        val frequencyIndex = frequencyIndex(sampleRate)
        val channelConfig = channels.coerceIn(1, 7)
        val frameLength = accessUnitBytes + 7
        header[0] = 0xff.toByte()
        header[1] = 0xf1.toByte()
        header[2] = ((1 shl 6) or (frequencyIndex shl 2) or (channelConfig ushr 2)).toByte()
        header[3] = (((channelConfig and 0x3) shl 6) or (frameLength ushr 11)).toByte()
        header[4] = ((frameLength ushr 3) and 0xff).toByte()
        header[5] = (((frameLength and 0x7) shl 5) or 0x1f).toByte()
        header[6] = 0xfc.toByte()
    }
    fun frequencyIndex(rate: Int): Int = when (rate) {
        96_000 -> 0; 88_200 -> 1; 64_000 -> 2; 48_000 -> 3; 44_100 -> 4; 32_000 -> 5
        24_000 -> 6; 22_050 -> 7; 16_000 -> 8; 12_000 -> 9; 11_025 -> 10; 8_000 -> 11; 7_350 -> 12
        else -> 3
    }
    fun audioSpecificConfig(rate: Int, channels: Int): ByteArray {
        val value = (2 shl 11) or (frequencyIndex(rate) shl 7) or (channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }
    fun accessUnit(payload: ByteArray): ByteArray {
        if (payload.size < 4) return ByteArray(0)
        val headerBits = u16(payload, 0)
        if (headerBits < 16 || headerBits % 16 != 0) return ByteArray(0)
        val headerBytes = headerBits / 8
        if (2 + headerBytes > payload.size) return ByteArray(0)
        val auSize = (u16(payload, 2) shr 3) and 0x1fff
        val start = 2 + headerBytes
        val end = minOf(start + auSize, payload.size)
        return if (end <= start) ByteArray(0) else payload.copyOfRange(start, end)
    }
    private fun u16(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 255) shl 8) or (source[offset + 1].toInt() and 255)
}
