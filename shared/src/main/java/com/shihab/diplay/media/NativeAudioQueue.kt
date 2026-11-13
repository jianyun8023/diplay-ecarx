package com.shihab.diplay.media

import com.shihab.diplay.airplay.MediaPacket
import com.shihab.diplay.airplay.NativeMedia
import com.shihab.diplay.airplay.NativePacket
import java.io.Closeable

/** Rust owns the queue, wakeups, bounds and statistics. The worker retains once for its lifetime. */
internal class NativeAudioQueue private constructor(private var handle: Long) : Closeable {
    private val lock = Any()
    private val finalStats = LongArray(5)

    fun retain(): Long = synchronized(lock) {
        if (handle == 0L) 0L else NativeMedia.audioQueueRetain(handle)
    }

    fun consumer(): Consumer? = retain().takeIf { it != 0L }?.let(::Consumer)

    fun push(packet: MediaPacket, sample: Int, arrivedNs: Long): Int = synchronized(lock) {
        if (handle == 0L) { packet.release(); return@synchronized 0 }
        val native = if (packet is NativePacket) packet.detach() else {
            val copy = NativeMedia.packetWrap(packet.toByteArray(), packet.size)
            packet.release()
            copy
        }
        NativeMedia.audioQueuePush(handle, native, sample, arrivedNs)
    }

    fun event(out: LongArray): Boolean = synchronized(lock) {
        handle != 0L && NativeMedia.audioQueueEvent(handle, out)
    }

    fun stats(out: LongArray, reset: Boolean) = synchronized(lock) {
        if (handle != 0L) NativeMedia.audioQueueStats(handle, out, reset)
        else {
            finalStats.copyInto(out)
            if (reset) { finalStats[0] = 0; finalStats[1] = 0; finalStats[4] = 0 }
        }
    }

    val size: Int get() = synchronized(lock) { NativeMedia.audioQueueSize(handle) }
    fun pendingFrames(dropped: LongArray? = null): Long = synchronized(lock) {
        if (handle != 0L) NativeMedia.audioQueuePending(handle, dropped)
        else { if (dropped != null) dropped[0] = finalStats[2]; 0L }
    }
    fun arrivedFrameNs(sinceNs: Long, nowNs: Long, sampleRate: Int): Double = synchronized(lock) {
        NativeMedia.audioQueueArrivals(handle, sinceNs, nowNs, sampleRate)
    }

    override fun close() = synchronized(lock) {
        if (handle != 0L) {
            NativeMedia.audioQueueDestroy(handle, finalStats)
            handle = 0L
        }
    }

    /** Thread-confined retained reference; owner.close() wakes poll but never frees this reference. */
    class Consumer internal constructor(private var handle: Long) : Closeable {
        fun poll(out: LongArray, timeoutMillis: Long): MediaPacket? {
            val packet = NativeMedia.audioQueuePoll(handle, out, timeoutMillis)
            return if (packet == 0L) null else NativePacket(packet, out[2].toInt())
        }
        override fun close() {
            if (handle == 0L) return
            NativeMedia.audioQueueRelease(handle)
            handle = 0L
        }
    }

    companion object {
        fun create(clockRate: Int, media: Boolean): NativeAudioQueue {
            NativeMedia.requireAvailable()
            return NativeAudioQueue(NativeMedia.audioQueueCreate(clockRate, media).also { check(it != 0L) })
        }
    }
}
