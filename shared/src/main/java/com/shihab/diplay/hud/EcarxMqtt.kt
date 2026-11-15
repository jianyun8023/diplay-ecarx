package com.shihab.diplay.hud

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The MQTT 3.1.1 bytes the ECARX DIM stack speaks, in one place.
 *
 * `se.cevt.dimsupportservice` publishes its `ihu/…` state to a loopback broker, so anything DiPlay
 * publishes on that bus needs the same framing. Rust owns framing, acknowledgements and JSON escaping; this host executes socket operations; [EcarxProjectionChannel] owns the publisher's lifetime.
 */
internal object EcarxMqtt {
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 1883

    /** Connect and complete the CONNACK handshake, or return null. */
    fun connect(
        clientId: String,
        host: String = DEFAULT_HOST,
        port: Int = DEFAULT_PORT,
        connectTimeoutMs: Int = 1_500,
        readTimeoutMs: Int = 1_500,
    ): Socket? = runCatching {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket.soTimeout = readTimeoutMs
        socket.getOutputStream().apply { write(connectPacket(clientId)); flush() }
        val ack = readPacket(socket.getInputStream())
        if (NativeEcarxMqtt.ack(ack?.first ?: -1, ack?.second, -1) != 0) {
            runCatching { socket.close() }
            null
        } else {
            socket
        }
    }.getOrElse { null }

    /** QoS-1 publish and wait for the matching PUBACK. Returns `"ok"` or a short failure reason. */
    fun publish(socket: Socket, packetId: Int, topic: String, payload: String, health: LongArray): String {
        NativeEcarxMqtt.health(health, 4)
        var waitingForAck = false
        val outcome = runCatching {
            socket.getOutputStream().apply { write(publishPacket(packetId, topic, payload)); flush() }
            waitingForAck = true
            val ack = readPacket(socket.getInputStream())
            NativeEcarxMqtt.publishReply(ack?.first ?: -1, ack?.second, packetId)
        }.getOrElse {
            if (waitingForAck && it is SocketTimeoutException) NativeEcarxMqtt.health(health, 7)
            "write failed ${reason(it)}"
        }
        NativeEcarxMqtt.health(health, if (outcome == "ok") 5 else 6)
        return outcome
    }

    fun connectPacket(clientId: String): ByteArray = NativeEcarxMqtt.connectPacket(clientId.toByteArray(Charsets.UTF_8))

    fun publishPacket(id: Int, topic: String, payload: String): ByteArray =
        NativeEcarxMqtt.publishPacket(id, topic.toByteArray(Charsets.UTF_8), payload.toByteArray(Charsets.UTF_8))

    internal fun frame(type: Int, body: ByteArray): ByteArray = NativeEcarxMqtt.frame(type, body)

    internal fun mqttString(out: ByteArrayOutputStream, value: String) {
        out.write(NativeEcarxMqtt.mqttString(value.toByteArray(Charsets.UTF_8)))
    }

    fun readPacket(input: InputStream, maxBytes: Int = Int.MAX_VALUE): Pair<Int, ByteArray>? =
        NativeEcarxMqtt.readPacket(input, maxBytes)?.let { (it[0] as Int) to (it[1] as ByteArray) }

    fun jsonEscape(value: String): String = NativeEcarxMqtt.jsonEscape(value)

    private fun reason(error: Throwable): String = "${error.javaClass.simpleName}: ${error.message}"
}
