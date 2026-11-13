package com.shilapi.xcertplay.media

import android.os.ParcelFileDescriptor
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.NativeMedia
import java.net.DatagramSocket
import java.net.Inet6Address

/**
 * The microphone's send path in `libdiplay_media`: each captured frame is encoded (opus-pure) or put
 * in wire order, sealed and sent in one call, in the layout of [com.shilapi.xcertplay.airplay.MicrophonePacketizer].
 * Its own duplicate of the uplink's socket lets [close] race the capture thread's last send safely.
 */
internal class NativeUplinkSender private constructor(private var handle: Long) : AutoCloseable {
    /** Packets sent (0 when the encoder produced none), -1 on an encoding failure, -errno on a send failure. */
    @Synchronized
    fun send(frame: ByteArray): Int = if (handle == 0L) -1 else NativeMedia.uplinkSend(handle, frame, frame.size)

    @Synchronized
    override fun close() {
        if (handle != 0L) NativeMedia.uplinkClose(handle)
        handle = 0L
    }

    companion object {
        /** Null when the socket cannot be shared or the sender rejects its configuration. */
        fun create(socket: DatagramSocket, config: MicrophoneConfig): NativeUplinkSender? {
            NativeMedia.requireAvailable()
            val fd = runCatching { ParcelFileDescriptor.fromDatagramSocket(socket).detachFd() }.getOrNull() ?: return null
            val bitrate = if (config.codec == AudioCodecKind.OPUS) config.bitrate ?: 48_000 else 0
            val handle = NativeMedia.uplinkCreate(
                fd, config.key, config.payloadType, config.host.address, (config.host as? Inet6Address)?.scopeId ?: 0,
                config.port, bitrate, config.rtpSamplesPerPacket,
            )
            if (handle == 0L) {
                runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
                return null
            }
            return NativeUplinkSender(handle)
        }
    }
}
