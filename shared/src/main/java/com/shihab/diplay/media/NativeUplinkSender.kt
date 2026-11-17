package com.shihab.diplay.media

import com.shihab.diplay.airplay.AudioCodecKind
import com.shihab.diplay.airplay.MicrophoneConfig
import com.shihab.diplay.airplay.NativeMedia
import java.net.Inet6Address

/** Owns one Rust capture pipeline, including residual PCM, echo leases, Speex, encoder and socket. */
internal class NativeUplinkSender private constructor(private var handle: Long) : AutoCloseable {
    @Synchronized
    fun enableEcho(source: EchoReference?): Boolean = handle != 0L && NativeMicrophone.enableEcho(handle, source)

    @Synchronized
    fun disableEcho() { if (handle != 0L) NativeMicrophone.disableEcho(handle) }

    @Synchronized
    fun feed(input: ByteArray, count: Int, now: Long, stamped: Boolean, timestamp: Long, position: Long,
        host: MicrophoneUplink, stats: MicrophoneCaptureStats): Int =
        if (handle == 0L) -1 else NativeMicrophone.feed(handle, input, count, now, stamped, timestamp, position, host, stats.owner)

    @Synchronized
    override fun close() {
        if (handle != 0L) NativeMicrophone.close(handle)
        handle = 0L
    }

    companion object {
        /** Null means startup failed; capture requires the native pipeline. */
        fun create(config: MicrophoneConfig): NativeUplinkSender? {
            val handle = NativeMicrophone.create(config.key, config.payloadType, config.host.address,
                (config.host as? Inet6Address)?.scopeId ?: 0, config.port,
                if (config.codec == AudioCodecKind.OPUS) config.bitrate ?: 48_000 else 0,
                config.rtpSamplesPerPacket, config.frameBytes, config.sampleRate, config.channels)
            return if (handle == 0L) null else NativeUplinkSender(handle)
        }
    }
}

internal object NativeMicrophone {
    init { NativeMedia.requireAvailable() }
    @JvmStatic external fun create(key: ByteArray, payloadType: Int, address: ByteArray, scopeId: Int, port: Int,
        bitrate: Int, samplesPerPacket: Int, frameBytes: Int, sampleRate: Int, channels: Int): Long
    // JNI uses the actual platform object class, also across Robolectric's sandbox class loader.
    @JvmStatic external fun enableEcho(handle: Long, source: Any?): Boolean
    @JvmStatic external fun disableEcho(handle: Long)
    @JvmStatic external fun feed(handle: Long, input: ByteArray, count: Int, now: Long, stamped: Boolean,
        timestamp: Long, position: Long, host: Any, stats: LongArray): Int
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun uplinkConfigCreate(): Long
    @JvmStatic external fun uplinkConfigDecide(handle: Long, fingerprint: String): Int
    @JvmStatic external fun uplinkConfigCommit(handle: Long, fingerprint: String)
    @JvmStatic external fun uplinkConfigDestroy(handle: Long)
}
