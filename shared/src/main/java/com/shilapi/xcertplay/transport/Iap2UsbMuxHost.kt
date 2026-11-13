package com.shilapi.xcertplay.transport

import android.util.Log
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

/**
 * USBMUX version-2 host over an already claimed iPhone bulk pipe.
 *
 * This is deliberately limited to USBMUX framing and one minimal outbound TCP connection. It
 * does not parse lockdown messages, iAP2, TLS, NCM, AirPlay, or any media protocol. All calls
 * may block and must run away from Android's main thread.
 */
class Iap2UsbMuxHost private constructor(
    private val pipe: Iap2UsbSession,
    private val readTimeoutMillis: Long,
    private val onDiagnostic: (String) -> Unit,
) : Closeable {
    private val stateLock = Any()
    private val writeLock = Any()
    private val connections = mutableMapOf<Int, Iap2UsbMuxTcpConnection>()
    private var failure: IphoneUsbException? = null
    private var receivedFrames = 0
    private val loggedControlFrames = AtomicInteger()
    private val loggedLargeWrites = AtomicInteger()
    private lateinit var readerThread: Thread
    private val receiveFrames = UsbMuxFrameBuffer { line ->
        Log.w("xcertplay-usb", line)
        runCatching { onDiagnostic(line) }
    }

    private var handle = NativeUsbMux.hostCreate()
    private val closed: Boolean get() = handle == 0L

    /** Opens a TCP byte stream to the iPhone service on [destinationPort]. */
    fun connect(
        destinationPort: Int = LOCKDOWN_PORT,
        timeoutMillis: Long = CONNECT_TIMEOUT_MILLIS,
    ): Iap2UsbMuxTcpConnection {
        require(destinationPort in 1..0xffff) { "destinationPort must be a valid TCP port" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val connection = synchronized(stateLock) {
            checkOpenLocked()
            val sourcePort = allocateSourcePortLocked()
            Iap2UsbMuxTcpConnection(this, sourcePort, destinationPort).also {
                connections[sourcePort] = it
            }
        }
        try {
            connection.beginConnect()
            if (!connection.awaitConnected(timeoutMillis)) {
                connection.abort()
                throw IphoneUsbException.TimedOut("USBMUX TCP connection to port $destinationPort timed out")
            }
            return connection
        } catch (error: IphoneUsbException) {
            try {
                connection.closeFromHost(error)
                connection.finishClose()
            } finally { removeConnection(connection) }
            throw error
        }
    }

    override fun close() {
        val activeConnections = synchronized(stateLock) {
            if (closed) return
            NativeUsbMux.hostDestroy(handle)
            handle = 0L
            connections.values.toList().also { connections.clear() }
        }
        activeConnections.forEach(Iap2UsbMuxTcpConnection::closeFromHost)
        try { pipe.close() } finally {
            receiveFrames.close()
            activeConnections.forEach(Iap2UsbMuxTcpConnection::finishClose)
        }
        if (::readerThread.isInitialized && Thread.currentThread() !== readerThread) {
            try {
                readerThread.join(readTimeoutMillis + CLOSE_JOIN_MARGIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    internal fun sendTcp(
        sourcePort: Int,
        destinationPort: Int,
        sequence: Int,
        acknowledgement: Int,
        flags: Int,
        payload: ByteArray,
    ) {
        if (payload.size > 512 && loggedLargeWrites.incrementAndGet() <= LOGGED_RX_FRAMES) Log.i("xcertplay-usb", "usbmux TX source=$sourcePort destination=$destinationPort bytes=${payload.size} seq=$sequence ack=$acknowledgement")
        sendFrame(NativeUsbMux.encodeTcp(sourcePort, destinationPort, sequence, acknowledgement, flags, payload))
    }

    internal fun sendSegment(tcp: ByteArray, fields: IntArray? = null) {
        if (fields != null && fields[0] > 512 && loggedLargeWrites.incrementAndGet() <= LOGGED_RX_FRAMES) {
            Log.i("xcertplay-usb", "usbmux TX source=${fields[1]} destination=${fields[2]} bytes=${fields[0]} seq=${fields[3]} ack=${fields[4]}")
        }
        sendFrame(tcp)
    }

    internal fun removeConnection(connection: Iap2UsbMuxTcpConnection) {
        synchronized(stateLock) {
            if (connections[connection.sourcePort] === connection) {
                connections.remove(connection.sourcePort)
            }
        }
    }

    private fun begin() {
        pipe.write(NativeUsbMux.version(), HANDSHAKE_TIMEOUT_MILLIS.toInt())
        // The phone replies with the same proto=0, length=20, version=2 packet. Protocol 1 is not
        // a distinct "version reply" here; waiting for it discards the valid reply and times out.
        val deadline = System.nanoTime() + HANDSHAKE_TIMEOUT_MILLIS * NANOS_PER_MILLISECOND
        var reply: UsbMuxFrame
        while (true) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) {
                throw IphoneUsbException.TimedOut("Timed out waiting for the USBMUX version reply")
            }
            val remainingMillis = (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND
            reply = takeFrame(remainingMillis)
                ?: throw IphoneUsbException.TimedOut("Timed out waiting for the USBMUX version reply")
            val decision = synchronized(stateLock) {
                checkOpenLocked()
                NativeUsbMux.handshake(handle, reply.protocol, reply.length, reply.word8)
            }
            if (decision == 0) break
            if (decision == 2) throw IphoneUsbException.Protocol(
                "Invalid USBMUX version reply: proto=${reply.protocol} length=${reply.length} version=${reply.word8}")
            Log.i("xcertplay-usb", "discarding stale usbmux TCP frame before version reply")
        }
        Log.i("xcertplay-usb", "usbmux version accepted: ${reply.word8}")
        // Optional reply padding is handled by the incremental framer. Retain a possible
        // fragmented/coalesced next frame instead of discarding the entire remainder.
        sendFrame(null)
        readerThread = Thread(::readerLoop, "iap2-usbmux-reader").apply {
            isDaemon = true
            start()
        }
    }

    /** Reads one complete USBMUX frame, keeping partial data buffered across reads. */
    private fun takeFrame(timeoutMillis: Long): UsbMuxFrame? {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        while (true) {
            synchronized(stateLock) {
                if (closed) throw IphoneUsbException.DeviceUnavailable("USBMUX host is closed")
            }
            // Diagnostic callbacks may stop the host; neither its lock nor a Rust borrow spans them.
            receiveFrames.takeFrame()?.let { frame ->
                if (++receivedFrames <= LOGGED_RX_FRAMES) Log.i(
                    "xcertplay-usb", "usbmux rx proto=${frame.protocol} length=${frame.length} word8=0x" + frame.word8.toUInt().toString(16))
                synchronized(stateLock) { if (!closed) NativeUsbMux.muxReceived(handle, frame.sequence) }
                return frame
            }
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) return null
            val remainingMillis = (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND
            val bytes = pipe.read(remainingMillis) ?: continue
            receiveFrames.append(bytes)
        }
    }

    private fun sendFrame(tcp: ByteArray?) {
        try {
            synchronized(writeLock) {
                val frame = synchronized(stateLock) {
                    checkOpenLocked()
                    NativeUsbMux.muxFrame(handle, tcp)
                }
                pipe.write(frame, WRITE_TIMEOUT_MILLIS)
                synchronized(stateLock) { if (!closed) NativeUsbMux.muxSent(handle) }
            }
        } catch (error: IphoneUsbException) {
            // Do not hold the host write lock while retiring a different TCP writer.
            fail(error)
            throw error
        }
    }

    private fun readerLoop() {
        try {
            while (true) {
                synchronized(stateLock) {
                    if (closed) return
                }
                val frame = takeFrame(readTimeoutMillis) ?: continue
                dispatchTcp(frame)
            }
        } catch (error: IphoneUsbException) {
            fail(error)
        } catch (error: RuntimeException) {
            fail(IphoneUsbException.DeviceUnavailable("USBMUX reader failed", error))
        }
    }

    private fun dispatchTcp(frame: UsbMuxFrame) {
        val decoded = NativeUsbMux.parseTcp(frame.protocol, frame.payload) ?: return
        val fields = decoded[0] as IntArray
        val payload = decoded[1] as ByteArray
        val destination = fields[0]
        val flags = fields[1]
        if (payload.isEmpty() && (flags and TCP_SYN_FIN_RST != 0 || loggedControlFrames.incrementAndGet() <= LOGGED_RX_FRAMES)) {
            Log.i("xcertplay-usb", "usbmux TCP control destination=$destination flags=$flags ack=${fields[3]} window=${fields[4]}")
        }
        val connection = synchronized(stateLock) { connections[destination] } ?: return
        connection.onPacket(flags, fields[2], payload)
    }

    private fun fail(error: IphoneUsbException) {
        val activeConnections = synchronized(stateLock) {
            if (closed) return
            failure = failure ?: error
            NativeUsbMux.hostDestroy(handle)
            handle = 0L
            connections.values.toList().also { connections.clear() }
        }
        activeConnections.forEach { it.closeFromHost(error) }
        try { pipe.close() } finally {
            receiveFrames.close()
            activeConnections.forEach(Iap2UsbMuxTcpConnection::finishClose)
        }
    }

    private fun allocateSourcePortLocked(): Int =
        NativeUsbMux.sourcePort(handle, connections.keys.toIntArray()).also {
            if (it == 0) throw IphoneUsbException.DeviceUnavailable("USBMUX has no free TCP source ports")
        }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("USBMUX host is closed")
    }

    companion object {
        const val LOCKDOWN_PORT = 62078
        private const val LOGGED_RX_FRAMES = 50

        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val TCP_SYN_FIN_RST = 0x07
        private const val HANDSHAKE_TIMEOUT_MILLIS = 60_000L
        private const val CONNECT_TIMEOUT_MILLIS = 5_000L
        private const val WRITE_TIMEOUT_MILLIS = 2_000
        private const val CLOSE_JOIN_MARGIN_MILLIS = 100L

        /** Performs the USBMUX v2 handshake and starts the framed reader. */
        fun open(
            pipe: Iap2UsbSession,
            readTimeoutMillis: Long = 1_000,
            onDiagnostic: (String) -> Unit = {},
        ): Iap2UsbMuxHost {
            require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
            return Iap2UsbMuxHost(pipe, readTimeoutMillis, onDiagnostic).also {
                try {
                    it.begin()
                } catch (error: Throwable) {
                    it.close()
                    throw error
                }
            }
        }
    }
}

/** Blocking platform waits and USB effects around one native TCP owner. */
class Iap2UsbMuxTcpConnection internal constructor(
    private val host: Iap2UsbMuxHost,
    internal val sourcePort: Int,
    private val destinationPort: Int,
) : BlockingDuplexByteStream {
    private val stateLock = Object()
    private val writeLock = Any()
    // The exception object is a platform I/O result; TCP flags/counters/queue remain native.
    private var failure: IphoneUsbException? = null
    private var retired = ByteArray(0)
    private var handle = NativeUsbMux.tcpCreate(sourcePort, destinationPort)

    override fun send(data: ByteArray) {
        synchronized(stateLock) { checkConnectedLocked() }
        var offset = 0
        while (offset < data.size) {
            synchronized(writeLock) {
                val prepared = synchronized(stateLock) {
                    checkConnectedLocked()
                    NativeUsbMux.tcpPrepare(handle, data, offset)
                }
                val fields = prepared[0] as IntArray
                val count = fields[0]
                host.sendSegment(prepared[1] as ByteArray, fields)
                synchronized(stateLock) { NativeUsbMux.tcpSent(handle, count) }
                offset += count
            }
        }
    }

    /** null is timeout; queued bytes still drain after FIN/local/host close, then EOF. */
    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        synchronized(stateLock) {
            while (true) {
                failure?.let { throw it }
                NativeUsbMux.tcpRecv(handle, retired, maxBytes)?.let { return it }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return null
                try {
                    stateLock.wait(remaining / NANOS_PER_MILLISECOND, (remaining % NANOS_PER_MILLISECOND).toInt())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IphoneUsbException.DeviceUnavailable("Interrupted while waiting for USBMUX TCP data")
                }
            }
        }
    }

    override fun close() = terminate(abort = false)
    internal fun abort() = terminate(abort = true)
    private fun terminate(abort: Boolean) {
        val plan = synchronized(stateLock) {
            if (handle == 0L) return
            NativeUsbMux.tcpClosePlan(handle, abort).also { stateLock.notifyAll() }
        }
        try {
            if (plan > 0) try { sendControl(plan) } catch (_: IphoneUsbException) {
                // Closed pipe cannot make this terminal connection live again.
            }
        } finally {
            try { finishClose() } finally { host.removeConnection(this) }
        }
    }

    internal fun beginConnect() = sendControl(1)
    internal fun awaitConnected(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        synchronized(stateLock) {
            while (true) {
                failure?.let { throw it }
                val status = NativeUsbMux.tcpStatus(handle, retired)
                if (status and 2 != 0) return false
                if (status and 1 != 0) return true
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                try {
                    stateLock.wait(remaining / NANOS_PER_MILLISECOND, (remaining % NANOS_PER_MILLISECOND).toInt())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IphoneUsbException.DeviceUnavailable("Interrupted while connecting USBMUX TCP")
                }
            }
        }
    }

    internal fun closeFromHost(error: IphoneUsbException? = null) = synchronized(stateLock) {
        if (handle != 0L && NativeUsbMux.tcpHostClose(handle, error != null)) {
            failure = error ?: failure
            stateLock.notifyAll()
        }
    }

    /** Host closes USB before taking this write lock, so blocked bulk writes cannot retain owners. */
    internal fun finishClose() = synchronized(writeLock) {
        synchronized(stateLock) {
            if (handle != 0L) {
                val old = handle
                handle = 0L
                retired = NativeUsbMux.tcpFreeze(old)
            }
        }
    }

    internal fun onPacket(flags: Int, sequence: Int, payload: ByteArray) {
        var action = synchronized(stateLock) {
            NativeUsbMux.tcpPacket(handle, retired, flags, sequence, payload).also {
                if (it and 15 == 4) failure = IphoneUsbException.DeviceUnavailable("USBMUX TCP connection was reset by the peer")
                if (it and 16 != 0) stateLock.notifyAll()
            }
        }
        while (true) {
            if (action and 32 != 0) host.removeConnection(this)
            if (action and 15 !in 1..3) return
            sendControl(0)
            action = synchronized(stateLock) {
                NativeUsbMux.tcpAckSent(handle).also { if (it and 16 != 0) stateLock.notifyAll() }
            }
        }
    }

    private fun sendControl(command: Int) = synchronized(writeLock) {
        val frame = synchronized(stateLock) { NativeUsbMux.tcpControl(handle, retired, command) }
        host.sendSegment(frame)
    }
    private fun checkConnectedLocked() {
        failure?.let { throw it }
        if (NativeUsbMux.tcpStatus(handle, retired) and 3 != 1) {
            throw IphoneUsbException.DeviceUnavailable("USBMUX TCP connection is not open")
        }
    }
    private companion object { const val NANOS_PER_MILLISECOND = 1_000_000L }
}
