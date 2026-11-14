package com.shihab.diplay.media

import android.view.Surface
import com.shihab.diplay.airplay.MediaPacket
import com.shihab.diplay.airplay.NativeMedia
import com.shihab.diplay.airplay.NativePacket
import com.shihab.diplay.airplay.NativeVideoCallbacks

/**
 * A screen stream's decode loop in `libdiplay_media`: frames go from the receiver straight into an
 * NDK MediaCodec in async mode, and the codec's callbacks release pictures paced as [VideoDecoder]
 * paces them, so nothing polls the codec. [VideoDecoder] keeps every decision (which decoder, which
 * format keys, which surface, when to rebuild) and acts on the pipe's events from its worker.
 */
internal class NativeVideoPipe private constructor(private val handle: Long) {
    fun retainTarget(): Long = NativeMedia.pipeRetain(handle)
    /** Takes ownership of [frame]. A Kotlin receiver's frame (packet capture) is copied into a pooled one. */
    fun submit(frame: MediaPacket, senderNanos: Long, arrivalNanos: Long) {
        val native = frame as? NativePacket ?: run {
            val annexB = frame.annexBOrNull()
            frame.release()
            if (annexB == null) return
            val copy = NativeMedia.packetWrap(annexB.toByteArray(), annexB.size)
            if (copy == 0L) return
            // A Kotlin frame carries no flags: the random-access bits are worked out here, once.
            val flags = MediaPacket.FLAG_ANNEX_B or
                (if (annexB.isRandomAccess(com.shihab.diplay.airplay.VideoCodec.H264)) MediaPacket.FLAG_RANDOM_ACCESS_H264 else 0) or
                (if (annexB.isRandomAccess(com.shihab.diplay.airplay.VideoCodec.H265)) MediaPacket.FLAG_RANDOM_ACCESS_H265 else 0)
            NativeMedia.pipeSubmit(handle, copy, annexB.size, flags, senderNanos, arrivalNanos)
            return
        }
        NativeMedia.pipeSubmit(handle, native.detach(), native.size, native.flags, senderNanos, arrivalNanos)
    }

    /** Null on success, else why ([detail] carries the codec name the platform gave, when it got that far). */
    class Result(val status: Int, val detail: String?)

    fun configure(
        name: String?, mime: String, width: Int, height: Int, keys: Map<String, Int>, csd: List<ByteArray>,
        surface: Surface, hevc: Boolean, reportVendorFormat: Boolean,
    ): Result {
        val detail = arrayOfNulls<String>(1)
        val status = NativeMedia.pipeConfigure(
            handle, name, mime, width, height, keys.keys.toTypedArray(), keys.values.toIntArray(),
            csd.getOrNull(0), csd.getOrNull(1), surface, hevc, reportVendorFormat, detail,
        )
        return Result(status, detail[0])
    }

    fun release() = NativeMedia.pipeRelease(handle)

    /** Moves the running decoder to [surface]; [shown] false for the parking consumer. */
    fun setSurface(surface: Surface, shown: Boolean): Boolean = NativeMedia.pipeSetSurface(handle, surface, shown) == 0

    /** Pair each received configuration with its worker completion; overlapping configurations stay held. */
    fun hold(hold: Boolean) = NativeMedia.pipeHold(handle, hold)

    fun setAccepting(accepting: Boolean, resetChain: Boolean) = NativeMedia.pipeSetAccepting(handle, accepting, resetChain)

    fun live(): LongArray = LongArray(4).also { NativeMedia.pipeLive(handle, it) }

    /** Moves the native five-second counters into [stats]. */
    fun drainStats(stats: VideoStats) {
        val values = LongArray(14)
        val decode = IntArray(1024)
        val count = NativeMedia.pipeTakeStats(handle, values, decode)
        stats.addNative(values, decode, count)
    }

    fun destroy() = NativeMedia.pipeDestroy(handle)

    companion object {
        const val EVENT_REQUEST_KEYFRAME = 1
        const val EVENT_REPORT = 2
        const val EVENT_NEED_DECODER = 3
        const val EVENT_RELEASE_DECODER = 4
        const val EVENT_CODEC_ERROR = 5
        const val EVENT_INPUT_STALLED = 6
        const val EVENT_FIRST_FRAME = 7
        const val EVENT_LOG = 8

        /** Null before API 28 or when the platform cannot create an async NDK MediaCodec loop. */
        fun create(pacingDelayNanos: Long, trackTouches: Boolean, onEvent: (Int, String?) -> Unit): NativeVideoPipe? {
            NativeMedia.requireAvailable()
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return null
            val callbacks = object : NativeVideoCallbacks() {
                override fun onEvent(kind: Int, detail: String?) = onEvent(kind, detail)
            }
            return NativeMedia.pipeCreate(callbacks, pacingDelayNanos, trackTouches).takeIf { it != 0L }?.let(::NativeVideoPipe)
        }
    }
}
