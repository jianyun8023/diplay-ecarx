package com.shilapi.xcertplay.hud

import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Wire-level tests with both established-socket probes and real fixed-endpoint connections. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EcarxProjectionChannelTest {
    private lateinit var broker: LocalBroker
    private val reports = CopyOnWriteArrayList<String>()
    private val worker get() = field("worker").get(EcarxProjectionChannel) as ExecutorService
    private fun field(name: String) = EcarxProjectionChannel.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any?) = field(name).set(EcarxProjectionChannel, value)
    private fun drain() {
        worker.submit {}.get(3, TimeUnit.SECONDS)
        broker.failure.get()?.let { throw AssertionError("Local broker rejected a production packet", it) }
    }

    @Before fun setup() {
        worker.submit {}.get(3, TimeUnit.SECONDS)
        (field("socket").get(EcarxProjectionChannel) as? Socket)?.close()
        resetState()
        broker = LocalBroker()
        set("socket", broker.client)
        set("lastConnectReason", "test loopback")
        EcarxProjectionChannel.attachReport { reports += it }
        reports.clear()
    }

    @After fun cleanup() {
        try {
            drain()
        } finally {
            try {
                closeChannelSocket()
            } finally {
                try {
                    broker.close()
                } finally {
                    // A timed-out publish may still be unwinding after its socket was closed.
                    // Do not reset shared state until the production worker has finished.
                    try {
                        worker.submit {}.get(3, TimeUnit.SECONDS)
                    } finally {
                        closeChannelSocket()
                    }
                    set("reportSink", null)
                    resetState()
                }
            }
        }
    }

    private fun closeChannelSocket() {
        try {
            (field("socket").get(EcarxProjectionChannel) as? Socket)?.close()
        } finally {
            set("socket", null)
        }
    }

    private fun resetState() {
        for (name in listOf("navigationWanted", "mediaWanted", "touched", "queued")) set(name, false)
        for (name in listOf("published", "lastOngoing")) set(name, null)
        set("packetId", 0)
    }

    @Test fun realWorkerPublishesNavigationPrioritySurvivingMediaThenOneFinalOff() {
        EcarxProjectionChannel.setMediaProjection(false)
        EcarxProjectionChannel.setNavigationProjection(false)
        drain()
        assertTrue(broker.frames.isEmpty())
        EcarxProjectionChannel.setMediaProjection(true)
        drain()
        EcarxProjectionChannel.setMediaProjection(true)
        drain()
        assertEquals(1, broker.frames.size)
        EcarxProjectionChannel.setNavigationProjection(true)
        drain()
        EcarxProjectionChannel.setMediaProjection(false)
        drain()
        assertEquals(2, broker.frames.size)
        EcarxProjectionChannel.setMediaProjection(true)
        drain()
        EcarxProjectionChannel.setNavigationProjection(false)
        drain()
        EcarxProjectionChannel.setMediaProjection(false)
        drain()
        EcarxProjectionChannel.setNavigationProjection(false)
        EcarxProjectionChannel.setMediaProjection(false)
        drain()
        assertEquals(listOf("carPlayMedia", "carPlayNavigation", "carPlayMedia", "noProjection"),
            broker.frames.map { JSONObject(it.payload).getString("statusType") })
        assertTrue(broker.frames.all { it.topic == "ihu/info/phoneprojection" && it.header == 0x32 })
        assertEquals(listOf(1, 2, 3, 4), broker.frames.map { it.id })
        assertEquals(4, reports.count { it.startsWith("projection ") && it.endsWith("-> ok") })
        assertTrue(EcarxProjectionChannel.state().contains("projection=noProjection"))
    }

    @Test fun queuedStartAndStopCollapseBeforeAnythingTouchesTheCluster() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker.execute { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        try {
            EcarxProjectionChannel.setMediaProjection(true)
            EcarxProjectionChannel.setNavigationProjection(true)
            EcarxProjectionChannel.setNavigationProjection(false)
            EcarxProjectionChannel.setMediaProjection(false)
        } finally { release.countDown() }
        drain()
        assertTrue(broker.frames.isEmpty())
        assertTrue(EcarxProjectionChannel.state().contains("projection=unset"))
        EcarxProjectionChannel.setMediaProjection(true)
        drain()
        assertEquals("carPlayMedia", JSONObject(broker.frames.single().payload).getString("statusType"))
    }

    @Test fun ongoingEventEscapesUtf8AndControlsDeduplicatesAndPreservesTopicOrder() {
        val road = "路名\"\\\n\r\u0001" + "海".repeat(180)
        EcarxProjectionChannel.setMediaProjection(true)
        drain()
        EcarxProjectionChannel.setNavigationOngoing(true, road)
        EcarxProjectionChannel.setNavigationOngoing(true, road)
        EcarxProjectionChannel.setNavigationOngoing(false, "")
        drain()
        assertEquals(listOf("ihu/info/phoneprojection", "ihu/events/navigation/ongoing",
            "ihu/events/navigation/ongoing"), broker.frames.map { it.topic })
        assertEquals(listOf(1, 2, 3), broker.frames.map { it.id })
        val ongoing = JSONObject(broker.frames[1].payload)
        assertEquals("ongoingNavi", ongoing.getString("naviEvent"))
        assertEquals("路名\"\\   " + "海".repeat(180), ongoing.getString("destination"))
        assertEquals("hubsNavi", ongoing.getString("naviIcon"))
        assertEquals("naviNewIcon", ongoing.getString("iconLeft"))
        assertEquals("cancelNaviIcon", ongoing.getString("iconCenter"))
        assertEquals("unmuteIcon", ongoing.getString("iconRight"))
        assertEquals("destinationFlagIcon", ongoing.getString("destinationFlagIcon"))
        assertEquals(7, ongoing.length())
        assertEquals("idle", JSONObject(broker.frames[2].payload).getString("naviEvent"))
        assertEquals("", JSONObject(broker.frames[2].payload).getString("destination"))
        // Ongoing events are independent of the selected projection source.
        assertTrue(EcarxProjectionChannel.state().contains("projection=carPlayMedia"))
    }

    @Test fun packetIdsWrapAtSixteenBitsAndOffDoesNotRepeat() {
        set("packetId", 65_534)
        EcarxProjectionChannel.setMediaProjection(true)
        drain()
        EcarxProjectionChannel.setMediaProjection(false)
        drain()
        EcarxProjectionChannel.setMediaProjection(false)
        drain()
        assertEquals(listOf(65_535, 1), broker.frames.map { it.id })
        assertEquals(listOf("carPlayMedia", "noProjection"),
            broker.frames.map { JSONObject(it.payload).getString("statusType") })
    }

    @Test fun realConnectPublishDisconnectAndReconnectRetryPreserveWireOrder() {
        withFixedBroker(listOf(ConnectionPlan.DROP_SECOND_PUBLISH, ConnectionPlan.ACCEPT)) { live ->
            EcarxProjectionChannel.setMediaProjection(true)
            drain()
            live.assertHealthy()
            assertEquals(listOf("CONNECT:1", "PUBLISH:1:1", "PUBACK:1:1"), live.events.toList())
            assertEquals("carPlayMedia", field("published").get(EcarxProjectionChannel))

            // The first socket receives this frame but closes without PUBACK. The production
            // retry must create another socket, complete CONNECT again, and resend navigation.
            EcarxProjectionChannel.setNavigationProjection(true)
            drain()
            live.assertHealthy()
            assertEquals(listOf("CONNECT:1", "PUBLISH:1:1", "PUBACK:1:1",
                "PUBLISH:1:2", "DROP:1", "CONNECT:2", "PUBLISH:2:3", "PUBACK:2:3"), live.events.toList())
            assertEquals(listOf("diplay-projection-pub", "diplay-projection-pub"), live.clientIds.toList())
            assertEquals(listOf("carPlayMedia", "carPlayNavigation", "carPlayNavigation"),
                live.frames.map { JSONObject(it.payload).getString("statusType") })
            assertEquals(listOf(1, 2, 3), live.frames.map { it.id })
            assertTrue(live.frames.all { it.header == 0x32 && it.topic == "ihu/info/phoneprojection" })
            assertEquals("carPlayNavigation", field("published").get(EcarxProjectionChannel))
            EcarxProjectionChannel.setNavigationProjection(false)
            drain()
            EcarxProjectionChannel.setMediaProjection(false)
            drain()
            live.assertHealthy()
            assertEquals(listOf("carPlayMedia", "noProjection"),
                live.frames.takeLast(2).map { JSONObject(it.payload).getString("statusType") })
            assertEquals(2, live.clientIds.size) // Both final frames reuse the replacement socket.
            assertEquals(listOf(1, 2, 3, 4, 5), live.frames.map { it.id })
        }
    }

    @Test fun wrongPubackExhaustsFreshConnectionRetryWithoutPublishingSuccessAndLaterRecovers() {
        withFixedBroker(listOf(ConnectionPlan.WRONG_PUBACK, ConnectionPlan.WRONG_PUBACK,
            ConnectionPlan.ACCEPT)) { live ->
            EcarxProjectionChannel.setMediaProjection(true)
            drain()
            live.assertHealthy()
            assertEquals(2, live.clientIds.size)
            assertEquals(listOf(1, 2), live.frames.map { it.id })
            assertNull(field("published").get(EcarxProjectionChannel))
            assertEquals(false, field("touched").get(EcarxProjectionChannel))
            assertNull(field("socket").get(EcarxProjectionChannel))
            assertTrue(reports.any { it == "projection carPlayMedia -> puback=3 wanted=2" })
            assertFalse(reports.any { it == "projection carPlayMedia -> ok" })

            // The same wanted value remains dirty after failure; a new request must really
            // reconnect and publish rather than deduplicate against an unacknowledged value.
            EcarxProjectionChannel.setMediaProjection(true)
            drain()
            live.assertHealthy()
            assertEquals(3, live.clientIds.size)
            assertEquals(listOf(1, 2, 3), live.frames.map { it.id })
            assertTrue(live.frames.all { JSONObject(it.payload).getString("statusType") == "carPlayMedia" })
            assertEquals("carPlayMedia", field("published").get(EcarxProjectionChannel))
            assertEquals(true, field("touched").get(EcarxProjectionChannel))
            assertEquals(1, reports.count { it == "projection carPlayMedia -> ok" })
        }
    }

    @Test fun rejectedConnackDoesNotPublishAndNextRequestReestablishesTheRealConnection() {
        withFixedBroker(listOf(ConnectionPlan.REJECT_CONNECT, ConnectionPlan.ACCEPT)) { live ->
            EcarxProjectionChannel.setMediaProjection(true)
            drain()
            live.assertHealthy()
            assertEquals(listOf("CONNECT:1", "CONNACK_REJECT:1"), live.events.toList())
            assertTrue(live.frames.isEmpty())
            assertNull(field("published").get(EcarxProjectionChannel))
            assertNull(field("socket").get(EcarxProjectionChannel))
            assertTrue(EcarxProjectionChannel.state().contains("broker=connack=5"))
            EcarxProjectionChannel.setMediaProjection(true)
            drain()
            live.assertHealthy()
            assertEquals(2, live.clientIds.size)
            assertEquals(1, live.frames.single().id) // Refused CONNECT never consumed a publish id.
            assertEquals("carPlayMedia", field("published").get(EcarxProjectionChannel))
            assertTrue(EcarxProjectionChannel.state().contains("broker=ok"))
        }
    }

    private fun withFixedBroker(plans: List<ConnectionPlan>, test: (FixedEndpointBroker) -> Unit) {
        // Bind before removing the injected socket. A busy 1883 fails here, so the test never
        // connects the production channel to an unrelated process already owning that port.
        FixedEndpointBroker(plans).use { live ->
            broker.close()
            set("socket", null)
            resetState()
            try {
                test(live)
            } finally {
                try {
                    worker.submit {}.get(3, TimeUnit.SECONDS)
                } finally {
                    try {
                        closeChannelSocket()
                    } finally {
                        try {
                            worker.submit {}.get(3, TimeUnit.SECONDS)
                        } finally {
                            // Closing an in-flight publish can cause its one reconnect attempt.
                            closeChannelSocket()
                        }
                    }
                }
            }
        }
    }

    private enum class ConnectionPlan { ACCEPT, DROP_SECOND_PUBLISH, WRONG_PUBACK, REJECT_CONNECT }

    /** Uses the unchanged production endpoint and validates the actual MQTT 3.1.1 CONNECT. */
    private class FixedEndpointBroker(private val plans: List<ConnectionPlan>) : AutoCloseable {
        private val listener = ServerSocket().apply {
            try {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", 1883))
            } catch (error: Throwable) {
                runCatching { close() }.onFailure { error.addSuppressed(it) }
                throw error
            }
        }
        private val peers = CopyOnWriteArrayList<Socket>()
        private val failure = AtomicReference<Throwable?>()
        val events = CopyOnWriteArrayList<String>()
        val frames = CopyOnWriteArrayList<Publication>()
        val clientIds = CopyOnWriteArrayList<String>()
        @Volatile private var closed = false
        private val server = thread(name = "ecarx-fixed-endpoint-broker", isDaemon = true) {
            try {
                for ((index, plan) in plans.withIndex()) {
                    listener.accept().use { peer ->
                        peers += peer
                        peer.soTimeout = 3_000
                        serve(peer, index + 1, plan)
                    }
                }
            } catch (error: Throwable) {
                if (!closed) failure.set(error)
            }
        }

        private fun serve(peer: Socket, number: Int, plan: ConnectionPlan) {
            val input = peer.getInputStream()
            val output = peer.getOutputStream()
            val connect = readPacket(input) ?: error("Missing CONNECT")
            check(connect.first == 0x10) { "CONNECT fixed header was ${connect.first}" }
            val body = connect.second
            val variableHeader = byteArrayOf(0, 4, 77, 81, 84, 84, 4, 2, 0, 0)
            check(body.size >= 12 && body.copyOfRange(0, 10).contentEquals(variableHeader)) {
                "Expected MQTT 3.1.1, clean session, no credentials/will, keepalive=0"
            }
            val clientLength = u16(body, 10)
            check(body.size == 12 + clientLength) { "Unexpected CONNECT trailing payload" }
            val clientId = String(body, 12, clientLength, Charsets.UTF_8)
            check(clientId == "diplay-projection-pub") { "Publisher must not evict the DIM service's client" }
            clientIds += clientId
            events += "CONNECT:$number"
            if (plan == ConnectionPlan.REJECT_CONNECT) {
                events += "CONNACK_REJECT:$number"
                output.write(byteArrayOf(0x20, 2, 0, 5))
                output.flush()
                return
            }
            output.write(byteArrayOf(0x20, 2, 0, 0))
            output.flush()
            var count = 0
            while (true) {
                val packet = readPacket(input) ?: return
                val publication = publication(packet.first, packet.second)
                frames += publication
                events += "PUBLISH:$number:${publication.id}"
                count++
                if (plan == ConnectionPlan.DROP_SECOND_PUBLISH && count == 2) {
                    events += "DROP:$number"
                    return
                }
                val ackId = if (plan == ConnectionPlan.WRONG_PUBACK) publication.id + 1 else publication.id
                events += "PUBACK:$number:$ackId"
                output.write(byteArrayOf(0x40, 2, (ackId ushr 8).toByte(), ackId.toByte()))
                output.flush()
            }
        }

        fun assertHealthy() {
            failure.get()?.let { throw AssertionError("Fixed-endpoint broker failed", it) }
        }

        override fun close() {
            closed = true
            listener.close()
            peers.forEach { it.close() }
            server.join(1_000)
            check(!server.isAlive) { "Fixed-endpoint broker did not stop" }
            assertHealthy()
        }
    }

    private data class Publication(val header: Int, val id: Int, val topic: String, val payload: String)

    /** Decodes actual MQTT PUBLISH packets and returns their QoS-1 PUBACKs over TCP. */
    private class LocalBroker : AutoCloseable {
        val frames = CopyOnWriteArrayList<Publication>()
        val failure = AtomicReference<Throwable?>()
        private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket(InetAddress.getLoopbackAddress(), listener.localPort).apply { soTimeout = 1_000 }
        private val peer = listener.accept().apply { soTimeout = 3_000 }
        @Volatile private var closed = false
        private val server = thread(name = "ecarx-test-broker", isDaemon = true) {
            try {
                val input = peer.getInputStream()
                val output = peer.getOutputStream()
                while (true) {
                    val packet = readPacket(input) ?: break
                    val frame = publication(packet.first, packet.second)
                    frames += frame
                    output.write(byteArrayOf(0x40, 2, (frame.id ushr 8).toByte(), frame.id.toByte()))
                    output.flush()
                }
            } catch (error: Throwable) {
                if (!closed) failure.set(error)
            }
        }

        init { listener.close() }

        override fun close() {
            closed = true
            client.close()
            peer.close()
            server.join(1_000)
            check(!server.isAlive) { "Local broker did not stop" }
        }

    }

    private companion object {
        fun readPacket(input: InputStream): Pair<Int, ByteArray>? {
            val header = input.read()
            if (header < 0) return null
            var length = 0
            var multiplier = 1
            do {
                val digit = input.read()
                check(digit >= 0 && multiplier <= 128 * 128 * 128) { "Invalid remaining length" }
                length += (digit and 0x7f) * multiplier
                multiplier *= 128
            } while (digit and 0x80 != 0)
            return header to exact(input, length)
        }

        fun publication(header: Int, body: ByteArray): Publication {
            check(header == 0x32) { "Expected QoS-1 publish; header=$header" }
            val topicLength = u16(body, 0)
            val id = u16(body, 2 + topicLength)
            check(id in 1..65_535)
            val topic = String(body, 2, topicLength, Charsets.UTF_8)
            val payload = String(body, 4 + topicLength, body.size - 4 - topicLength, Charsets.UTF_8)
            return Publication(header, id, topic, payload)
        }

        fun u16(data: ByteArray, offset: Int) =
            ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
        fun exact(input: InputStream, size: Int): ByteArray {
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = input.read(bytes, offset, size - offset)
                check(count > 0) { "Truncated MQTT body" }
                offset += count
            }
            return bytes
        }
    }
}
