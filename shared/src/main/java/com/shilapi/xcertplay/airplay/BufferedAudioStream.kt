package com.shilapi.xcertplay.airplay

import android.util.Log
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay's main buffered audio (WWDC23 "Enhanced buffering"). For apps that support it (Apple Music),
 * the iPhone sends music ahead of time over TCP, here up to about two minutes, so short Wi-Fi gaps do
 * not interrupt playback. The car keeps the timing: SETRATE starts playback at an RTP time and the car
 * answers with the anchor (when that sample plays on the session's network clock); GETANCHOR reads it;
 * SETRATEANCHORTIME with rate 0 pauses; FLUSHBUFFERED drops what was sent before a track change.
 *
 * Frames are length-prefixed RTP packets sealed with the stream's key (shk) like the other audio
 * streams. Rust keeps them and hands them to the [MediaSink] about one second ahead of playback, so
 * the media renderer (focus, ducking, channel settings) treats them like any music stream.
 *
 * With [NativeMedia], Rust owns receive, queues, anchors, pacing and backpressure; this adapter orders
 * platform callbacks against control/close.
 */
internal class BufferedAudioStream(
    private val key: ByteArray,
    private val format: AudioFormat,
    private val sink: MediaSink,
    private val log: (String) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
    private val expectedPeer: InetAddress = InetAddress.getLoopbackAddress(),
) : Closeable {
    init { NativeMedia.requireAvailable() }

    private val id = AudioStreamId(format.payloadType, format.audioType)
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private var cleanupStarted = false // guarded by lock; permits reentrant close without recursion
    @Volatile internal var outputCleanupComplete = false
        private set
    private val server = ServerSocket(0)
    private val boundPort = server.localPort
    @Volatile private var client: Socket? = null
    private val lock = Object()
    private var nativeCore = NativeMedia.bufferedCreate(format.sampleRate).also { check(it != 0L) }
    private var nativeReceiver = 0L
    val port: Int get() = boundPort

    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        Thread({ receive() }, "airplay-buffered-rx").apply { isDaemon = true; start() }
        Thread({ feed() }, "airplay-buffered-feed").apply { isDaemon = true; start() }
    }

    private fun receive() {
        try {
            val socket = acceptPeer() ?: return
            synchronized(lock) {
                if (closed.get()) return
                server.close()
                val fd = ParcelFileDescriptor.fromSocket(socket).detachFd()
                nativeReceiver = NativeMedia.bufferedStart(nativeCore, fd, key, object : NativeStreamCallbacks() {
                    override fun onPacket(handle: Long, size: Int, flags: Int, a: Long, b: Long) {
                        NativeMedia.packetRelease(handle)
                        error("buffered receiver must deliver into its core queue")
                    }
                    override fun onLog(level: Int, line: String) { log(line) }
                    override fun onReport(line: String) { log(line) }
                    override fun onEnded(error: String?) {
                        synchronized(lock) {
                            runCatching { client?.close() }
                            client = null
                        }
                        if (!closed.get() && error != null) log("Buffered audio: connection ended ($error)")
                        if (error?.startsWith("invalid frame length") == true) close()
                    }
                })
                if (nativeReceiver == 0L) {
                    ParcelFileDescriptor.adoptFd(fd).close()
                    close()
                } else log("Buffered audio: connected impl=rust")
            }
        } catch (error: Exception) {
            if (!closed.get()) log("Buffered audio: connection ended (${error.javaClass.simpleName})")
            close()
        }
    }

    private fun acceptPeer(): Socket? {
        while (!closed.get()) {
            val socket = server.accept()
            val accepted = synchronized(lock) {
                if (closed.get()) {
                    runCatching { socket.close() }
                    return null
                }
                if (socket.inetAddress != expectedPeer) {
                    runCatching { socket.close() }
                    log("Buffered audio: rejected sender outside the control session")
                    false
                } else {
                    client = socket
                    true
                }
            }
            if (accepted) return socket
        }
        return null
    }

    /** Orders native deliveries against pause, flush and close under the output lock. */
    private fun feed() {
        val step = LongArray(3)
        try {
            while (!closed.get()) synchronized(lock) {
                if (closed.get()) return
                val handle = NativeMedia.bufferedNext(nativeCore, nanoTime(), step)
                if (handle == 0L) lock.wait(FEED_POLL_MILLIS)
                else {
                    val packet = NativePacket(handle, NativeMedia.packetSize(handle))
                    try {
                        if (step[1] != 0L) sink.onAudioStarted(id, format, step[0].toInt())
                        if (!closed.get() && NativeMedia.bufferedCommit(nativeCore, step[2])) {
                            sink.onAudioPacket(id, format, packet.share(), step[0].toInt())
                        }
                    } finally { packet.release() }
                }
            }
        } catch (_: InterruptedException) {
        } catch (error: Exception) {
            if (!closed.get()) Log.w(TAG, "buffered audio feed failed", error)
            close()
        }
    }

    /**
     * SETRATE / SETRATEANCHORTIME. Rate 1 starts at [rtpTime] (frames before it are dropped) about
     * 400 ms from [nowNtp]; rate 0 pauses where playback is. Returns the new anchor.
     */
    fun setRate(rtpTime: Long?, newRate: Int, nowNtp: BigInteger): Map<String, Any?>? = synchronized(lock) {
        if (closed.get()) return@synchronized null
        nativeControl(1, rtpTime, newRate, nowNtp)
    }

    /** GETANCHOR: the current anchor, or null before the first SETRATE. */
    fun anchor(): Map<String, Any?>? = synchronized(lock) {
        if (closed.get()) null else nativeControl(0)
    }

    /** FLUSHBUFFERED: drop what was sent before [untilTimestamp] and restart the output there. */
    fun flush(untilTimestamp: Long?) = synchronized(lock) {
        if (!closed.get()) nativeControl(2, untilTimestamp)
    }

    /** The /feedback entry for this stream: the sample playing now on the session clock. */
    fun feedback(nowNtp: BigInteger, connectionId: Any?): Map<String, Any?> = synchronized(lock) {
        val now = nanoTime()
        val position = if (closed.get()) -1L else {
            val values = LongArray(5)
            NativeMedia.bufferedControl(nativeCore, 3, -1, 0, 0, 0, now, values)
            values[0]
        }
        NativeMedia.bufferedFeedback(format.payloadType, format.sampleRate, position,
            if (position >= 0) unsignedPlistInteger(connectionId) else null, nowNtp, now)
    }

    // The protocol still uses plist maps; the core retains the anchor and returns a snapshot.
    private fun nativeControl(op: Int, timestamp: Long? = null, newRate: Int = 0,
        ntp: BigInteger = BigInteger.ZERO): Map<String, Any?>? {
        val values = LongArray(5)
        NativeMedia.bufferedControl(nativeCore, op, timestamp?.and(U32) ?: -1, newRate,
            ntp.shiftRight(32).toLong(), ntp.and(BigInteger.valueOf(U32)).toLong(), nanoTime(), values)
        if (values[4] != 0L) runCatching { sink.onAudioStopped(id) }
            .onFailure { log("Buffered audio: output cleanup failed (${it.javaClass.simpleName})") }
        lock.notifyAll()
        return NativeMedia.bufferedAnchor(values)
    }

    override fun close() {
        closed.set(true)
        runCatching { server.close() }
        synchronized(lock) {
            // A second external closer must wait for the first one's sink cleanup, rather
            // than return just because closed was set while a delivery callback was active.
            if (cleanupStarted) return
            cleanupStarted = true
            try {
                runCatching { client?.close() }
                client = null
                val handle = nativeCore
                nativeCore = 0L
                val stopOutput = NativeMedia.bufferedDestroy(handle)
                if (nativeReceiver != 0L) NativeMedia.stop(nativeReceiver)
                nativeReceiver = 0L
                if (stopOutput) runCatching { sink.onAudioStopped(id) }
                    .onFailure { log("Buffered audio: output cleanup failed (${it.javaClass.simpleName})") }
            } finally {
                outputCleanupComplete = true
                lock.notifyAll()
            }
        }
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        const val STREAM_TYPE = 103
        /** Advertised and locally enforced; a full buffer applies TCP backpressure. */
        const val AUDIO_BUFFER_BYTES = 8 * 1024 * 1024
        private const val FEED_POLL_MILLIS = 20L
        private const val U32 = 0xffff_ffffL
    }
}
