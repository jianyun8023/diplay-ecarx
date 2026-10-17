package com.shilapi.xcertplay.hud

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The **phone-projection** channel of the ECARX DIM stack — the switch that decides whether the
 * cluster's navigation card and media card are fed by the phone or by the head unit's own sources.
 *
 * ## Why this exists
 *
 * Everything in [EcarxHudBridge] and [EcarxMediaBridge] goes through the XUI DIM API
 * (`com.ecarx.xui.adaptapi.diminteraction`). Measured on the car, that is **not** sufficient:
 *
 *  * the turn arrow only reaches the cluster while the 仪表投屏 switch
 *    ([EcarxDimApi.setPresentationToDimSwitch]) is on — with it off, the card is drawn by the head
 *    unit's own navigation source and nothing we send is shown;
 *  * the media card reads **无媒体** even though every one of the four `IMediaInteraction` calls is
 *    accepted, because the cluster's media card is likewise pointed at the head unit's own sources.
 *
 * Both are the same root cause, and it is one topic away.
 *
 * ## What the head unit's own service does
 *
 * The cluster is served by `se.cevt.dimsupportservice` — a `coreApp`, `persistent` system app
 * (`DimSupportServiceHost`) that is nothing but a Paho MQTT client. Recovered from its own bytecode
 * (`handlers/NavigationHandler`, `handlers/MediaHandler`, both dumped off the car):
 *
 * ```
 * DimSupportService.defaultUri   = "localhost:1883"      // + system property persist.dimsupportservice.brokeruri
 * DimSupportService.clientId     = "dimsupportservice"
 *
 * NavigationHandler.updateNavigationPhoneProjection(int osType)   // 0|1|2, anything else throws
 * MediaHandler.updateMediaPhoneProjection(int osType)             // 0|1|2, anything else throws
 *     -> MqttHelper.publish("ihu/info/phoneprojection", {"statusType": <statusType>})
 *
 *     osType 0 -> "androidAutoNavigation" / "androidAutoMedia"
 *     osType 1 -> "carPlayNavigation"     / "carPlayMedia"
 *     osType 2 -> "noProjection"
 * ```
 *
 * The payload carries a **single key**, but the service has three publishers writing to it —
 * `NavigationHandler`, `MediaHandler` and `PhoneHandler` — each with its own set of values, so the
 * value is a **per-axis** one: `…Navigation` addresses the navigation axis, `…Media` the media axis,
 * `…Telephone` the telephone axis. That is why this class keeps both halves in one place instead of
 * letting the nav and media bridges race each other on the topic (see [update]), and why a frame per
 * axis is published rather than one collapsed value: a single value would mean a track change could
 * take the arrow off the cluster.
 *
 * `noProjection` is the one value with no axis in its name — all three publishers can emit it — so
 * it is treated as "undo the projection" and is only sent once both halves have asked for nothing
 * *and* something was actually projected (see [flush]).
 *
 * ## Why we speak MQTT instead of calling the API
 *
 * `updateNavigationPhoneProjection` / `updateMediaPhoneProjection` live on the *service*, and the
 * third-party-facing facade this project can reach — `DimInteraction` — was never observed to carry
 * a projection setter: the shipped ECARX toolkit
 * (`com/hyphp/hywebviewdcltools/{i,l,m}.smali`) calls exactly four `IMediaInteraction` methods and
 * none of them touches this topic, and its `DimSupportManager` binding is a no-op stub whose
 * `onServiceConnected` only calls `getClass()`. So the topic, its exact payload and the state map
 * are the only part of this path that is *proven*; the API call that would have produced them is
 * not. Publishing the frame ourselves is the honest way to use what is proven.
 *
 * ## What is deliberately conservative
 *
 *  * **QoS 1, not 0.** These are `void` calls everywhere else in this stack and nothing on the DIM
 *    API reports refusal, so the broker's `PUBACK` is the only delivery evidence a third-party app
 *    can obtain. A refused publish is then a fact in the report rather than a silent drop.
 *  * **An observer, not just a publisher.** A second connection subscribes to the same topic plus
 *    the two the service republishes our own pushes on. That is what tells us whether
 *    `setPresentationToDimSwitch` also lands here, and whether our own arrow reached the service's
 *    side — neither of which can be inferred from the XUI calls, all of which return `void`.
 *  * **Nothing is published until a feature asks for it.** An untouched install writes nothing to
 *    this topic (see [update]), so a head unit whose owner never enabled the cluster output is not
 *    re-pointed at a phone that is not there.
 */
internal object EcarxProjectionChannel {
    private const val TAG = "DiPlay-ECARX-PROJ"

