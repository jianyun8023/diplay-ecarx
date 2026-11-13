package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The "CarPlayVideo Settings App" data stream (type 130, [VideoInCar.SETTINGS_CHANNEL_UUID]) the iPhone
 * opens once video in car is declared; it tears the session down when this stream is refused. Framing
 * as the iAP tunnel: ChaCha20-Poly1305 frames with a 2-byte little-endian length, carrying packages
 * with a 32-byte header (size, 12-byte type such as "sync"/"rply", 4-byte command, 8-byte sequence
 * number) and a body. Each "sync" is answered with an empty "rply", as AirPlay data streams expect;
 * the bodies are not needed to play.
 */
class VideoSettingsChannel(
    private val readKey: ByteArray,
    private val writeKey: ByteArray,
    private val expectedPeer: InetAddress,
    private val log: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val resources = Any()
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private val protocol = DataStreamProtocol(1)

    fun listen(bindAddress: InetAddress): Int {
        val bound = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, 0))
        }
        val installed = synchronized(resources) { if (closed.get()) false else { server = bound; true } }
        if (!installed) { runCatching { bound.close() }; error("VideoSettingsChannel is closed") }
        Thread({ accept(bound) }, "diplay-video-settings").apply { isDaemon = true }.start()
        return bound.localPort
    }

    private fun accept(bound: ServerSocket) {
        try {
            val accepted = bound.acceptFrom(expectedPeer) { log("video settings channel rejected sender outside the control session") }
            try {
                val installed = synchronized(resources) { if (closed.get()) false else { socket = accepted; true } }
                if (!installed) return
                log("video settings channel connected")
                run(accepted)
            } finally {
                synchronized(resources) { if (socket === accepted) socket = null }
                runCatching { accepted.close() }
            }
        } catch (error: Exception) {
            if (!closed.get()) log("video settings channel ended: ${error.message}")
        } finally {
            close()
        }
    }

    private fun run(sock: Socket) {
        val connection = protocol.begin()
        if (connection == 0L) return
        try {
            val input = sock.getInputStream()
            val output = sock.getOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (!closed.get()) {
                val read = input.read(buffer)
                if (read < 0) return
                protocol.append(connection, buffer.copyOf(read))
                while (true) {
                    val event = protocol.next(connection, readKey, writeKey) ?: break
                    output.write(event[1] as ByteArray)
                    output.flush()
                }
            }
        } finally {
            protocol.end(connection)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val owned = synchronized(resources) { (socket to server).also { socket = null; server = null } }
        runCatching { owned.first?.close() }
        runCatching { owned.second?.close() }
        protocol.close()
    }

}
