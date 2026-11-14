package com.shihab.diplay.media

import com.shihab.diplay.airplay.HeapPacket
import com.shihab.diplay.airplay.MediaPacket
import com.shihab.diplay.airplay.VideoCodec
import java.util.concurrent.TimeUnit

/** The decode-loop timing pieces the Kotlin side shares with the Rust vpipe. */
internal object NativeVideoFlow {
    init { com.shihab.diplay.airplay.NativeMedia.requireAvailable() }
    @JvmStatic external fun inputCreate(start: Long, timeout: Long): LongArray
    @JvmStatic external fun inputStep(owner: LongArray, event: Int, value: Long): Int
    @JvmStatic external fun inputResult(owner: LongArray): Int
    @JvmStatic external fun touchSent(sent: Long)
    @JvmStatic external fun touchFrame(now: Long): Long
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. Video and audio decoders share it. */
internal object DecoderInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val owner = NativeVideoFlow.inputCreate(nanoTime(), timeoutNs)
        var action = NativeVideoFlow.inputStep(owner, 0, 0)
        while (true) {
            action = when (action) {
                0 -> NativeVideoFlow.inputStep(owner, 1, if (running()) 1 else 0)
                1 -> { drain(); NativeVideoFlow.inputStep(owner, 2, 0) }
                2 -> NativeVideoFlow.inputStep(owner, 3, dequeue().toLong())
                3 -> NativeVideoFlow.inputStep(owner, 4, nanoTime())
                else -> return NativeVideoFlow.inputResult(owner)
            }
        }
    }
}

/**
 * The frame as Annex B, or null when it is not a valid access unit. A native receiver converted it
 * already; a Kotlin one's length-prefixed units are converted here, as [MediaCodecSupport.toAnnexB].
 */
internal fun MediaPacket.annexBOrNull(): MediaPacket? = when {
    flags and MediaPacket.FLAG_ANNEX_B != 0 -> this
    this is HeapPacket -> MediaCodecSupport.toAnnexB(bytes).let { converted ->
        when {
            converted.isEmpty() -> null
            converted === bytes -> this
            else -> HeapPacket(converted)
        }
    }
    else -> null
}

/** Whether an Annex B frame starts an independently decodable chain: IDR (AVC) or IRAP (HEVC). */
internal fun MediaPacket.isRandomAccess(codec: VideoCodec): Boolean =
    if (flags and MediaPacket.FLAG_ANNEX_B != 0) {
        flags and (if (codec == VideoCodec.H265) MediaPacket.FLAG_RANDOM_ACCESS_H265 else MediaPacket.FLAG_RANDOM_ACCESS_H264) != 0
    } else MediaCodecSupport.isRandomAccess(toByteArray(), codec)
