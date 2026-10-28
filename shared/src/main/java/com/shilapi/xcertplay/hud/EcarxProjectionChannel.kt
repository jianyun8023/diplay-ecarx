package com.shilapi.xcertplay.hud

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
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

    private fun reason(error: Throwable): String = "${error.javaClass.simpleName}: ${error.message}"
}
