package com.shihab.diplay.airplay

import java.net.InetAddress

/** Everything one captured microphone stream needs to send samples to the phone. */
data class MicrophoneConfig(
    val audioType: String,
    val sampleRate: Int,
    val channels: Int,
    val payloadType: Int,
    val frameMillis: Int,
    val host: InetAddress,
    val port: Int,
    val key: ByteArray,
    val codec: AudioCodecKind = AudioCodecKind.LPCM,
    val bitrate: Int? = null,
    /**
     * The CarPlay clock for an Opus stream. Capture and encoding remain at 48 kHz.
     * Captured Siri packets for audioFormat 0x20000000 advance by 480 per 20 ms and calls
     * (0x40000000) by 960, so the clock follows the format's rate; 0x10000000 counts at 16 kHz on that
     * rule. The report's "Audio: rtp clock" line measures the iPhone's own clock for each stream.
     */
    val opusClockRate: Int = OPUS_CAPTURE_RATE,
) {
    private val plan = NativeMicrophonePacketizer.plan(codec == AudioCodecKind.OPUS, sampleRate, channels, frameMillis, opusClockRate)
    val samplesPerPacket: Int get() = plan[0]
    val rtpClockRate: Int get() = plan[1]
    val rtpSamplesPerPacket: Int get() = plan[2]
    val frameBytes: Int get() = plan[3]

    companion object {
        private const val OPUS_CAPTURE_RATE = 48_000

        /** The negotiated Opus format's rate: 48, 24 or 16 kHz; 48 kHz when no Opus bit is set. */
        fun opusClockRate(formatBits: Long): Int = NativeMicrophonePacketizer.opusClockRate(formatBits)

        /**
         * Milliseconds of PCM per packet when the iPhone names no framesPerPacket: the longest of 20, 10 and 5 ms whose
         * sealed packet still fits one 1,500-byte link MTU with its IPv6 and UDP headers. 20 ms of 48 kHz mono is a
         * 1,956-byte packet that leaves as IP fragments. Over USB every 48 kHz recording in the #1094 reports came out
         * silent while 24 kHz Siri packets (996 bytes) worked, and wireless sends small Opus packets instead (#718,
         * #1017). 48 kHz mono therefore sends 10 ms, the 480 samples a 24 kHz Siri packet already carries.
         */
        fun pcmFrameMillis(sampleRate: Int, channels: Int): Int =
            NativeMicrophonePacketizer.pcmFrameMillis(sampleRate, channels)
    }
}

/** Mutable RTP/ChaCha counters for one microphone uplink. */
class MicrophoneCounters(sequence: Int = 0, timestamp: Int = 0, nonce: Long = 0) {
    internal val owner = NativeMicrophonePacketizer.counters(sequence, timestamp, nonce)
    var sequence: Int
        get() = NativeMicrophonePacketizer.counter(owner, 0, false, 0).toInt()
        set(value) { NativeMicrophonePacketizer.counter(owner, 0, true, value.toLong()) }
    var timestamp: Int
        get() = NativeMicrophonePacketizer.counter(owner, 1, false, 0).toInt()
        set(value) { NativeMicrophonePacketizer.counter(owner, 1, true, value.toLong()) }
    var nonce: Long
        get() = NativeMicrophonePacketizer.counter(owner, 2, false, 0)
        set(value) { NativeMicrophonePacketizer.counter(owner, 2, true, value) }
}

/** The Java-socket path shares the native sender's RTP seal and owns no Kotlin protocol counters. */
object MicrophonePacketizer {
    const val RTP_HEADER_LEN = 12
    const val TAG_LEN = 16
    const val NONCE_LEN = 8
    const val SEAL_OVERHEAD = RTP_HEADER_LEN + TAG_LEN + NONCE_LEN

    fun sealPacket(key: ByteArray, payloadType: Int, counters: MicrophoneCounters, body: ByteArray, samples: Int): ByteArray =
        NativeMicrophonePacketizer.seal(counters.owner, key, payloadType, body, samples)

    fun toWirePcm(pcmLittleEndian: ByteArray): ByteArray = NativeMicrophonePacketizer.wirePcm(pcmLittleEndian)
}

internal object NativeMicrophonePacketizer {
    init { NativeMedia.requireAvailable() }
    @JvmStatic external fun counters(sequence: Int, timestamp: Int, nonce: Long): LongArray
    @JvmStatic external fun counter(owner: LongArray, field: Int, write: Boolean, value: Long): Long
    @JvmStatic external fun seal(owner: LongArray, key: ByteArray, payloadType: Int, body: ByteArray, samples: Int): ByteArray
    @JvmStatic external fun wirePcm(pcm: ByteArray): ByteArray
    @JvmStatic external fun plan(opus: Boolean, sampleRate: Int, channels: Int, frameMillis: Int, opusClockRate: Int): IntArray
    @JvmStatic external fun opusClockRate(formatBits: Long): Int
    @JvmStatic external fun pcmFrameMillis(sampleRate: Int, channels: Int): Int
}