    /** `DimSupportService.defaultUri`, and the same value its `serverUri` falls back to. */
    private const val BROKER_HOST = "127.0.0.1"
    private const val BROKER_PORT = 1883

    /** The topic both projection publishers in the service use. */
    private const val TOPIC_PROJECTION = "ihu/info/phoneprojection"

    /** The single key in that topic's payload (`NavigationHandler`/`MediaHandler` both `put` it). */
    private const val KEY_STATUS = "statusType"

    private const val STATUS_CARPLAY_NAVIGATION = "carPlayNavigation"
    private const val STATUS_CARPLAY_MEDIA = "carPlayMedia"
    private const val STATUS_NONE = "noProjection"

    /**
     * What the observer listens to. `ihu/info/phoneprojection` is the state we are trying to set;
     * the other two are where the service republishes what we hand the XUI API, so a line in the
     * report separates "the cluster never got the call" from "the cluster got it and drew nothing".
     */
    private val OBSERVED_TOPICS = arrayOf(
        TOPIC_PROJECTION,
        "ihu/info/navigation/turn-by-turn/icon",
        "ihu/info/media/playback/status",
        "ihu/info/media/progress",
        // The two list topics are retained, so subscribing is enough to read back whatever the head
        // unit's own player last published — the template for the card's track, without asking the
        // driver to start a track first. `registerListTopic` in `MediaHandler` registers exactly
        // three: 7 = sources, 8 = radio stations, 9 = playlist.
        "ihu/info/media/playlist",
        "ihu/info/media/sources",
    )

    /** A local loopback broker answers or fails immediately; these are a liveness check, not a budget. */
    private const val CONNECT_TIMEOUT_MS = 1500
    private const val READ_TIMEOUT_MS = 1500

    /** Ceiling on observer lines, so a chatty session cannot crowd out the rest of the report. */
    private const val OBSERVE_LIMIT = 120

    /** Longest observed payload echoed into a report line. */
    private const val OBSERVE_PAYLOAD_LIMIT = 160

    /**
     * Distinct client ids: the broker keys sessions by client id, and reusing the service's own
     * (`"dimsupportservice"`) would evict it and take the whole DIM stack down with it.
     */
    private const val PUBLISH_CLIENT_ID = "diplay-projection-pub"
    private const val OBSERVE_CLIENT_ID = "diplay-projection-sub"

    private val lock = Any()

    /** The broker connection used for publishing; kept open so a track change is one socket write. */
    private var socket: Socket? = null
    private var packetId = 0
    private var lastConnectReason = "not attempted"

    /**
     * What the two halves want, and the **one** value the cluster was last told.
     *
     * This is a single state, not three independent axes. Measured on this head unit: a track
     * change and a guidance start fired 2 ms apart, `carPlayMedia` then `carPlayNavigation`, and the
     * cluster stayed on the navigation value — the later frame won and the media card it had just
     * been handed never appeared. `noProjection` is likewise the only "off" value, so one half
     * cannot be turned off while the other stays on; the honest frame to send is the survivor.
     */
    private var navigationWanted = false
    private var mediaWanted = false

    /** The value the cluster was last told, or `null` before the first successful frame. */
    private var published: String? = null

    /** True once a frame has gone out, so `noProjection` only ever undoes this project's own work. */
    private var touched = false

    private val observerStarted = AtomicBoolean(false)
    private val observed = AtomicInteger(0)

