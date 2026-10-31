package com.shilapi.xcertplay.hud

import java.net.Socket
import java.util.HashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps the DIM service's live guidance distance on CarPlay's value.
 *
 * `se.cevt.dimsupportservice` is a single, source-agnostic MQTT publisher: the factory
 * `ecarx.naviservice` and DiPlay each push the same `ihu/info/navigation/turn-by-turn/next-turn`
 * topic, so whichever writes last wins. The factory's `MapManager.handleGuideInfoChanged()` clamps
 * `nextTurnDistance <= 0` to `1`, which is the "1 m vs normal" flicker. Because each app de-dupes its
 * own `NaviInteraction`, re-sending an unchanged distance is dropped, so the only way to win is to
 * observe the shared topic and re-assert our value after a foreign write. That is this guard.
 *
 * Deliberately separate from [EcarxProjectionChannel]: projection owns the cluster video source, the
 * guard owns two distance topics, and both share [EcarxMqtt] for bytes.
 */
internal object EcarxDimDistanceGuard {
    private const val TAG = "DiPlay-ECARX-GUARD"
    private const val PUBLISHER_CLIENT_ID = "diplay-nav-guard-pub"
    private const val SUBSCRIBER_CLIENT_ID = "diplay-nav-guard-sub"
    private const val GUARD_MIN_CORRECTION_MS = 120L
    private const val GUARD_RECONNECT_MS = 1_000L
    private const val TBT_ADDRESS_MAX_LENGTH = 32

    /** The two live guidance-distance topics `NavigationHandler` publishes on. */
    internal const val TOPIC_TURN_BY_TURN_NEXT = "ihu/info/navigation/turn-by-turn/next-turn"
    internal const val TOPIC_DISTANCE_TO_DESTINATION = "ihu/events/navigation/distance"

