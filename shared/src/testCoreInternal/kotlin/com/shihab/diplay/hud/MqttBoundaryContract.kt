package com.shihab.diplay.hud

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.json.JSONObject
import org.junit.Assert.*

internal object MqttBoundaryContract {
    fun wireLimitsAndExceptions() {
        assertArrayEquals(byteArrayOf(0x10, 14, 0, 4, 77, 81, 84, 84, 4, 2, 0, 0, 0, 2, 105, 100), EcarxMqtt.connectPacket("id"))
        assertArrayEquals(byteArrayOf(0x32, 7, 0, 1, 97, 0x12, 0x34, 123, 125), EcarxMqtt.publishPacket(0x1234, "a", "{}"))
        val frame = EcarxMqtt.frame(0x30, ByteArray(16384) { it.toByte() })
        val input = object : ByteArrayInputStream(frame) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 3))
        }
        assertArrayEquals(ByteArray(16384) { it.toByte() }, EcarxMqtt.readPacket(input, 16384)!!.second)
        assertNull(EcarxMqtt.readPacket(frame.inputStream(), 16383))
        assertNull(EcarxMqtt.readPacket(byteArrayOf(0x30, 0x80.toByte(), 0x80.toByte(), 0x80.toByte()).inputStream()))
        assertNull(EcarxMqtt.readPacket(byteArrayOf(0x30, 0).inputStream(), -1))
        val failure = IOException("same exception")
        assertSame(failure, assertThrows(IOException::class.java) {
            EcarxMqtt.readPacket(object : InputStream() { override fun read(): Int = throw failure })
        })
        assertEquals(0, NativeEcarxMqtt.ack(0x2f, byteArrayOf(8, 0), -1))
        assertFalse(NativeEcarxMqtt.observerConnack(0x2f, byteArrayOf(8, 0)))
        assertEquals(0x1234, NativeEcarxMqtt.ack(0x4f, byteArrayOf(0x12, 0x34, 9), 0x1234))
        assertEquals("ok", NativeEcarxMqtt.publishReply(0x4f, byteArrayOf(0x12, 0x34, 9), 0x1234))
        assertEquals("puback=4660 wanted=3", NativeEcarxMqtt.publishReply(0x40, byteArrayOf(0x12, 0x34), 3))
        assertEquals("no puback", NativeEcarxMqtt.publishReply(-1, null, 3))
        assertEquals("puback=-1 wanted=3", NativeEcarxMqtt.publishReply(0x20, byteArrayOf(0, 0), 3))
        assertEquals("x\uD800 ", EcarxMqtt.jsonEscape("x\uD800\n"))
    }

    fun publicationAndObservationState() {
        for ((replies, failureAt, expected) in listOf(
            Triple(listOf("ok"), 0, listOf("connect", "publish")),
            Triple(listOf("bad", "ok"), 0, listOf("connect", "publish", "drop", "connect", "publish")),
            Triple(listOf("bad", "bad"), 0, listOf("connect", "publish", "drop", "connect", "publish", "drop")),
            Triple(emptyList(), 1, listOf("connect")),
            Triple(listOf("bad"), 2, listOf("connect", "publish", "drop", "connect")),
        )) {
            val host = Publisher(replies, failureAt)
            assertEquals(if (failureAt > 0) "connect failed" else replies.last(), NativeEcarxMqtt.publishLoop(host, "topic", "body"))
            assertEquals(expected, host.events)
        }
        val owner = NativeEcarxMqtt.projectionCreate()
        assertEquals(0, NativeEcarxMqtt.projectionUpdate(owner, false))
        assertEquals(2, NativeEcarxMqtt.projectionUpdate(owner, true))
        assertEquals(0, NativeEcarxMqtt.projectionUpdate(owner, false))
        assertNull(NativeEcarxMqtt.projectionPlan(owner))
        NativeEcarxMqtt.projectionUpdate(owner, true)
        assertEquals("carPlayNavigation", NativeEcarxMqtt.projectionPlan(owner))
        assertEquals(2, NativeEcarxMqtt.projectionUpdate(owner, true))
        NativeEcarxMqtt.projectionCommit(owner, "carPlayNavigation")
        assertEquals(0, NativeEcarxMqtt.projectionUpdate(owner, true))
        val first = NativeEcarxMqtt.projectionOngoing(owner, true, "a\"\uD800\n")!!
        assertTrue(first.contains("a\\\"\uD800 "))
        assertNull(NativeEcarxMqtt.projectionOngoing(owner, true, "a\"\uD800\n"))
        val observations = NativeEcarxMqtt.observerCreate()
        val lanes = "ihu/info/navigation/turn-by-turn/lanesupport"
        // optString(null) is the literal "null" on Android; preserve the original lane count.
        val laneJson = JSONObject("{\"lanes\":[{\"icon\":null},{\"icon\":\"\u00a0\"},{\"icon\":\"left\"},null]}")
        assertTrue(NativeEcarxMqtt.observerObserve(observations, lanes, laneJson, false, false, 0, false)!!.endsWith("lanes=2"))
        val ongoing = "ihu/events/navigation/ongoing"
        assertTrue(NativeEcarxMqtt.observerObserve(observations, ongoing,
            JSONObject("{\"naviEvent\":\"ongoingNavi\",\"destination\":null}"), false, true, 1, false)!!.endsWith("destinationPresent=false"))
        assertFalse(NativeEcarxMqtt.observerReport(observations, 9).contains("left"))
        NativeEcarxMqtt.observerClear(observations)
        assertTrue(NativeEcarxMqtt.observerReport(observations, 10).lines().all { it.endsWith("count=0") })
    }

    private class Publisher(val replies: List<String>, val failureAt: Int) {
        val events = mutableListOf<String>()
        var connections = 0
        var published = 0
        @Suppress("unused") private fun mqttConnect(): Boolean { events += "connect"; return ++connections != failureAt }
        @Suppress("unused") private fun mqttConnectReason(): String = "connect failed"
        @Suppress("unused") private fun mqttPublish(topic: String, payload: String): String {
            assertEquals("topic", topic); assertEquals("body", payload)
            events += "publish"; return replies[published++]
        }
        @Suppress("unused") private fun mqttDrop() { events += "drop" }
    }
}
