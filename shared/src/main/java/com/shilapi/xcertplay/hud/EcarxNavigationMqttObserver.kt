package com.shilapi.xcertplay.hud

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** A separate clean session subscribes at QoS 0. Observation never sends navigation commands. */
internal class EcarxNavigationMqttObserver(
    private val report: (String) -> Unit,
    private val port: Int = EcarxMqtt.DEFAULT_PORT,
    private val debugLogsEnabled: () -> Boolean = { false },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val topics = listOf(
        "dim/actions/navigation/map", "dim/actions/navigation/fullscreenmap", "dim/actions/navigation/ongoing",
        "ihu/events/navigation/fullscreenmap", "ihu/events/navigation/ongoing",
        "ihu/info/navigation/turn-by-turn/next-turn", "ihu/info/navigation/turn-by-turn/icon",
        "ihu/info/navigation/turn-by-turn/lanesupport",
        "ihu/info/phoneprojection", "ihu/heartbeat/navigation",
        "ihu/events/navigation/distance", "ihu/events/navigation/ETA",
    )
    private data class Observation(
        val summary: String, val retained: Boolean, val count: Long, val seenMs: Long,
        val reportedMs: Long, val reportedCount: Long,
    )
    private val observations = mutableMapOf<String, Observation>()
    private var status = "not started"
    private var worker: Thread? = null
    private var activeSocket: Socket? = null

    @Synchronized fun start() {
        if (worker != null) return
        status = "connecting"
        observations.clear()
        worker = Thread(::observe, "diplay-navigation-mqtt").apply { isDaemon = true; start() }
    }

    @Synchronized fun stop() {
        worker?.interrupt()
        worker = null
        activeSocket?.close()
        activeSocket = null
        status = "stopped"
    }

    @Synchronized fun diagnosticReport(): String =
        "Navigation MQTT status=$status debug=${debugLogsEnabled()} (read-only; sender unknown)\n" +
            topics.joinToString("\n") { topic ->
                val last = observations[topic]
                if (last == null) "$topic count=0" else
                    "$topic count=${last.count} ageMs=${nanoTime() / 1_000_000 - last.seenMs} retained=${last.retained} ${last.summary}"
            }

    private fun observe() {
        val current = Thread.currentThread()
        var lastFailure = ""
        while (!current.isInterrupted) {
            val socket = synchronized(this) {
                if (worker !== current) return
                Socket().also { activeSocket = it }
            }
            try {
                socket.use {
                    it.connect(InetSocketAddress(EcarxMqtt.DEFAULT_HOST, port), 1500)
                    it.soTimeout = 3000
                    val output = it.getOutputStream()
                    val input = it.getInputStream()
                    output.write(EcarxMqtt.connectPacket("diplay-nav-observer"))
                    val ack = EcarxMqtt.readPacket(input, 2)
                    if (ack?.first != 0x20 || !ack.second.contentEquals(byteArrayOf(0, 0))) throw IOException("connack")
                    synchronized(this) { observations.clear() }
                    val body = ByteArrayOutputStream()
                    body.write(0)
                    body.write(1)
                    for (topic in topics) {
                        EcarxMqtt.mqttString(body, topic)
                        body.write(0)
                    }
                    output.write(EcarxMqtt.frame(0x82, body.toByteArray()))
                    var sequence = 0L
                    while (!current.isInterrupted) {
                        val (header, bytes) = EcarxMqtt.readPacket(input, 1024 * 1024) ?: throw IOException("ended-or-oversize")
                        if (header == 0x90) {
                            if (bytes.size != topics.size + 2 || bytes[0].toInt() != 0 || bytes[1].toInt() != 1)
                                throw IOException("suback")
                            val denied = topics.filterIndexed { i, _ -> bytes[i + 2].toInt() != 0 }
                            synchronized(this) { status = "subscribed=${topics.size - denied.size}/${topics.size}" }
                            report("mqtt subscribed=${topics.size - denied.size}/${topics.size} denied=${denied.joinToString(",").ifEmpty { "none" }}")
                            if (denied.size == topics.size) throw IOException("denied")
                            it.soTimeout = 0
                            lastFailure = ""
                        } else if (header ushr 4 == 3 && header and 6 == 0 && bytes.size >= 2) {
                            val end = 2 + ((bytes[0].toInt() and 255) shl 8) + (bytes[1].toInt() and 255)
                            if (end > bytes.size) throw IOException("topic-length")
                            val topic = String(bytes, 2, end - 2, Charsets.UTF_8)
                            if (topic !in topics) continue
                            val payload = String(bytes, end, bytes.size - end, Charsets.UTF_8)
                            val json = if (payload.isEmpty()) null else
                                try { JSONObject(payload) } catch (_: org.json.JSONException) { null }
                            val summary = if (payload.isEmpty()) "empty" else
                                if (json == null) "invalid-json" else summarize(topic, json)
                            sequence++
                            val retained = header and 1 != 0
                            val debug = debugLogsEnabled()
                            val line = synchronized(this) {
                                val now = nanoTime() / 1_000_000
                                val previous = observations[topic]
                                val count = (previous?.count ?: 0) + 1
                                val changed = previous == null ||
                                    mqttLogSummaryKey(topic, previous.summary) != mqttLogSummaryKey(topic, summary) ||
                                    previous.retained != retained
                                // Keep transitions immediately; coalesce repeated traffic without losing its count.
                                // Six topics at one line a minute each were still 12% of the v129 session log.
                                val due = previous == null || now - previous.reportedMs >= if (debug) 1000 else UNCHANGED_REPORT_MILLIS
                                val emit = changed || due
                                observations[topic] = Observation(summary, retained, count, now,
                                    if (emit) now else previous?.reportedMs ?: now,
                                    if (emit) count else previous?.reportedCount ?: 0)
                                // Raw payloads contain addresses and images; detail is still an allowlist.
                                val detail = if (emit && debug && json != null) when (topic) {
                                    "ihu/info/navigation/turn-by-turn/next-turn", "ihu/events/navigation/distance" ->
                                        " distance=" + (json.optString("distance").takeIf {
                                            it.matches(Regex("[0-9]{1,9}([.,][0-9]{1,3})?( ?(m|km))?"))
                                        } ?: "unknown")
                                    else -> ""
                                } else ""
                                if (emit) "mqtt rx=$sequence count=$count suppressed=${count - (previous?.reportedCount ?: 0) - 1} " +
                                    "gapMs=${previous?.let { now - it.seenMs } ?: -1} retained=$retained topic=$topic $summary$detail" else null
                            }
                            line?.let(report)
                        } else throw IOException("unexpected-packet")
                    }
                }
            } catch (e: IOException) {
                if (current.isInterrupted) return
                val failure = e.javaClass.simpleName + ":" + (e.message ?: "")
                synchronized(this) { status = "unavailable reason=$failure" }
                if (failure != lastFailure) report("mqtt unavailable reason=$failure retryMs=5000")
                lastFailure = failure
            }
            try { Thread.sleep(5000) } catch (_: InterruptedException) { return }
        }
    }

    private fun summarize(topic: String, json: JSONObject): String {
        fun present(key: String) = !json.isNull(key) && json.optString(key).isNotBlank()
        fun enum(key: String, vararg allowed: String): String =
            "$key=" + (json.optString(key).takeIf { it in allowed } ?: "unknown")
        return when (topic) {
            "dim/actions/navigation/map" -> enum("statusType", "always", "routeSet", "never")
            "dim/actions/navigation/fullscreenmap" -> enum("action", "switchMap")
            "dim/actions/navigation/ongoing" -> enum("action", "iconLeftClicked", "iconCenterClicked", "iconRightClicked")
            "ihu/events/navigation/fullscreenmap" -> enum("mapRequest", "showMap", "hideMap", "noRequest")
            "ihu/events/navigation/ongoing" -> enum("naviEvent", "ongoingNavi", "idle", "firstFrameReady", "naviAppOff") +
                " destinationPresent=${present("destination")}"
            "ihu/info/phoneprojection" -> enum("statusType", "androidAutoNavigation", "carPlayNavigation", "noProjection",
                "androidAutoMedia", "carPlayMedia")
            "ihu/heartbeat/navigation" -> "timestampPresent=${present("timestamp")} periodicity=${json.optInt("periodicity", -1)}"
            "ihu/events/navigation/distance" -> "distancePresent=${present("distance")}"
            "ihu/events/navigation/ETA" -> "etaPresent=${present("destinationTime")}"
            "ihu/info/navigation/turn-by-turn/next-turn" -> "distancePresent=${present("distance")} roadPresent=${present("address")}"
            "ihu/info/navigation/turn-by-turn/icon" -> "iconPresent=${present("iconId")} imagePresent=${present("imageData")}"
            else -> {
                val lanes = json.optJSONArray("lanes")
                val count = (0 until (lanes?.length() ?: 0)).count { !lanes?.optJSONObject(it)?.optString("icon").isNullOrBlank() }
                "lanes=$count"
            }
        }
    }
}

/**
 * Alternating "firstFrameReady" / "ongoingNavi" observations are routine refreshes of the
 * same active navigation state, not transitions. Preserve the unmodified last observation in
 * diagnosticReport() and preserve idle/off/destination changes as distinct states.
 */
internal fun mqttLogSummaryKey(topic: String, summary: String): String {
    if (topic != "ihu/events/navigation/ongoing") return summary
    return summary.replace("naviEvent=firstFrameReady", "naviEvent=active")
        .replace("naviEvent=ongoingNavi", "naviEvent=active")
}

/** Repeat interval for a topic whose state has not changed; changes are written at once. */
private const val UNCHANGED_REPORT_MILLIS = 300_000L
