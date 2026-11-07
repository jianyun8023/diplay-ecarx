package com.shilapi.xcertplay.hud

import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EcarxNavigationMqttObserverTest {
    @Test fun treatsRefreshPairAsOneActiveStateWithoutHidingRouteEnd() {
        val topic = "ihu/events/navigation/ongoing"
        val ready = "naviEvent=firstFrameReady destinationPresent=false"
        val active = "naviEvent=ongoingNavi destinationPresent=false"
        val off = "naviEvent=naviAppOff destinationPresent=false"
        assertEquals(mqttLogSummaryKey(topic, ready), mqttLogSummaryKey(topic, active))
        assertNotEquals(mqttLogSummaryKey(topic, active), mqttLogSummaryKey(topic, off))
        assertNotEquals(mqttLogSummaryKey(topic, active),
            mqttLogSummaryKey(topic, "naviEvent=ongoingNavi destinationPresent=true"))
    }

    @Test fun subscribesReadOnlyAndDistinguishesRetainedFromLiveWithoutLoggingAddress() {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { broker ->
            broker.soTimeout = 5000
            val lines = LinkedBlockingQueue<String>()
            val observer = EcarxNavigationMqttObserver({ lines.add(it) }, broker.localPort)
            observer.start()
            try {
                broker.accept().use { client ->
                    client.soTimeout = 5000
                    val input = client.getInputStream()
                    val out = client.getOutputStream()
                    assertEquals(0x10, EcarxMqtt.readPacket(input)!!.first)
                    out.write(byteArrayOf(0x20, 2, 0, 0))
                    val (header, body) = EcarxMqtt.readPacket(input)!!
                    assertEquals(0x82, header)
                    val subscription = java.io.DataInputStream(body.inputStream())
                    assertEquals(1, subscription.readUnsignedShort())
                    val topics = mutableListOf<String>()
                    while (subscription.available() > 0) {
                        topics.add(subscription.readUTF())
                        assertEquals(0, subscription.readUnsignedByte())
                    }
                    assertTrue("dim/actions/navigation/map" in topics)
                    assertTrue(topics.containsAll(listOf("ihu/info/phoneprojection", "ihu/heartbeat/navigation",
                        "ihu/events/navigation/distance", "ihu/events/navigation/ETA")))
                    assertFalse(topics.any { '#' in it || '+' in it })
                    out.write(EcarxMqtt.frame(0x90, byteArrayOf(0, 1) + ByteArray(topics.size)))
                    assertTrue(lines.poll(5, TimeUnit.SECONDS).startsWith("mqtt subscribed="))
                    for (retained in listOf(true, false)) {
                        val message = ByteArrayOutputStream()
                        EcarxMqtt.mqttString(message, "ihu/events/navigation/ongoing")
                        message.write("""{"naviEvent":"ongoingNavi","destination":"Private Place"}""".toByteArray())
                        out.write(EcarxMqtt.frame(if (retained) 0x31 else 0x30, message.toByteArray()))
                        val line = lines.poll(5, TimeUnit.SECONDS)
                        assertTrue(line.contains("retained=$retained"))
                        assertTrue(line.endsWith("naviEvent=ongoingNavi destinationPresent=true"))
                        assertFalse(line.contains("Private"))
                    }
                    observer.stop()
                    assertEquals(-1, input.read())
                }
            } finally { observer.stop() }
        }
    }

    @Test fun boundsRepeatedMessagesKeepsStateChangesAndEnablesDetailsWithDebug() {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { broker ->
            broker.soTimeout = 5000
            val lines = LinkedBlockingQueue<String>()
            val debug = AtomicBoolean(false)
            val clock = AtomicLong(1_000_000_000)
            val observer = EcarxNavigationMqttObserver({ lines.add(it) }, broker.localPort, debug::get, clock::get)
            observer.start()
            try {
                broker.accept().use { client ->
                    client.soTimeout = 5000
                    val input = client.getInputStream()
                    val output = client.getOutputStream()
                    EcarxMqtt.readPacket(input)
                    output.write(byteArrayOf(0x20, 2, 0, 0))
                    val subscription = EcarxMqtt.readPacket(input)!!.second
                    val topics = java.io.DataInputStream(subscription.inputStream())
                    topics.readUnsignedShort()
                    var topicCount = 0
                    while (topics.available() > 0) { topics.readUTF(); topics.readUnsignedByte(); topicCount++ }
                    output.write(EcarxMqtt.frame(0x90, byteArrayOf(0, 1) + ByteArray(topicCount)))
                    assertTrue(lines.poll(5, TimeUnit.SECONDS).startsWith("mqtt subscribed="))
                    assertTrue(observer.diagnosticReport().contains("ihu/heartbeat/navigation count=0"))

                    fun send(topic: String, payload: String) {
                        val body = ByteArrayOutputStream()
                        EcarxMqtt.mqttString(body, topic)
                        body.write(payload.toByteArray())
                        output.write(EcarxMqtt.frame(0x30, body.toByteArray()))
                    }
                    fun nextTurn(distance: String = "90 m") = send("ihu/info/navigation/turn-by-turn/next-turn",
                        """{"distance":"$distance","address":"Private Road","imageData":"Private Image"}""")
                    // A changed control event is also a barrier: earlier packets have been processed.
                    fun ongoing(event: String) = send("ihu/events/navigation/ongoing", """{"naviEvent":"$event"}""")

                    repeat(1000) { nextTurn() }
                    ongoing("ongoingNavi")
                    val first = lines.poll(5, TimeUnit.SECONDS)
                    assertTrue(first.contains("count=1 "))
                    assertFalse(first.contains(" distance="))
                    assertTrue(lines.poll(5, TimeUnit.SECONDS).contains("naviEvent=ongoingNavi"))
                    assertTrue(lines.isEmpty())
                    assertTrue(observer.diagnosticReport().contains("next-turn count=1000"))

                    // The OEM alternates these two active-state events every second.
                    // A different-topic report is a processing barrier for the assertions.
                    repeat(15) {
                        ongoing("firstFrameReady")
                        ongoing("ongoingNavi")
                    }
                    send("ihu/events/navigation/distance", """{"distance":"50 m"}""")
                    assertTrue(lines.poll(5, TimeUnit.SECONDS).contains("topic=ihu/events/navigation/distance"))
                    assertTrue(lines.isEmpty())
                    assertTrue(observer.diagnosticReport().contains("ihu/events/navigation/ongoing count=31"))

                    clock.addAndGet(60_000_000_000)
                    nextTurn()
                    val summary = lines.poll(5, TimeUnit.SECONDS)
                    assertTrue(summary.contains("count=1001 suppressed=999"))
                    assertFalse(summary.contains(" distance="))

                    debug.set(true)
                    clock.addAndGet(1_000_000_000)
                    repeat(1000) { nextTurn() }
                    ongoing("idle")
                    assertTrue(lines.poll(5, TimeUnit.SECONDS).endsWith(" distance=90 m"))
                    assertTrue(lines.poll(5, TimeUnit.SECONDS).contains("naviEvent=idle"))
                    assertTrue(lines.isEmpty())

                    clock.addAndGet(1_000_000_000)
                    nextTurn("Private Destination")
                    assertTrue(lines.poll(5, TimeUnit.SECONDS).endsWith(" distance=unknown"))
                    debug.set(false)
                    clock.addAndGet(1_000_000_000)
                    nextTurn()
                    ongoing("ongoingNavi")
                    assertTrue(lines.poll(5, TimeUnit.SECONDS).contains("naviEvent=ongoingNavi"))
                    assertTrue(lines.isEmpty())

                    send("ihu/info/phoneprojection", """{"statusType":"carPlayNavigation"}""")
                    assertTrue(lines.poll(5, TimeUnit.SECONDS).contains("statusType=carPlayNavigation"))
                    send("ihu/info/phoneprojection", """{"statusType":"noProjection"}""")
                    assertTrue(lines.poll(5, TimeUnit.SECONDS).contains("statusType=noProjection"))
                    send("ihu/events/navigation/ETA", """{"destinationTime":"Private ETA"}""")
                    assertTrue(lines.poll(5, TimeUnit.SECONDS).endsWith("etaPresent=true"))
                    send("ihu/heartbeat/navigation", """{"timestamp":1234,"periodicity":2}""")
                    assertTrue(lines.poll(5, TimeUnit.SECONDS).contains("periodicity=2"))
                    clock.addAndGet(5_000_000_000)
                    val report = observer.diagnosticReport()
                    assertTrue(report.contains("ihu/heartbeat/navigation count=1 ageMs=5000"))
                    assertFalse(report.contains("Private"))
                }
            } finally { observer.stop() }
        }
    }
}
