package com.shilapi.xcertplay.hud

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.HashMap
import java.util.concurrent.Executors

/** Navigation projection channel for the ECARX DIM stack. */
internal object EcarxProjectionChannel {
    private const val TAG = "DiPlay-ECARX-PROJ"

    /** `DimSupportService.defaultUri`, and the same value its `serverUri` falls back to. */
    private const val BROKER_HOST = "127.0.0.1"
    private const val BROKER_PORT = 1883

    /** The topic both projection publishers in the service use. */
    private const val TOPIC_PROJECTION = "ihu/info/phoneprojection"

    /**
     * `NavigationHandler.updateOngoingNavigation(boolean, String, boolean)` — the head unit's "a
     * navigation is running" event, payload `{"naviEvent":"ongoingNavi"|"idle", …}`.
     *
     * This is the one navigation signal the XUI API may not produce at all: our arrow, distance and
     * road name are all visible on the broker (`projection observed
     * ihu/info/navigation/turn-by-turn/icon {"imageData":"<svg…"}` in the 12:06 report), so the data
     * path is not the problem — but the cluster still draws nothing unless its navigation
     * *presentation* is on. `notifyTurnByTurnStarted()` is the only call on our side that could have
     * set that state, it returns `void`, and nothing has ever confirmed it reached this handler.
     * Publishing the event makes that question answerable on the car.
     */
    private const val TOPIC_NAV_ONGOING = "ihu/events/navigation/ongoing"

    /** The single key in that topic's payload (`NavigationHandler`/`MediaHandler` both `put` it). */
    private const val KEY_STATUS = "statusType"

    private const val STATUS_CARPLAY_NAVIGATION = "carPlayNavigation"
    private const val STATUS_NONE = "noProjection"

    /** A local loopback broker answers or fails immediately; these are a liveness check, not a budget. */
    private const val CONNECT_TIMEOUT_MS = 1500
    private const val READ_TIMEOUT_MS = 1500

    /**
     * This connection's client id: the broker keys sessions by it, and reusing the service's own
     * (`"dimsupportservice"`) would evict it and take the whole DIM stack down with it.
     */
    private const val PUBLISH_CLIENT_ID = "diplay-projection-pub"

    /**
     * The two live guidance-distance topics `se.cevt.dimsupportservice` publishes on. The factory
     * `ecarx.naviservice` writes the same topics from its own `NaviInteraction` instance
     * (`MapManager.handleGuideInfoChanged`: `nextTurnDistance <= 0 -> 1`), so whichever app
     * publishes last wins. This channel subscribes to both and re-asserts our value after a foreign
     * write — instead of disabling the factory package, which also hosts the desktop navigation
     * widgets and cannot be restored once disabled.
     */
    internal const val TOPIC_TURN_BY_TURN_NEXT = "ihu/info/navigation/turn-by-turn/next-turn"
    internal const val TOPIC_DISTANCE_TO_DESTINATION = "ihu/events/navigation/distance"

    /**
     * A separate client id from [PUBLISH_CLIENT_ID]: the broker keys sessions by client id, so a
     * subscriber must not evict the publisher (or the DIM service) from its session.
     */
    private const val GUARD_CLIENT_ID = "diplay-nav-guard"
    private const val GUARD_MIN_CORRECTION_MS = 120L
    private const val GUARD_RECONNECT_MS = 1_000L

    private val lock = Any()

    /** The broker connection used for publishing; kept open so a track change is one socket write. */
    private var socket: Socket? = null
    private var packetId = 0
    private var lastConnectReason = "not attempted"

    private var navigationWanted = false

    /** The value the cluster was last told, or `null` before the first successful frame. */
    private var published: String? = null

    /** True once a frame has gone out, so `noProjection` only ever undoes this project's own work. */
    private var touched = false

    /** The `ongoing` payload last published, so an unchanged road name is not re-sent. */
    private var lastOngoing: String? = null

    // ---------------------------------------------------------------- navigation distance guard

    /** True only while CarPlay guidance wants the cluster's distance; a foreign write is corrected. */
    private var guardActive = false
    private var guardTurnNext: String? = null
    private var guardDistance: String? = null
    private var guardSocket: Socket? = null
    private var guardThread: Thread? = null
    private val guardCorrectionAt = HashMap<String, Long>()

