package com.shilapi.xcertplay.airplay

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * One received media payload: a screen stream's access unit or an audio stream's RTP packet. A
 * native receiver's packet lives in a pooled buffer outside the Java heap ([NativePacket]); the Kotlin
 * receivers wrap an array ([HeapPacket]). Whoever holds a packet releases it exactly once, after the
 * bytes have gone to the decoder or when the packet is dropped; [share] gives a second owner its own
 * reference.
 */
abstract class MediaPacket internal constructor() {
    abstract val size: Int

    /** For a native screen packet: [FLAG_ANNEX_B] and the random-access bits; 0 when unknown. */
    abstract val flags: Int

    /** Copies [length] bytes from [offset] into [target] at its position and advances the position. */
    abstract fun copyInto(target: ByteBuffer, offset: Int = 0, length: Int = size - offset)

    /** The bytes on the heap: the array itself for a [HeapPacket], a copy for a native one. */
    abstract fun toByteArray(): ByteArray

    abstract fun share(): MediaPacket

    abstract fun release()

    companion object {
        /** The packet is Annex B already (a native screen receiver converted it). */
        const val FLAG_ANNEX_B = 1
        const val FLAG_RANDOM_ACCESS_H264 = 2
        const val FLAG_RANDOM_ACCESS_H265 = 4
    }
}

class HeapPacket(val bytes: ByteArray, override val flags: Int = 0) : MediaPacket() {
    override val size: Int get() = bytes.size

    override fun copyInto(target: ByteBuffer, offset: Int, length: Int) {
        target.put(bytes, offset, length)
    }

    override fun toByteArray(): ByteArray = bytes

    override fun share(): MediaPacket = this

    override fun release() {}
}

internal class NativePacket(handle: Long, override val size: Int, override val flags: Int = 0) : MediaPacket() {
    private val handle = AtomicLong(handle)

    private fun live(): Long = handle.get().also { check(it != 0L) { "media packet used after release" } }

    /** The handle native calls read the packet through; valid until [release]. */
    fun nativeHandle(): Long = live()

    /** Hands this reference to native code, which releases it; this wrapper is spent. */
    fun detach(): Long = handle.getAndSet(0L).also { check(it != 0L) { "media packet used after release" } }

    override fun copyInto(target: ByteBuffer, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= size - length)
        val position = target.position()
        require(length <= target.remaining()) { "media packet larger than its target" }
        val copied = if (target.isDirect) NativeMedia.packetCopyInto(live(), offset, length, target, position) else -1
        if (copied == length) {
            target.position(position + length)
        } else {
            target.put(toByteArray(), offset, length)
        }
    }

    override fun toByteArray(): ByteArray = ByteArray(size).also {
        check(NativeMedia.packetRead(live(), 0, size, it, 0)) { "media packet read failed" }
    }

    override fun share(): MediaPacket {
        val current = live()
        NativeMedia.packetRetain(current)
        return NativePacket(current, size, flags)
    }

    override fun release() {
        val current = handle.getAndSet(0L)
        if (current != 0L) NativeMedia.packetRelease(current)
    }
}
