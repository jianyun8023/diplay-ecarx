package com.shihab.diplay.hud

import java.net.Socket
import java.util.concurrent.Executors

/**
 * Publishes the cluster's navigation *presentation* state over the DIM broker: whether the phone is
 * the navigation source (`ihu/info/phoneprojection`) and whether a navigation is running
 * (`ihu/events/navigation/ongoing`), using [EcarxMqtt] for packet framing.
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
    private val state = NativeEcarxMqtt.projectionCreate()
    private val health = LongArray(12)
    private var lastConnectReason = "not attempted"

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

    /** Where this channel's lines go besides logcat; same seam as the HUD bridge. Idempotent. */
    fun attachReport(sink: (String) -> Unit) = this.sink.attach(sink)

    /**
     * The navigation axis. Called when guidance starts and stops.
     *
     * This is the half that answers the car's own complaint — *the arrow only appears while the
     * 仪表投屏 switch is on* — by pointing the cluster's navigation source at the phone without
     * mirroring a window onto it.
     */
    fun setNavigationProjection(on: Boolean): String = synchronized(lock) {
        val action = NativeEcarxMqtt.projectionUpdate(state, on)
        val wanted = NativeEcarxMqtt.projectionDescription(state, true)
        if (action == 2) worker.execute { flush() }
        "projection=$wanted" + if (action == 0) "" else " (queued)"
    }

    /**
     * Tells the cluster that a navigation is running (or has stopped), on the event topic the head
     * unit's own navigation app uses.
     *
     * `destination` is the road text the cluster shows beside its own navigation icons. Empty is
     * valid: the stock handler is called with an empty address when no destination string exists.
     */
    fun setNavigationOngoing(on: Boolean, destination: String): String = synchronized(lock) {
        val payload = NativeEcarxMqtt.projectionOngoing(state, on, destination)
            ?: return@synchronized "navi ongoing unchanged"
        worker.execute {
            val outcome = synchronized(lock) { publishRawLocked(TOPIC_NAV_ONGOING, payload) }
            sink.line("navi ongoing ${if (on) "on" else "off"} -> $outcome")
        }
        "navi ongoing ${if (on) "on" else "off"} (queued)"
    }

    /** One line of state for the settings diagnostics block. */
    fun state(): String = synchronized(lock) {
        NativeEcarxMqtt.projectionDescription(state, false) + " broker=" + lastConnectReason +
            "\nMQTT publisher processTotals " + NativeEcarxMqtt.health(health, 0)
    }

    /**
     * Runs on [worker]. Publishes whatever the state is **when it runs**, not what it was when the
     * frame was queued: a start and a stop that arrive while a publish is in flight must collapse to
     * one frame carrying the *later* value.
     */
    private fun flush() {
        val frame = synchronized(lock) { NativeEcarxMqtt.projectionPlan(state) } ?: return
        val outcome = synchronized(lock) { publishLocked(frame) }
        sink.line("projection $frame -> $outcome")
        if (outcome != "ok") return
        synchronized(lock) {
            NativeEcarxMqtt.projectionCommit(state, frame)
        }
    }

    private fun publishLocked(statusType: String): String =
        publishRawLocked(TOPIC_PROJECTION, NativeEcarxMqtt.projectionPayload(statusType))

    /** One QoS-1 publish on an already-known topic, with the PUBACK as the only delivery evidence. */
    private fun publishRawLocked(topic: String, payload: String): String =
        NativeEcarxMqtt.publishLoop(this, topic, payload)

    private fun mqttConnect(): Boolean = (socket ?: connectLocked()) != null
    private fun mqttConnectReason(): String = lastConnectReason
    private fun mqttPublish(topic: String, payload: String): String =
        EcarxMqtt.publish(checkNotNull(socket), NativeEcarxMqtt.projectionPacketId(state), topic, payload, health)
    private fun mqttDrop() = dropLocked()

    private fun connectLocked(): Socket? {
        NativeEcarxMqtt.health(health, 1)
        val connection = EcarxMqtt.connect(
            PUBLISH_CLIENT_ID,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
        )
        if (connection == null) {
            NativeEcarxMqtt.health(health, 3)
            lastConnectReason = "connect failed"
            return null
        }
        socket = connection
        NativeEcarxMqtt.health(health, 2)
        lastConnectReason = "ok"
        return connection
    }

    private fun dropLocked() {
        runCatching { socket?.close() }
        socket = null
    }

}