    /**
     * Every publish runs here rather than on the caller's thread.
     *
     * The callers are [EcarxHudBridge] and [EcarxMediaBridge] tickers, and both hold their own lock
     * around the call; a broker that has gone away costs a connect timeout per attempt, which is not
     * something to spend inside a guidance frame. One thread also keeps the frames in the order the
     * state changed, which is what makes the navigation-last rule in [planLocked] hold on the wire.
     */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diplay-ecarx-projection").apply { isDaemon = true }
    }

    /** Set when a change is queued for [worker]; a burst of changes collapses into one drain. */
    private var queued = false

    private var reportSink: ((String) -> Unit)? = null
    private val unreported = ArrayDeque<String>()

    /** Where this channel's lines go besides logcat; same seam as the two bridges. Idempotent. */
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

    /** The media axis. Called when the now-playing card goes up and when it is cleared. */
    fun setMediaProjection(on: Boolean): String = update { mediaWanted = on }

    /** One line of state for the settings diagnostics block. */
    fun state(): String = synchronized(lock) {
        " projection=" + (published ?: "unset") + " wanted=" + describeLocked() +
            " broker=" + lastConnectReason + " observed=" + observed.get()
    }

    private fun update(mutate: () -> Unit): String = synchronized(lock) {
        mutate()
        val wanted = describeLocked()
        if (!dirtyLocked()) return@synchronized "projection=$wanted"
        if (queued) return@synchronized "projection=$wanted (queued)"
        queued = true
        startObserver()
        worker.execute { flush() }
        "projection=$wanted (queued)"
    }

    /** `navi/media`, with `-` for a half that is off. Only for report lines. */
    private fun describeLocked(): String =
        (if (navigationWanted) "navi" else "-") + "/" + (if (mediaWanted) "media" else "-")

    /**
     * The single value the cluster should be showing.
     *
     * Navigation outranks media because the turn arrow is what a driver reads at a glance, and the
     * topic cannot say both. This is also what keeps a guidance stop from blanking the card: with
     * media still on, the answer is `carPlayMedia` rather than `noProjection`.
     */
    private fun desiredLocked(): String = when {
        navigationWanted -> STATUS_CARPLAY_NAVIGATION
        mediaWanted -> STATUS_CARPLAY_MEDIA
        else -> STATUS_NONE
    }

    /** Whether the single value differs from what the cluster has been told. */
    private fun dirtyLocked(): Boolean {
        val wanted = desiredLocked()
        // Nothing to say while both halves were never on: the head unit's own sources stay untouched.
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

    private fun publishLocked(statusType: String): String {
        val payload = "{\"$KEY_STATUS\":\"$statusType\"}"
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
                out.write(publishPacket(id, TOPIC_PROJECTION, payload))
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

    // ---------------------------------------------------------------- observe

    /**
     * Mirrors what actually moves on the broker into the report.
     *
     * Started lazily — from the first publish, or from the projection watch in [EcarxHudBridge] while
     * the 仪表投屏 switch is on — so the traffic this sees is exactly the traffic produced by the
     * features under test.
     */
    fun startObserver() {
        if (!observerStarted.compareAndSet(false, true)) return
        Thread({ observeLoop() }, "diplay-ecarx-mqtt").apply { isDaemon = true }.start()
    }

    private fun observeLoop() {
        var connection: Socket? = null
        try {
            connection = Socket()
            connection.connect(InetSocketAddress(BROKER_HOST, BROKER_PORT), CONNECT_TIMEOUT_MS)
            val input = connection.getInputStream()
            val out = connection.getOutputStream()
            out.write(connectPacket(OBSERVE_CLIENT_ID))
            out.flush()
            val ack = readPacket(input)
            val code = ack?.second?.lastOrNull()?.toInt() ?: -1
            if (ack == null || (ack.first and 0xF0) != 0x20 || code != 0) {
                report("projection observer refused: connack=$code")
                return
            }
            for (topic in OBSERVED_TOPICS) {
                out.write(subscribePacket(nextPacketId(), topic))
            }
            out.flush()
            report("projection observer subscribed ${OBSERVED_TOPICS.joinToString(",")}")
            // No read timeout from here on: this thread does nothing but wait for the broker.
            connection.soTimeout = 0
            // Repeats are dropped, changes are kept. A state topic re-sends the same payload every
            // second while guidance runs, and counting each one would fill the ceiling below with
            // the arrow and push the media topics out of it — those arrive once, as retained
            // messages, the moment the subscription is made.
            val seen = HashSet<String>()
            while (observed.get() < OBSERVE_LIMIT) {
                val packet = readPacket(input) ?: break
                if ((packet.first and 0xF0) != 0x30) continue
                val text = publishText(packet.first, packet.second) ?: continue
                if (!seen.add(text)) continue
                observed.incrementAndGet()
                report("projection observed $text")
            }
        } catch (error: Exception) {
            report("projection observer stopped: ${reason(error)}")
        } finally {
            runCatching { connection?.close() }
        }
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

    private fun subscribePacket(id: Int, topicFilter: String): ByteArray {
        val body = ByteArrayOutputStream()
        body.write((id ushr 8) and 0xFF)
        body.write(id and 0xFF)
        mqttString(body, topicFilter)
        body.write(0) // requested QoS 0: observation must never slow the broker down
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

    /** `"<topic> <payload>"` for a PUBLISH, truncated so one frame cannot fill a report line. */
    private fun publishText(first: Int, body: ByteArray): String? {
        if (body.size < 4) return null
        val topicLength = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        if (topicLength + 2 > body.size) return null
        val topic = String(body, 2, topicLength, Charsets.UTF_8)
        var offset = 2 + topicLength
        if (((first shr 1) and 0x03) > 0) offset += 2 // QoS > 0 carries a packet id
        if (offset > body.size) return null
        val payload = String(body, offset, body.size - offset, Charsets.UTF_8)
            .replace('\n', ' ')
            .take(OBSERVE_PAYLOAD_LIMIT)
        return "$topic $payload"
    }

    private fun reason(error: Throwable): String = "${error.javaClass.simpleName}: ${error.message}"
}
