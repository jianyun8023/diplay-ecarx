package com.shilapi.xcertplay.hud

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure coverage for the DIM distance guard's payloads and MQTT framing. The guard must speak byte
 * for byte the language `se.cevt.dimsupportservice.NavigationHandler` speaks, so these strings are
 * the contract with the cluster; nothing here opens a socket.
 */
class EcarxProjectionChannelTest {
    @Test fun convertDistanceMatchesTheServiceJsonHelper() {
        assertEquals("0 m", EcarxProjectionChannel.convertDistance(0))
        assertEquals("350 m", EcarxProjectionChannel.convertDistance(350))
        assertEquals("999 m", EcarxProjectionChannel.convertDistance(999))
        assertEquals("1 km", EcarxProjectionChannel.convertDistance(1000))
        assertEquals("1.2 km", EcarxProjectionChannel.convertDistance(1200))
        assertEquals("2.3 km", EcarxProjectionChannel.convertDistance(2345))
        assertEquals("9.9 km", EcarxProjectionChannel.convertDistance(9876))
        assertEquals("10 km", EcarxProjectionChannel.convertDistance(10000))
        assertEquals("12 km", EcarxProjectionChannel.convertDistance(12345))
    }

    @Test fun nextTurnPayloadUsesDistanceThenAddress() {
        assertEquals(
            "{\"distance\":\"350 m\",\"address\":\"滨海路\"}",
            EcarxProjectionChannel.turnByTurnPayload(350, "滨海路"),
        )
    }

    @Test fun nextTurnPayloadClampsAddressTo32CharactersAndEscapesQuotes() {
        val road = "a".repeat(40)
        assertEquals("{\"distance\":\"1.2 km\",\"address\":\"${"a".repeat(32)}\"}",
            EcarxProjectionChannel.turnByTurnPayload(1200, road))
        assertEquals("{\"distance\":\"5 m\",\"address\":\"a\\\"b\"}",
            EcarxProjectionChannel.turnByTurnPayload(5, "a\"b"))
    }

    @Test fun distancePayloadIsJustTheFormattedDistance() {
        assertEquals("{\"distance\":\"9.9 km\"}", EcarxProjectionChannel.distancePayload(9876))
    }

    @Test fun subscribePacketEncodesQosOneForEveryTopic() {
        val packet = EcarxProjectionChannel.subscribePacket(7, "a/b", "c")
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
        val packetBody = publishBody("ihu/info/navigation/turn-by-turn/next-turn", 42, payload)
        val parsed = EcarxProjectionChannel.parsePublish(0x32, packetBody)
        assertEquals("ihu/info/navigation/turn-by-turn/next-turn" to payload, parsed)
    }

    @Test fun parsePublishReadsQosZeroPayload() {
        val payload = "{\"distance\":\"1.2 km\"}"
        val body = ByteArrayOutputStream().also { out ->
            mqttString(out, "ihu/events/navigation/distance")
            out.write(payload.toByteArray(Charsets.UTF_8))
        }.toByteArray()
        assertEquals("ihu/events/navigation/distance" to payload,
            EcarxProjectionChannel.parsePublish(0x30, body))
    }

    @Test fun parsePublishRejectsNonPublishAndTruncatedBody() {
        assertNull(EcarxProjectionChannel.parsePublish(0x40, byteArrayOf(0, 1)))
        assertNull(EcarxProjectionChannel.parsePublish(0x30, byteArrayOf(0, 9, 1)))
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
