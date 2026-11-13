package com.shihab.diplay.airplay

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class AudioCodecKind { AAC_LC, OPUS, LPCM }

data class AudioFormat(
    val codec: AudioCodecKind,
    val sampleRate: Int,
    val channels: Int,
    val payloadType: Int,
    val audioType: String = "media",
    /**
     * The rate of the stream's RTP timestamps. Opus is always decoded at 48 kHz, but audioFormat
     * 0x10000000 and 0x20000000 count at 16 and 24 kHz (see [MicrophoneConfig.opusClockRate]).
     */
    val rtpClockRate: Int = sampleRate,
)

/**
 * Binds the RTP data and RTCP control UDP ports for one CarPlay audio stream.
 *
 * Wire layout follows LIVI `livi_audio_stream`: one RTP packet per datagram, a 12-byte header,
 * ciphertext, a 16-byte tag, then an 8-byte little-endian nonce. The header's last eight bytes
 * (timestamp + SSRC) are the AEAD associated data.
 *
 * Rust receives, opens and routes each packet directly to the renderer queue. With [preferNative]
 * false, native wiretap preserves raw datagrams and rejected packets for capture and disables the
 * direct route; socket binding, dump storage and hardware rendering remain Android adapters.
 */
class AudioStream(
    private val key: ByteArray,
    private val streamType: Int = -1,
    private val onDiagnostic: (String) -> Unit = {},
    preferNative: Boolean = true,
    clockRate: Int = 48_000,
) : Closeable {
    interface Listener {
        fun onStarted(firstSample: Int) {}
        fun onClock(hz: Int) {}
        fun onQueueEvent(kind: Int, value: Long, extra: Long) {}
        fun onRtp(rtp: ByteArray, sample: Int) {}
        /** An opened RTP packet the listener now owns: it releases [rtp] once done with it. */
        fun onPacket(rtp: MediaPacket, sample: Int) {
            try {
                onRtp(rtp.toByteArray(), sample)
            } finally {
                rtp.release()
            }
        }
        fun onPacket(
            wire: ByteArray,
            rtp: ByteArray?,
            sample: Int?,
            error: Throwable?,
        ) {}
    }

    private val closed = AtomicBoolean(false)
    internal val route = NativeAudioRoute(clockRate, direct = preferNative)
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var controlThread: Thread? = null
    private val nativeHandle = AtomicLong(0)

    fun listen(listener: Listener): Pair<Int, Int> {
        check(!closed.get()) { "AudioStream is closed" }
        var listening = false
        try {
            val data = bindAnyPort().also { dataSocket = it }
            // Keep short Wi-Fi bursts in the kernel while decrypting or scheduling pauses
            // the receive thread. The platform may cap this request; log the actual size.
            val originalBufferBytes = runCatching { data.receiveBufferSize }.getOrDefault(0)
            if (originalBufferBytes < AUDIO_RECEIVE_BUFFER_BYTES) {
                runCatching { data.receiveBufferSize = AUDIO_RECEIVE_BUFFER_BYTES }
            }
            onDiagnostic("Audio UDP receive buffer type=$streamType original=$originalBufferBytes requested=$AUDIO_RECEIVE_BUFFER_BYTES actual=${runCatching { data.receiveBufferSize }.getOrDefault(0)}")
            val control = bindAnyPort()
            controlSocket = control
            if (!startNative(data, listener)) {
                throw IOException("native audio receiver could not start")
            }
            controlThread = Thread({ runControl(control) }, "airplay-rtcp-rx").apply {
                isDaemon = true
                start()
            }
            return (data.localPort to control.localPort).also { listening = true }
        } finally { if (!listening) close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        route.close()
        nativeHandle.getAndSet(0).takeIf { it != 0L }?.let(NativeMedia::stop)
        dataSocket?.close()
        controlSocket?.close()
        controlThread?.interrupt()
    }

    /** Hands [socket]'s receiving to the native receiver; false when it could not start. */
    private fun startNative(socket: DatagramSocket, listener: Listener): Boolean {
        val fd = runCatching { ParcelFileDescriptor.fromDatagramSocket(socket).detachFd() }.getOrNull() ?: return false
        val callbacks = object : NativeStreamCallbacks() {
            override fun onPacket(handle: Long, size: Int, flags: Int, a: Long, b: Long) {
                val rtp = NativePacket(handle, size, flags)
                if (closed.get()) { rtp.release(); return }
                val sample = a.toInt()
                listener.onPacket(rtp, sample)
            }

            override fun onAudioStarted(sample: Int) { if (!closed.get()) listener.onStarted(sample) }
            override fun onAudioClock(hz: Int) { if (!closed.get()) listener.onClock(hz) }
            override fun onAudioQueueEvent(kind: Int, value: Long, extra: Long) {
                if (!closed.get()) listener.onQueueEvent(kind, value, extra)
            }
            override fun onAudioWire(wire: ByteArray, opened: ByteArray?, sample: Long, error: Int) {
                if (closed.get()) return
                val failure = when (error) {
                    1 -> IOException("audio packet shorter than RTP header plus tail")
                    2 -> javax.crypto.AEADBadTagException("ChaCha20-Poly1305 tag mismatch")
                    else -> null
                }
                listener.onPacket(wire, opened, if (error == 1) null else sample.toInt(), failure)
            }

            override fun onLog(level: Int, line: String) {
                if (level == 0) android.util.Log.i(TAG, line) else android.util.Log.w(TAG, line)
            }

            override fun onReport(line: String) = onDiagnostic(line)

            override fun onEnded(error: String?) {
                if (error != null) android.util.Log.w(TAG, "audio stream type=$streamType receive ended: $error")
            }
        }
        val handle = route.start(fd, key, streamType, callbacks)
        if (handle == 0L) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            return false
        }
        nativeHandle.set(handle)
        if (closed.get()) nativeHandle.getAndSet(0).takeIf { it != 0L }?.let(NativeMedia::stop)
        return true
    }

    private fun runControl(socket: DatagramSocket) {
        val buffer = ByteArray(DATAGRAM_BYTES)
        while (!closed.get()) {
            try {
                socket.receive(DatagramPacket(buffer, buffer.size))
            } catch (_: Exception) {
                if (closed.get()) return
            }
        }
    }

    private fun bindAnyPort(): DatagramSocket {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        return socket
    }

    private companion object {
        const val TAG = "diplay-usb"
        const val DATAGRAM_BYTES = 4_096
        const val AUDIO_RECEIVE_BUFFER_BYTES = 512 * 1024
    }
}

/** Maps the phone's negotiated audioFormat bits to a decode/render format. */
object AudioStreamCodec {
    fun fromFormatBits(bits: Long, payloadType: Int, audioType: String = "media"): AudioFormat {
        NativeMedia.requireAvailable()
        val row = NativeMedia.audioFormatBits(bits)
        return AudioFormat(AudioCodecKind.entries[row[0]], row[1], row[2], payloadType, audioType, row[3])
    }
}
