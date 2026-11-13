package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay timing-port clock sync.
 *
 * The accessory drives a two-way RTCP-style exchange with the phone: PT_REQUEST (210) carries our
 * transmit timestamp and PT_RESPONSE (211) echoes it while adding the phone's receive and
 * transmit stamps. The resulting offset and round-trip time steer a local monotonic clock onto
 * the phone's media-clock domain, which /feedback reports.
 */
class NtpClock : Closeable {
    private val running = AtomicBoolean(false)
    private val socketLock = Any()
    private val clockLock = Any()

    private var socket: DatagramSocket? = null
    private var receiver: Thread? = null
    private var sender: Thread? = null
    // Written by the session thread, read by the receive thread.
    @Volatile private var peer: InetSocketAddress? = null

    private val timing = NtpTiming(System.nanoTime(), System.currentTimeMillis())

    fun listen(): Int {
        check(!running.getAndSet(true)) { "NtpClock is already running" }
        val bound = DatagramSocket(null)
        bound.reuseAddress = true
        bound.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        synchronized(socketLock) { socket = bound }
        receiver = Thread(::runReceiver, "airplay-ntp-rx").apply { isDaemon = true; start() }
        return bound.localPort
    }

    fun start(peerAddress: InetAddress, port: Int) {
        require(port > 0) { "timing port must be positive" }
        peer = InetSocketAddress(peerAddress, port)
        sender = Thread(::runSender, "airplay-ntp-tx").apply { isDaemon = true; start() }
    }

    /** Now in the phone's synchronized clock domain, as an unsigned NTP64 value. */
    fun syncedNtp(): BigInteger = synchronized(clockLock) {
        timing.synced(System.nanoTime())
    }

    override fun close() {
        running.set(false)
        synchronized(socketLock) {
            socket?.close()
            socket = null
        }
        sender?.interrupt()
        receiver?.interrupt()
    }

    private fun runSender() {
        while (running.get()) {
            try {
                sendRequest()
                Thread.sleep(REQUEST_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                try {
                    Thread.sleep(REQUEST_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun sendRequest() {
        val destination = peer ?: return
        val packet = synchronized(clockLock) { timing.request(System.nanoTime()) }
        currentSocket()?.send(DatagramPacket(packet, packet.size, destination))
    }

    private fun runReceiver() {
        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        while (running.get()) {
            val sock = currentSocket() ?: return
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                sock.receive(packet)
            } catch (_: Exception) {
                if (running.get()) continue else return
            }
            val message = packet.data.copyOf(packet.length)
            val address = packet.address
            if (address != null) {
                handleMessage(message, InetSocketAddress(address, packet.port))
            }
        }
    }

    private fun handleMessage(message: ByteArray, source: InetSocketAddress) {
        when (timing.action(message, source.address == peer?.address)) {
            1 -> {
                val response = synchronized(clockLock) {
                    timing.reply(message, System.nanoTime(), System.nanoTime())
                }
                currentSocket()?.send(DatagramPacket(response, response.size, source))
            }
            2 -> synchronized(clockLock) { timing.sample(message, System.nanoTime()) }
        }
    }

    private fun currentSocket(): DatagramSocket? = synchronized(socketLock) { socket }

    private companion object {
        const val REQUEST_INTERVAL_MS = 1_000L
        const val RECEIVE_BUFFER_BYTES = 2_048
    }
}