    /**
     * Every publish runs here rather than on the caller's thread.
     *
     * The callers are [EcarxHudBridge] tickers, which hold the bridge lock
     * around the call; a broker that has gone away costs a connect timeout per attempt, which is not
     * something to spend inside a guidance frame. One thread also keeps the frames in the order the
     * state changed.
     */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diplay-ecarx-projection").apply { isDaemon = true }
    }

    /** Set when a change is queued for [worker]; a burst of changes collapses into one drain. */
    private var queued = false

    private var reportSink: ((String) -> Unit)? = null
    private val unreported = ArrayDeque<String>()

    /** Where this channel's lines go besides logcat; same seam as the HUD bridge. Idempotent. */
    fun attachReport(sink: (String) -> Unit) = synchronized(lock) {
        reportSink = sink
        unreported.forEach(sink)
        unreported.clear()
    }

    /** logcat *and* the report. */
    private fun report(line: String) {
        Log.i(TAG, line)
        val sink = reportSink
        if (sink != null) {
            sink(line)
            return
        }
        while (unreported.size >= 32) unreported.removeFirst()
        unreported.addLast(line)
    }

    /**
     * The navigation axis. Called when guidance starts and stops.
     *
     * This is the half that answers the car's own complaint — *the arrow only appears while the
     * 仪表投屏 switch is on* — by pointing the cluster's navigation source at the phone without
     * mirroring a window onto it (see the class doc).
     */
    fun setNavigationProjection(on: Boolean): String = update { navigationWanted = on }


    /**
     * Tells the cluster that a navigation is running (or has stopped), on the event topic the head
     * unit's own navigation app uses.
     *
     * Separate from [setNavigationProjection]: that one points the cluster's navigation *source* at
     * the phone and is the value that also puts the picture on the cluster, while this one only says
     * "a navigation exists". If the cluster's card is gated on the second, the card can appear with
     * the first left alone — which is the whole point of the experiment.
     *
     * `destination` is the road text the cluster shows beside its own navigation icons. Empty is
     * valid: the stock handler is called with an empty address when no destination string exists.
     */
    fun setNavigationOngoing(on: Boolean, destination: String): String = synchronized(lock) {
        val payload = ongoingPayload(on, destination)
        if (payload == lastOngoing) return@synchronized "navi ongoing unchanged"
        lastOngoing = payload
        worker.execute {
            val outcome = synchronized(lock) { publishRawLocked(TOPIC_NAV_ONGOING, payload) }
            report("navi ongoing ${if (on) "on" else "off"} -> $outcome")
        }
        "navi ongoing ${if (on) "on" else "off"} (queued)"
    }

    /**
     * `NavigationHandler`'s own payload, field for field: `naviEvent` carries the state and the four
     * icon names are the ones the cluster's card chrome expects. `iconRight` is `unmuteIcon` because
     * CarPlay's mute state is not something this project tracks.
     */
    private fun ongoingPayload(on: Boolean, destination: String): String {
        val event = if (on) "ongoingNavi" else "idle"
        return "{\"naviEvent\":\"$event\",\"naviIcon\":\"hubsNavi\",\"destination\":\"" +
            escapeJson(destination) + "\",\"iconLeft\":\"naviNewIcon\",\"iconCenter\":\"cancelNaviIcon\"," +
            "\"iconRight\":\"unmuteIcon\",\"destinationFlagIcon\":\"destinationFlagIcon\"}"
    }

    private fun escapeJson(value: String): String {
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

    // ---------------------------------------------------------------- navigation distance guard

    /**
     * Called on every guidance frame with our authoritative distance. Starts the guard on first use.
     * The guard does not replace the XUI publish: it only re-asserts this value when the factory
     * overwrites the shared topic (see [TOPIC_TURN_BY_TURN_NEXT]).
     */
    fun guardGuidance(nextMeters: Int, remainingMeters: Int?, road: String) = synchronized(lock) {
        guardActive = true
        guardTurnNext = turnByTurnPayload(nextMeters, road)
        guardDistance = remainingMeters?.let { distancePayload(it) }
        startGuardLocked()
    }

    /** Guidance ended: stop correcting, and stop the subscriber so it costs nothing on the road. */
    fun stopGuarding() = synchronized(lock) {
        guardActive = false
        guardTurnNext = null
        guardDistance = null
        closeGuardLocked()
    }

    /** One-line state for the settings diagnostics block. */
    fun guardState(): String = synchronized(lock) {
        "active=$guardActive subscribed=${guardSocket != null}"
    }

    private fun startGuardLocked() {
        if (guardThread != null) return
        val thread = Thread({ runGuardSubscriber() }, "diplay-nav-guard").apply { isDaemon = true }
        guardThread = thread
        thread.start()
    }

    private fun closeGuardLocked() {
        runCatching { guardSocket?.close() }
        guardSocket = null
        // The subscriber observes guardActive == false after its read fails and exits; it is not
        // joined here because this is called while holding [lock].
    }

    private fun runGuardSubscriber() {
        while (guardEnabled()) {
            val connection = connectGuard() ?: run {
                sleepQuietly(GUARD_RECONNECT_MS)
                continue
            }
            try {
                val out = connection.getOutputStream()
                out.write(subscribePacket(nextGuardPacketId(), TOPIC_TURN_BY_TURN_NEXT, TOPIC_DISTANCE_TO_DESTINATION))
                out.flush()
                val suback = readPacket(connection.getInputStream())
                if (suback == null || (suback.first and 0xF0) != 0x90) {
                    runCatching { connection.close() }
                    sleepQuietly(GUARD_RECONNECT_MS)
                    continue
                }
                report("nav guard subscribed")
                while (guardEnabled()) {
                    val packet = readPacket(connection.getInputStream()) ?: break
                    if ((packet.first and 0xF0) != 0x30) continue
                    val (topic, payload) = parsePublish(packet.first, packet.second) ?: continue
                    onGuardMessage(topic, payload)
                }
            } catch (_: Exception) {
                // Broker gone, or the socket was closed by stopGuarding().
            } finally {
                synchronized(lock) { if (guardSocket === connection) guardSocket = null }
                runCatching { connection.close() }
            }
            if (guardEnabled()) sleepQuietly(GUARD_RECONNECT_MS)
        }
        synchronized(lock) { guardThread = null }
    }

    private fun guardEnabled(): Boolean = synchronized(lock) { guardActive }

    private fun connectGuard(): Socket? = runCatching {
        val connection = Socket()
        connection.connect(InetSocketAddress(BROKER_HOST, BROKER_PORT), CONNECT_TIMEOUT_MS)
        connection.soTimeout = 0 // Block; stopGuarding() closes the socket to release the read.
        val out = connection.getOutputStream()
        out.write(connectPacket(GUARD_CLIENT_ID))
        out.flush()
        val ack = readPacket(connection.getInputStream())
        val code = ack?.second?.lastOrNull()?.toInt() ?: -1
        if (ack == null || (ack.first and 0xF0) != 0x20 || code != 0) {
            runCatching { connection.close() }
            null
        } else {
            synchronized(lock) { guardSocket = connection }
            connection
        }
    }.getOrElse { null }

    private fun onGuardMessage(topic: String, payload: String) {
        val desired = synchronized(lock) {
            if (!guardActive) return
            desiredLocked(topic)
        } ?: return
        if (payload == desired) return // our own echo, or already correct
        val now = System.nanoTime() / 1_000_000L
        synchronized(lock) {
            val last = guardCorrectionAt[topic] ?: 0L
            if (now - last < GUARD_MIN_CORRECTION_MS) return
            guardCorrectionAt[topic] = now
        }
        worker.execute {
            val current = synchronized(lock) { if (guardActive) desiredLocked(topic) else null } ?: return@execute
            val outcome = synchronized(lock) { publishRawLocked(topic, current) }
            report("nav guard corrected $topic -> $outcome")
        }
    }

    private fun desiredLocked(topic: String): String? = when (topic) {
        TOPIC_TURN_BY_TURN_NEXT -> guardTurnNext
        TOPIC_DISTANCE_TO_DESTINATION -> guardDistance
        else -> null
    }

    private fun nextGuardPacketId(): Int = synchronized(lock) {
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

    // ---------------------------------------------------------------- distance payload (JsonHelper)

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
        val address = if (road.length > 32) road.substring(0, 32) else road
        return "{\"distance\":\"${escapeJson(convertDistance(meters))}\",\"address\":\"${escapeJson(address)}\"}"
    }

    /** `NavigationHandler.updateDistanceToDestination` payload. */
    internal fun distancePayload(meters: Int): String =
        "{\"distance\":\"${escapeJson(convertDistance(meters))}\"}"

    /** One line of state for the settings diagnostics block. */
    fun state(): String = synchronized(lock) {
        " projection=" + (published ?: "unset") + " wanted=" + describeLocked() +
            " broker=" + lastConnectReason
    }

    private fun update(mutate: () -> Unit): String = synchronized(lock) {
        mutate()
        val wanted = describeLocked()
        if (!dirtyLocked()) return@synchronized "projection=$wanted"
        if (queued) return@synchronized "projection=$wanted (queued)"
        queued = true
        worker.execute { flush() }
        "projection=$wanted (queued)"
    }

    private fun describeLocked(): String = if (navigationWanted) "navi" else "-"

    private fun desiredLocked(): String = if (navigationWanted) STATUS_CARPLAY_NAVIGATION else STATUS_NONE

    /** Whether the single value differs from what the cluster has been told. */
    private fun dirtyLocked(): Boolean {
        val wanted = desiredLocked()
        // Nothing to say before navigation has been on: the head unit's own sources stay untouched.
        if (wanted == STATUS_NONE) return touched && published != STATUS_NONE
        return wanted != published
    }

    /**
     * Runs on [worker]. Publishes whatever the state is **when it runs**, not what it was when the
     * frame was queued: a start and a stop that arrive while a publish is in flight must collapse to
     * one frame carrying the *later* value.
     */
    private fun flush() {
        val frame = synchronized(lock) {
            if (!queued) return
            queued = false
            planLocked()
        } ?: return
        val outcome = synchronized(lock) { publishLocked(frame) }
        report("projection $frame -> $outcome")
        if (outcome != "ok") return
        synchronized(lock) {
            touched = true
            published = frame
        }
    }

    /**
     * The one frame this change needs, or `null` when the cluster already agrees.
     *
     * Exactly one: the payload carries a single `statusType`, and a second frame would silently
     * overwrite the first (see the state fields).
     */
    private fun planLocked(): String? {
        val wanted = desiredLocked()
        if (wanted == STATUS_NONE && !touched) return null
        if (wanted == published) return null
        return wanted
    }

    // ---------------------------------------------------------------- publish

    private fun publishLocked(statusType: String): String =
        publishRawLocked(TOPIC_PROJECTION, "{\"$KEY_STATUS\":\"$statusType\"}")

    /** One QoS-1 publish on an already-known topic, with the PUBACK as the only delivery evidence. */
    private fun publishRawLocked(topic: String, payload: String): String {
        var last = "no attempt"
        var attempt = 0
        // One retry, and only through a fresh connection: a broker that took the first frame but ate
        // the PUBACK is indistinguishable from one that never got the frame, and re-publishing a
        // state topic is idempotent.
        while (attempt < 2) {
            attempt += 1
            val connection = socket ?: connectLocked() ?: return lastConnectReason
            val id = nextPacketId()
            try {
                val out = connection.getOutputStream()
                out.write(publishPacket(id, topic, payload))
                out.flush()
                val ack = readPacket(connection.getInputStream())
                val acked = if (ack != null && (ack.first and 0xF0) == 0x40 && ack.second.size >= 2) {
                    ((ack.second[0].toInt() and 0xFF) shl 8) or (ack.second[1].toInt() and 0xFF)
                } else {
                    -1
                }
                if (acked == id) return "ok"
                last = if (ack == null) "no puback" else "puback=$acked wanted=$id"
            } catch (error: Exception) {
                last = "write failed ${reason(error)}"
            }
            dropLocked()
        }
        return last
    }

    private fun connectLocked(): Socket? = runCatching {
        val connection = Socket()
        connection.connect(InetSocketAddress(BROKER_HOST, BROKER_PORT), CONNECT_TIMEOUT_MS)
        connection.soTimeout = READ_TIMEOUT_MS
        val out = connection.getOutputStream()
        out.write(connectPacket(PUBLISH_CLIENT_ID))
        out.flush()
        val ack = readPacket(connection.getInputStream())
        val code = ack?.second?.lastOrNull()?.toInt() ?: -1
        if (ack == null || (ack.first and 0xF0) != 0x20 || code != 0) {
            connection.close()
            lastConnectReason = "connack=$code"
            null
        } else {
            socket = connection
            lastConnectReason = "ok"
            connection
        }
    }.getOrElse {
        lastConnectReason = "connect ${reason(it)}"
        null
    }

    private fun dropLocked() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun nextPacketId(): Int {
        packetId = packetId % 0xFFFF + 1
        return packetId
    }

    // ---------------------------------------------------------------- MQTT 3.1.1 framing

    private fun connectPacket(clientId: String): ByteArray {
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
    private fun publishPacket(id: Int, topic: String, payload: String): ByteArray {
        val body = ByteArrayOutputStream()
        mqttString(body, topic)
        body.write((id ushr 8) and 0xFF)
        body.write(id and 0xFF)
        val bytes = payload.toByteArray(Charsets.UTF_8)
        body.write(bytes, 0, bytes.size)
        return frame(0x32, body.toByteArray())
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
    private fun readPacket(input: InputStream): Pair<Int, ByteArray>? {
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

    /** MQTT SUBSCRIBE with QoS 1 for each topic. */
    internal fun subscribePacket(id: Int, vararg topics: String): ByteArray {
        val body = ByteArrayOutputStream()
        body.write((id ushr 8) and 0xFF)
        body.write(id and 0xFF)
        for (topic in topics) {
            mqttString(body, topic)
            body.write(1) // requested QoS 1
        }
        return frame(0x82, body.toByteArray())
    }

    /** Extracts `(topic, payload)` from a PUBLISH packet, or null when `first` is not a PUBLISH. */
    internal fun parsePublish(first: Int, body: ByteArray): Pair<String, String>? {
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
        val payload = String(body, index, body.size - index, Charsets.UTF_8)
        return topic to payload
    }

    private fun reason(error: Throwable): String = "${error.javaClass.simpleName}: ${error.message}"
}
