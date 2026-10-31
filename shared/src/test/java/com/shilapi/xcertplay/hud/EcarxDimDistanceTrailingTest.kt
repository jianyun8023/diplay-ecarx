package com.shilapi.xcertplay.hud

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EcarxDimDistanceTrailingTest {
    private val guard = EcarxDimDistanceGuard
    private val next = EcarxDimDistanceGuard.TOPIC_TURN_BY_TURN_NEXT
    private val destination = EcarxDimDistanceGuard.TOPIC_DISTANCE_TO_DESTINATION
    private lateinit var broker: Broker
    private val worker get() = ReflectionHelpers.getField<ScheduledExecutorService>(guard, "worker")
    private fun set(name: String, value: Any?) = ReflectionHelpers.setField(guard, name, value)
    private fun message(topic: String, payload: String) {
        guard.javaClass.getDeclaredMethod("onMessage", String::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(guard, topic, payload)
    }
    @Before fun setup() {
        guard.stopGuarding()
        worker.submit {}.get(2, TimeUnit.SECONDS)
        broker = Broker()
        set("active", true)
        set("turnNext", guard.turnByTurnPayload(90, "Road"))
        set("destinationDistance", guard.distancePayload(3000))
        set("publisher", broker)
    }
    @After fun cleanup() {
        guard.stopGuarding()
        worker.submit {}.get(2, TimeUnit.SECONDS)
    }
    private fun awaitCount(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (broker.writes.size < count && System.nanoTime() < deadline) Thread.sleep(2)
        assertEquals(count, broker.writes.size)
    }

    @Test fun finalConflictWithinThrottleWindowIsCorrectedForBothTopics() {
        for (topic in listOf(next, destination)) {
            val before = broker.writes.size
            message(topic, "foreign first")
            awaitCount(before + 1)
            repeat(5) { message(topic, "foreign last $it") }
            awaitCount(before + 2)
        }
        assertEquals(listOf(next, next, destination, destination), broker.writes.map { it.first })
    }

    @Test fun delayedCorrectionUsesLatestGuidance() {
        message(next, "foreign first")
        awaitCount(1)
        message(next, "foreign final")
        set("turnNext", guard.turnByTurnPayload(70, "New road"))
        awaitCount(2)
        assertEquals(guard.turnByTurnPayload(70, "New road"), broker.writes.last().second)
    }

    @Test fun matchingEchoCancelsUnnecessaryTrailingCorrection() {
        message(next, "foreign first")
        awaitCount(1)
        message(next, "foreign final")
        message(next, guard.turnByTurnPayload(90, "Road"))
        Thread.sleep(180)
        assertEquals(1, broker.writes.size)
    }

    @Test fun stoppedNavigationCannotPublishQueuedCorrectionIntoNewSession() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker.execute { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        try {
            message(next, "foreign")
            guard.stopGuarding()
            set("active", true)
            set("turnNext", guard.turnByTurnPayload(500, "New session"))
            set("publisher", broker)
        } finally { release.countDown() }
        worker.submit {}.get(2, TimeUnit.SECONDS)
        assertTrue(broker.writes.isEmpty())
        message(next, "new foreign")
        awaitCount(1)
        assertEquals(guard.turnByTurnPayload(500, "New session"), broker.writes.single().second)
    }

    /** The real MQTT encoder writes here; a synthetic broker returns the matching PUBACK. */
    private class Broker : Socket() {
        val writes = CopyOnWriteArrayList<Pair<String, String>>()
        private val acknowledgements = LinkedBlockingQueue<Int>()
        override fun getInputStream() = object : InputStream() {
            override fun read(): Int = acknowledgements.poll(2, TimeUnit.SECONDS) ?: error("Missing PUBACK")
        }
        override fun getOutputStream() = object : OutputStream() {
            override fun write(value: Int) = error("Expected a complete MQTT packet")
            override fun write(bytes: ByteArray) {
                val (header, body) = EcarxMqtt.readPacket(bytes.inputStream())!!
                val topicLength = ((body[0].toInt() and 255) shl 8) or (body[1].toInt() and 255)
                val idOffset = topicLength + 2
                writes += EcarxMqtt.parsePublish(header, body)!!
                listOf(0x40, 2, body[idOffset].toInt() and 255, body[idOffset + 1].toInt() and 255)
                    .forEach(acknowledgements::add)
            }
        }
    }
}
