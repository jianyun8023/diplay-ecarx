package com.shihab.diplay.airplay

import java.io.Closeable
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receive-only iAP2-over-CarPlay DataStream tunnel (stream type 130).
 *
 * The TCP stream is NetSocketChaCha20Poly1305 framed, then carries APTransportPackage records.
 * iAP2 bodies (messageType "comm") are emitted verbatim for the wired iAP2 relay.
 */
class IapTunnel(
    private val readKey: ByteArray,
    private val expectedPeer: InetAddress,
    bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
) : Closeable {
    interface Listener {
        fun onOpen(remoteAddress: String?) {}
        fun onIap(bytes: ByteArray) {}
        fun onDebug(message: String) {}
        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val protocol = DataStreamProtocol(0)
    private val resources = Any()
    private val bindAddress =
        if (bindAddress is Inet4Address) InetAddress.getByName("0.0.0.0") else bindAddress
    private val servers = mutableListOf<ServerSocket>()
    private val sockets = mutableSetOf<Socket>()
    private val threads = mutableListOf<Thread>()
    private val peerConnected = CountDownLatch(1)
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = bindAny()
        val installed = synchronized(resources) { if (closed.get()) false else { servers += bound; true } }
        if (!installed) { safeClose(bound); error("IapTunnel is closed") }
        listener.onDebug("AirPlay iAP tunnel listener bound=${bound.localSocketAddress}")
        val secondaryAddress = if (bindAddress is java.net.Inet6Address) {
            InetAddress.getByName("0.0.0.0")
        } else {
            InetAddress.getByName("::")
        }
        val secondary = ServerSocket()
        runCatching {
            secondary.apply {
                reuseAddress = true
                bind(InetSocketAddress(secondaryAddress, bound.localPort))
            }
        }.onSuccess { secondary ->
            val installed = synchronized(resources) { if (closed.get()) false else { servers += secondary; true } }
            if (!installed) { safeClose(secondary); return@onSuccess }
            listener.onDebug(
                "AirPlay iAP tunnel secondary listener bound=" +
                    "${secondary.localSocketAddress}",
            )
        }.onFailure { error ->
            safeClose(secondary)
            listener.onDebug(
                "AirPlay iAP tunnel secondary listener failed address=" +
                    "$secondaryAddress port=${bound.localPort}: ${error.message}",
            )
        }
        synchronized(resources) { servers.toList() }.forEach { server ->
            val thread = Thread({ accept(server) }, "airplay-iap-tunnel").apply { isDaemon = true }
            val start = synchronized(resources) { if (closed.get()) false else { threads += thread; true } }
            if (start) thread.start()
        }
        return bound.localPort
    }

    private fun bindAny(): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, 0))
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val owned = synchronized(resources) {
            Triple(sockets.toList(), servers.toList(), threads.toList()).also {
                sockets.clear(); servers.clear(); threads.clear()
            }
        }
        owned.first.forEach(::safeClose)
        owned.second.forEach(::safeClose)
        owned.third.forEach(Thread::interrupt)
        peerConnected.countDown()
        protocol.close()
    }

    /** Waits until the iPhone has connected to the advertised dataPort. */
    fun awaitPeerConnection(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            peerConnected.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun accept(bound: ServerSocket) {
        listener.onDebug(
            "AirPlay iAP tunnel accepting local=${bound.localSocketAddress}",
        )
        while (!closed.get()) {
            val accepted = try {
                bound.accept()
            } catch (error: Exception) {
                if (!closed.get()) listener.onClosed(error)
                return
            }
            if (closed.get()) {
                safeClose(accepted)
                return
            }
            if (accepted.inetAddress != expectedPeer) {
                safeClose(accepted)
                listener.onDebug("AirPlay iAP tunnel rejected sender outside the control session")
                continue
            }
            accepted.setSoLinger(true, 0)
            val installed = synchronized(resources) { if (closed.get()) false else { sockets += accepted; true } }
            if (!installed) { safeClose(accepted); return }
            var connection = 0L
            try {
                connection = protocol.begin()
                if (connection == 0L) return
                peerConnected.countDown()
                listener.onOpen(accepted.remoteSocketAddress?.toString())
                run(accepted, connection)
            } finally {
                protocol.end(connection)
                synchronized(resources) { sockets.remove(accepted) }
                safeClose(accepted)
            }
        }
    }

    private fun run(sock: Socket, connection: Long) {
        var failure: Throwable? = null
        var announcedData = false
        try {
            val input = sock.getInputStream()
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (!closed.get()) {
                val read = input.read(buffer)
                if (read < 0) {
                    listener.onDebug("AirPlay iAP tunnel peer EOF")
                    break
                }
                if (!announcedData) {
                    announcedData = true
                    listener.onDebug("AirPlay iAP tunnel received data")
                }
                protocol.append(connection, buffer.copyOf(read))
                while (true) {
                    val event = protocol.next(connection, readKey) ?: break
                    val body = event[1] as ByteArray
                    listener.onDebug("AirPlay iAP tunnel package type=comm body=${body.size}")
                    listener.onIap(body)
                }
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            safeClose(sock)
            if (!closed.get() && failure != null) listener.onClosed(failure)
        }
    }

    private companion object { const val READ_CHUNK_BYTES = 16 * 1024 }
}
