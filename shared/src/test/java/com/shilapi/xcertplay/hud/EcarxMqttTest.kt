package com.shilapi.xcertplay.hud

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Byte-level coverage for the shared MQTT framing (`EcarxMqtt`); no sockets, no Android. */
class EcarxMqttTest {
    @Test fun subscribePacketEncodesQosOneForEveryTopic() {
        val packet = EcarxMqtt.subscribePacket(7, listOf("a/b", "c"))
        assertEquals(0x82, packet[0].toInt() and 0xFF)
        val body = readRemainingBody(packet)
        var index = 0
        assertEquals(7, ((body[index].toInt() and 0xFF) shl 8) or (body[index + 1].toInt() and 0xFF))
        index += 2
        val (first, firstNext) = readMqttString(body, index)
        assertEquals("a/b", first)
        assertEquals(1, body[firstNext].toInt())
        index = firstNext + 1
        val (second, secondNext) = readMqttString(body, index)
        assertEquals("c", second)
        assertEquals(1, body[secondNext].toInt())
    }

    @Test fun parsePublishReadsQosOnePayloadAndSkipsPacketId() {
        val payload = "{\"distance\":\"350 m\",\"address\":\"滨海路\"}"
        val parsed = EcarxMqtt.parsePublish(
            0x32,
            publishBody("ihu/info/navigation/turn-by-turn/next-turn", 42, payload),
        )
        assertEquals("ihu/info/navigation/turn-by-turn/next-turn" to payload, parsed)
    }

    @Test fun parsePublishReadsQosZeroPayload() {
        val payload = "{\"distance\":\"1.2 km\"}"
        val body = ByteArrayOutputStream().also { out ->
            mqttString(out, "ihu/events/navigation/distance")
            out.write(payload.toByteArray(Charsets.UTF_8))
        }.toByteArray()
        assertEquals("ihu/events/navigation/distance" to payload, EcarxMqtt.parsePublish(0x30, body))
    }

    @Test fun parsePublishRejectsNonPublishAndTruncatedBody() {
        assertNull(EcarxMqtt.parsePublish(0x40, byteArrayOf(0, 1)))
        assertNull(EcarxMqtt.parsePublish(0x30, byteArrayOf(0, 9, 1)))
    }

    @Test fun jsonEscapeQuotesBackslashAndControlCharacters() {
        assertEquals("a\\\"b\\\\c", EcarxMqtt.jsonEscape("a\"b\\c"))
        assertEquals("a b", EcarxMqtt.jsonEscape("a\nb"))
    }

    private fun publishBody(topic: String, id: Int, payload: String): ByteArray {
        val out = ByteArrayOutputStream()
        mqttString(out, topic)
        out.write((id ushr 8) and 0xFF)
        out.write(id and 0xFF)
        out.write(payload.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private fun readRemainingBody(packet: ByteArray): ByteArray {
        var index = 1
        var length = 0
        var multiplier = 1
        while (true) {
            val digit = packet[index++].toInt() and 0xFF
            length += (digit and 0x7F) * multiplier
            if (digit and 0x80 == 0) break
            multiplier *= 128
        }
        return packet.copyOfRange(index, index + length)
    }

    private fun readMqttString(body: ByteArray, offset: Int): Pair<String, Int> {
        val length = ((body[offset].toInt() and 0xFF) shl 8) or (body[offset + 1].toInt() and 0xFF)
        val value = String(body, offset + 2, length, Charsets.UTF_8)
        return value to (offset + 2 + length)
    }

    private fun mqttString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.write((bytes.size ushr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes, 0, bytes.size)
    }
}
