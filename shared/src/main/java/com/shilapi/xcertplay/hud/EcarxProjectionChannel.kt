package com.shilapi.xcertplay.hud

import java.net.Socket
import java.util.concurrent.Executors

/**
 * Publishes the cluster's navigation *presentation* state over the DIM broker: whether the phone is
 * the navigation source (`ihu/info/phoneprojection`) and whether a navigation is running
 * (`ihu/events/navigation/ongoing`). The live distance is handled separately by
 * [EcarxDimDistanceGuard]; both speak bytes through [EcarxMqtt].
 */
internal object EcarxProjectionChannel {
    private const val TAG = "DiPlay-ECARX-PROJ"

    /** The topic both projection publishers in the service use. */
    private const val TOPIC_PROJECTION = "ihu/info/phoneprojection"

    /**
     * `NavigationHandler.updateOngoingNavigation(boolean, String, boolean)` — the head unit's "a
     * navigation is running" event, payload `{"naviEvent":"ongoingNavi"|"idle", …}`.
     *
     * `notifyTurnByTurnStarted()` is the only XUI call on our side that could set this state, it
     * returns `void`, and nothing has ever confirmed it reached the service. Publishing the event
     * the service itself would have published makes the question answerable on the car.
     */
    private const val TOPIC_NAV_ONGOING = "ihu/events/navigation/ongoing"

    /** The single key in that topic's payload (`NavigationHandler`/`MediaHandler` both `put` it). */
    private const val KEY_STATUS = "statusType"

    private const val STATUS_CARPLAY_NAVIGATION = "carPlayNavigation"
    private const val STATUS_NONE = "noProjection"

    /** A local loopback broker answers or fails immediately; these are a liveness check, not a budget. */
    private const val CONNECT_TIMEOUT_MS = 1_500
    private const val READ_TIMEOUT_MS = 1_500

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

    private val sink = EcarxReportSink(TAG)

    /**
     * Every publish runs here rather than on the caller's thread.
     *
     * The callers are [EcarxHudBridge] tickers, which hold the bridge lock around the call; a broker
     * that has gone away costs a connect timeout per attempt, which is not something to spend inside
     * a guidance frame. One thread also keeps the frames in the order the state changed.
     */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diplay-ecarx-projection").apply { isDaemon = true }
    }

    /** Set when a change is queued for [worker]; a burst of changes collapses into one drain. */
    private var queued = false

    /** Where this channel's lines go besides logcat; same seam as the HUD bridge. Idempotent. */
    fun attachReport(sink: (String) -> Unit) = this.sink.attach(sink)

    /**
     * The navigation axis. Called when guidance starts and stops.
     *
     * This is the half that answers the car's own complaint — *the arrow only appears while the
     * 仪表投屏 switch is on* — by pointing the cluster's navigation source at the phone without
     * mirroring a window onto it.
     */
    fun setNavigationProjection(on: Boolean): String = update { navigationWanted = on }

    /**
     * Tells the cluster that a navigation is running (or has stopped), on the event topic the head
     * unit's own navigation app uses.
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
            sink.line("navi ongoing ${if (on) "on" else "off"} -> $outcome")
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
            EcarxMqtt.jsonEscape(destination) + "\",\"iconLeft\":\"naviNewIcon\",\"iconCenter\":\"cancelNaviIcon\"," +
            "\"iconRight\":\"unmuteIcon\",\"destinationFlagIcon\":\"destinationFlagIcon\"}"
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
        sink.line("projection $frame -> $outcome")
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
     * overwrite the first.
     */
    private fun planLocked(): String? {
        val wanted = desiredLocked()
        if (wanted == STATUS_NONE && !touched) return null
        if (wanted == published) return null
        return wanted
    }

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
            val outcome = EcarxMqtt.publish(connection, nextPacketId(), topic, payload)
            if (outcome == "ok") return "ok"
            last = outcome
            dropLocked()
        }
        return last
    }

    private fun connectLocked(): Socket? {
        val connection = EcarxMqtt.connect(
            PUBLISH_CLIENT_ID,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
        )
        if (connection == null) {
            lastConnectReason = "connect failed"
            return null
        }
        socket = connection
        lastConnectReason = "ok"
        return connection
    }

    private fun dropLocked() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun nextPacketId(): Int {
        packetId = packetId % 0xFFFF + 1
        return packetId
    }
}
