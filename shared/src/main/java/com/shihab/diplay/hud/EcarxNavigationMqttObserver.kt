package com.shihab.diplay.hud

import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** A separate clean session subscribes at QoS 0. Observation never sends navigation commands. */
internal class EcarxNavigationMqttObserver(
    private val report: (String) -> Unit,
    private val port: Int = EcarxMqtt.DEFAULT_PORT,
    private val debugLogsEnabled: () -> Boolean = { false },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val observations = NativeEcarxMqtt.observerCreate()
    private val health = LongArray(12)
    private var status = "not started"
    private var worker: Thread? = null
    private var activeSocket: Socket? = null

    @Synchronized fun start() {
        if (worker != null) return
        status = "connecting"
        NativeEcarxMqtt.observerClear(observations)
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
            "MQTT observer lifetimeTotals ${NativeEcarxMqtt.health(health, 0)}\n" +
            NativeEcarxMqtt.observerReport(observations, nanoTime() / 1_000_000)

    private fun observe() {
        val current = Thread.currentThread()
        var lastFailure = ""
        while (!current.isInterrupted) {
            val socket = synchronized(this) {
                if (worker !== current) return
                NativeEcarxMqtt.health(health, 1)
                Socket().also { activeSocket = it }
            }
            var connected = false
            try {
                socket.use {
                    it.connect(InetSocketAddress(EcarxMqtt.DEFAULT_HOST, port), 1500)
                    it.soTimeout = 3000
                    val output = it.getOutputStream()
                    val input = it.getInputStream()
                    output.write(EcarxMqtt.connectPacket("diplay-nav-observer"))
                    val ack = EcarxMqtt.readPacket(input, 2)
                    if (!NativeEcarxMqtt.observerConnack(ack?.first ?: -1, ack?.second)) throw IOException("connack")
                    synchronized(this) {
                        NativeEcarxMqtt.health(health, 2)
                        NativeEcarxMqtt.observerClear(observations)
                    }
                    connected = true
                    output.write(NativeEcarxMqtt.observerSubscribe())
                    while (!current.isInterrupted) {
                        val (header, bytes) = EcarxMqtt.readPacket(input, 1024 * 1024) ?: throw IOException("ended-or-oversize")
                        if (header == 0x90) {
                            val subscription = synchronized(this) { NativeEcarxMqtt.subscription(bytes, health) }
                            synchronized(this) { status = subscription.status }
                            report(subscription.report)
                            if (subscription.rejected) throw IOException("denied")
                            it.soTimeout = 0
                            lastFailure = ""
                        } else {
                            val publication = NativeEcarxMqtt.publication(header, bytes) ?: continue
                            val json = if (publication.payload.isEmpty()) null else
                                try { JSONObject(publication.payload) } catch (_: org.json.JSONException) { null }
                            val debug = debugLogsEnabled()
                            val line = synchronized(this) {
                                NativeEcarxMqtt.observerObserve(observations, publication.topic, json,
                                    publication.payload.isEmpty(), publication.retained, nanoTime() / 1_000_000, debug)
                            }
                            line?.let(report)
                        }
                    }
                }
            } catch (e: IOException) {
                if (current.isInterrupted) return
                val failure = e.javaClass.simpleName + ":" + (e.message ?: "")
                synchronized(this) {
                    NativeEcarxMqtt.health(health, if (connected) 8 else 3)
                    if (e is SocketTimeoutException) NativeEcarxMqtt.health(health, 9)
                    status = "unavailable reason=$failure"
                }
                if (failure != lastFailure) report("mqtt unavailable reason=$failure retryMs=${NativeEcarxMqtt.observerRetryMillis()}")
                lastFailure = failure
            }
            try { Thread.sleep(NativeEcarxMqtt.observerRetryMillis()) } catch (_: InterruptedException) { return }
        }
    }

}

internal fun mqttLogSummaryKey(topic: String, summary: String): String = NativeEcarxMqtt.observerKey(topic, summary)
