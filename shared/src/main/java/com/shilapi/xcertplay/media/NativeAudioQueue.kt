package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.MediaPacket
import com.shilapi.xcertplay.airplay.NativeMedia
import com.shilapi.xcertplay.airplay.NativePacket
import java.io.Closeable

/** Serializes handle lifetime against producer, worker and close; Rust owns all queued references. */
internal class NativeAudioQueue private constructor(private var handle: Long) : Closeable {
    private val lock = Object()

    fun push(packet: MediaPacket, sample: Int, arrivedNs: Long): Int = synchronized(lock) {
        if (handle == 0L) { packet.release(); return@synchronized 0 }
        val native = if (packet is NativePacket) packet.detach() else {
            val copy = NativeMedia.packetWrap(packet.toByteArray(), packet.size)
            packet.release()
            copy
        }
        NativeMedia.audioQueuePush(handle, native, sample, arrivedNs).also { lock.notifyAll() }
    }

    fun poll(out: LongArray, timeoutMillis: Long = 0L): MediaPacket? = synchronized(lock) {
        var packet = NativeMedia.audioQueuePoll(handle, out)
        if (packet == 0L && handle != 0L && timeoutMillis > 0) {
            lock.wait(timeoutMillis)
            packet = NativeMedia.audioQueuePoll(handle, out)
        }
        if (packet == 0L) null else NativePacket(packet, NativeMedia.packetSize(packet))
    }

    val size: Int get() = synchronized(lock) { NativeMedia.audioQueueSize(handle) }
    fun pendingFrames(): Long = synchronized(lock) { NativeMedia.audioQueuePending(handle) }
    fun arrivedFrameNs(sinceNs: Long, nowNs: Long, sampleRate: Int): Double = synchronized(lock) {
        NativeMedia.audioQueueArrivals(handle, sinceNs, nowNs, sampleRate)
    }

    override fun close() = synchronized(lock) {
        if (handle != 0L) NativeMedia.audioQueueDestroy(handle)
        handle = 0L
        lock.notifyAll()
    }

    companion object {
        fun create(clockRate: Int, media: Boolean): NativeAudioQueue {
            check(NativeMedia.available) { "Rust core is unavailable: ${NativeMedia.loadError}" }
            return NativeAudioQueue(NativeMedia.audioQueueCreate(clockRate, media).also { check(it != 0L) })
        }
    }
}