    private val lock = Any()
    private val sink = EcarxReportSink(TAG)
    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "diplay-nav-guard-pub").apply { isDaemon = true }
    }

    private var active = false
    private var turnNext: String? = null
    private var destinationDistance: String? = null
    private var subscriber: Socket? = null
    private var reader: Thread? = null
    private var publisher: Socket? = null
    private var publisherReason = "not attempted"
    private var packetId = 0
    private val lastCorrectionAt = HashMap<String, Long>()
    private val pending = HashMap<String, ScheduledFuture<*>>()
    private var generation = 0L

    fun attachReport(sink: (String) -> Unit) = this.sink.attach(sink)

    /**
     * Guidance frame: remember our authoritative distance and start the subscriber on first use.
     * The guard does not publish here; [EcarxHudBridge] still writes through the XUI API and the
     * guard only re-asserts after a foreign write.
     */
    fun guardGuidance(nextMeters: Int, remainingMeters: Int?, road: String) = synchronized(lock) {
        active = true
        turnNext = turnByTurnPayload(nextMeters, road)
        destinationDistance = remainingMeters?.let { distancePayload(it) }
        startLocked()
    }

    /** Guidance ended: stop correcting and release both sockets so the guard is idle on the road. */
    fun stopGuarding() = synchronized(lock) {
        active = false
        generation++
        pending.values.forEach { it.cancel(false) }
        pending.clear()
        lastCorrectionAt.clear()
        sink.line("nav guard stopped; pending corrections cancelled generation=$generation")
        turnNext = null
        destinationDistance = null
        runCatching { subscriber?.close() }
        subscriber = null
        runCatching { publisher?.close() }
        publisher = null
        publisherReason = "not attempted"
    }

    fun state(): String = synchronized(lock) {
        "active=$active subscribed=${subscriber != null} publisher=$publisherReason"
    }

    // ---------------------------------------------------------------- correction

    private fun startLocked() {
        if (reader != null) return
        val thread = Thread({ runSubscriber() }, "diplay-nav-guard-sub").apply { isDaemon = true }
        reader = thread
        thread.start()
    }

    private fun runSubscriber() {
        while (isActive()) {
            val connection = EcarxMqtt.connect(SUBSCRIBER_CLIENT_ID, readTimeoutMs = 0) ?: run {
                sleepQuietly(GUARD_RECONNECT_MS)
                continue
            }
            try {
                synchronized(lock) { subscriber = connection }
                if (!EcarxMqtt.subscribe(connection, nextPacketId(), listOf(TOPIC_TURN_BY_TURN_NEXT, TOPIC_DISTANCE_TO_DESTINATION))) {
                    sleepQuietly(GUARD_RECONNECT_MS)
                    continue
                }
                sink.line("nav guard subscribed")
                while (isActive()) {
                    val packet = EcarxMqtt.readPacket(connection.getInputStream()) ?: break
                    if ((packet.first and 0xF0) != 0x30) continue
                    val (topic, payload) = EcarxMqtt.parsePublish(packet.first, packet.second) ?: continue
                    onMessage(topic, payload)
                }
            } catch (_: Exception) {
                // Broker gone, or the socket was closed by stopGuarding().
            } finally {
                synchronized(lock) { if (subscriber === connection) subscriber = null }
                runCatching { connection.close() }
            }
            if (isActive()) sleepQuietly(GUARD_RECONNECT_MS)
        }
        synchronized(lock) { reader = null; if (active) startLocked() }
    }

    private fun onMessage(topic: String, payload: String) = synchronized(lock) {
        if (!active) return
        val desired = desiredFor(topic) ?: return
        if (payload == desired) {
            pending.remove(topic)?.cancel(false)
            return
        }
        if (pending.containsKey(topic)) return
        val now = System.nanoTime() / 1_000_000L
        val last = lastCorrectionAt[topic] ?: 0L
        val delay = if (shouldCorrect(desired, payload, now, last)) 0L
            else (GUARD_MIN_CORRECTION_MS - (now - last)).coerceAtLeast(0L)
        val expectedGeneration = generation
        pending[topic] = worker.schedule({
            synchronized(lock) {
                if (!active || generation != expectedGeneration) return@schedule
                pending.remove(topic)
                if (desiredFor(topic) == null) return@schedule
                lastCorrectionAt[topic] = System.nanoTime() / 1_000_000L
            }
            val outcome = publishRaw(topic, expectedGeneration)
            sink.line("nav guard corrected $topic -> $outcome generation=$expectedGeneration")
        }, delay, TimeUnit.MILLISECONDS)
        sink.line("nav guard correction queued delayMs=$delay topic=$topic generation=$expectedGeneration")
    }

    private fun publishRaw(topic: String, expectedGeneration: Long): String {
        var last = "no attempt"
        repeat(2) {
            val connection = synchronized(lock) {
                if (!active || generation != expectedGeneration) return "cancelled"
                publisher
            } ?: connectPublisher(expectedGeneration) ?: return publisherReason
            // A queued correction follows the latest guidance, never a captured old distance.
            val payload = synchronized(lock) {
                if (!active || generation != expectedGeneration) return "cancelled"
                desiredFor(topic)
            } ?: return "cancelled"
            last = EcarxMqtt.publish(connection, nextPacketId(), topic, payload)
            if (last == "ok") return "ok"
            synchronized(lock) {
                if (publisher === connection) publisher = null
                runCatching { connection.close() }
            }
        }
        return last
    }

    private fun connectPublisher(expectedGeneration: Long): Socket? {
        val connection = EcarxMqtt.connect(PUBLISHER_CLIENT_ID)
        synchronized(lock) {
            if (!active || generation != expectedGeneration) {
                runCatching { connection?.close() }
                return null
            }
            publisherReason = if (connection == null) "connect failed" else "ok"
            publisher = connection
        }
        return connection
    }

    private fun desiredFor(topic: String): String? = when (topic) {
        TOPIC_TURN_BY_TURN_NEXT -> turnNext
        TOPIC_DISTANCE_TO_DESTINATION -> destinationDistance
        else -> null
    }

    private fun isActive(): Boolean = synchronized(lock) { active }

    private fun nextPacketId(): Int = synchronized(lock) {
        packetId = packetId % 0xFFFF + 1
        packetId
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ---------------------------------------------------------------- payloads (JsonHelper)

    /**
     * Whether a foreign payload should be corrected now: only a *different* value, and no more often
     * than [GUARD_MIN_CORRECTION_MS] for that topic. Pure so the policy is unit-testable.
     */
    internal fun shouldCorrect(desired: String?, inbound: String, nowMs: Long, lastCorrectionMs: Long): Boolean =
        desired != null && inbound != desired && nowMs - lastCorrectionMs >= GUARD_MIN_CORRECTION_MS

    /** Mirrors `se.cevt.dimsupportservice.util.JsonHelper.convertDistance(int, true)` exactly. */
    internal fun convertDistance(distance: Int): String {
        val space = " "
        return when {
            distance < 1000 -> "$distance$space" + "m"
            distance < 10000 -> {
                val roundRes = Math.round(distance / 100.0f).toLong()
                val hundredRes = 100L * roundRes
                if (hundredRes % 1000L != 0L) "${hundredRes / 1000.0}$space" + "km"
                else "${hundredRes / 1000}$space" + "km"
            }
            else -> "${distance / 1000}$space" + "km"
        }
    }

    /** `NavigationHandler.updateTurnByTurnNextTurn` payload: distance then address (32-char clamp). */
    internal fun turnByTurnPayload(meters: Int, road: String): String {
        val address = if (road.length > TBT_ADDRESS_MAX_LENGTH) road.substring(0, TBT_ADDRESS_MAX_LENGTH) else road
        return "{\"distance\":\"${EcarxMqtt.jsonEscape(convertDistance(meters))}\",\"address\":\"${EcarxMqtt.jsonEscape(address)}\"}"
    }

    /** `NavigationHandler.updateDistanceToDestination` payload. */
    internal fun distancePayload(meters: Int): String =
        "{\"distance\":\"${EcarxMqtt.jsonEscape(convertDistance(meters))}\"}"
}
