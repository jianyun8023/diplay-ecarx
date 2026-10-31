package com.shilapi.xcertplay.hud

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The MQTT 3.1.1 bytes the ECARX DIM stack speaks, in one place.
 *
 * `se.cevt.dimsupportservice` publishes its `ihu/…` state to a loopback broker, so anything DiPlay
 * wants to observe or re-assert on that bus needs the same framing. This object owns only the
 * protocol (connect/publish/subscribe packets, packet reading, PUBLISH decoding, JSON escaping) and
 * the short connect/publish/subscribe round-trips; socket *lifetime* stays with the caller, because
 * [EcarxProjectionChannel] keeps one long-lived publisher while [EcarxDimDistanceGuard] keeps a
 * blocking subscriber and a separate publisher.
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
        val code = ack?.second?.lastOrNull()?.toInt() ?: -1
        if (ack == null || (ack.first and 0xF0) != 0x20 || code != 0) {
            runCatching { socket.close() }
            null
        } else {
            socket
        }
    }.getOrElse { null }

    /** QoS-1 publish and wait for the matching PUBACK. Returns `"ok"` or a short failure reason. */
    fun publish(socket: Socket, packetId: Int, topic: String, payload: String): String = runCatching {
        socket.getOutputStream().apply { write(publishPacket(packetId, topic, payload)); flush() }
        val ack = readPacket(socket.getInputStream())
        val acked = if (ack != null && (ack.first and 0xF0) == 0x40 && ack.second.size >= 2) {
            ((ack.second[0].toInt() and 0xFF) shl 8) or (ack.second[1].toInt() and 0xFF)
        } else {
            -1
        }
        when {
            acked == packetId -> "ok"
            ack == null -> "no puback"
            else -> "puback=$acked wanted=$packetId"
        }
    }.getOrElse { "write failed ${reason(it)}" }

    /** Subscribe (QoS 1) to [topics] and wait for the SUBACK. */
    fun subscribe(socket: Socket, packetId: Int, topics: List<String>): Boolean = runCatching {
        socket.getOutputStream().apply { write(subscribePacket(packetId, topics)); flush() }
        val ack = readPacket(socket.getInputStream())
        ack != null && (ack.first and 0xF0) == 0x90
    }.getOrElse { false }

    fun connectPacket(clientId: String): ByteArray {
        val body = ByteArrayOutputStream()
        mqttString(body, "MQTT")
        body.write(4) // protocol level 4 == MQTT 3.1.1
        body.write(0x02) // clean session; no will, no user name, no password
        body.write(0)
        body.write(0) // keepalive 0: the broker never times this connection out, so no PINGREQ
        mqttString(body, clientId)
        return frame(0x10, body.toByteArray())
    }

    /** PUBLISH with QoS 1, so the broker answers with a PUBACK we can see. */
    fun publishPacket(id: Int, topic: String, payload: String): ByteArray {
        val body = ByteArrayOutputStream()
        mqttString(body, topic)
        body.write((id ushr 8) and 0xFF)
        body.write(id and 0xFF)
        val bytes = payload.toByteArray(Charsets.UTF_8)
        body.write(bytes, 0, bytes.size)
        return frame(0x32, body.toByteArray())
    }

    /** SUBSCRIBE with QoS 1 for each topic. */
    fun subscribePacket(id: Int, topics: List<String>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write((id ushr 8) and 0xFF)
        body.write(id and 0xFF)
        for (topic in topics) {
            mqttString(body, topic)
            body.write(1) // requested QoS 1
        }
        return frame(0x82, body.toByteArray())
    }

    private fun frame(type: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(type)
        var remaining = body.size
        do {
            var digit = remaining % 128
            remaining /= 128
            if (remaining > 0) digit = digit or 0x80
            out.write(digit)
        } while (remaining > 0)
        out.write(body, 0, body.size)
        return out.toByteArray()
    }

    private fun mqttString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.write((bytes.size ushr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes, 0, bytes.size)
    }

    /** Reads one control packet: its first byte (type in the high nibble) and its body. */
    fun readPacket(input: InputStream): Pair<Int, ByteArray>? {
        val first = input.read()
        if (first < 0) return null
        var multiplier = 1
        var length = 0
        while (true) {
            val digit = input.read()
            if (digit < 0) return null
            length += (digit and 0x7F) * multiplier
            if (digit and 0x80 == 0) break
            multiplier *= 128
            if (multiplier > 128 * 128 * 128) return null
        }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) return null
            read += count
        }
        return first to body
    }

    /** Extracts `(topic, payload)` from a PUBLISH packet, or null when `first` is not a PUBLISH. */
    fun parsePublish(first: Int, body: ByteArray): Pair<String, String>? {
        if ((first and 0xF0) != 0x30 || body.size < 2) return null
        val qos = (first ushr 1) and 0x03
        var index = 0
        val topicLength = ((body[index].toInt() and 0xFF) shl 8) or (body[index + 1].toInt() and 0xFF)
        index += 2
        if (topicLength < 0 || index + topicLength > body.size) return null
        val topic = String(body, index, topicLength, Charsets.UTF_8)
        index += topicLength
        if (qos > 0) {
            if (index + 2 > body.size) return null
            index += 2
        }
        return topic to String(body, index, body.size - index, Charsets.UTF_8)
    }

    /** Minimal JSON string escaping for MQTT payloads built by hand. */
    fun jsonEscape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n', '\r' -> out.append(' ')
                else -> if (ch < ' ') out.append(' ') else out.append(ch)
            }
        }
        return out.toString()
    }

    private fun reason(error: Throwable): String = "${error.javaClass.simpleName}: ${error.message}"
}
