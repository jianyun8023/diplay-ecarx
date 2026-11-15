package com.shihab.diplay.hud

import com.shihab.diplay.airplay.NativeMedia
import java.io.InputStream

/** Required Rust MQTT rules. Opaque value tokens are serialized by their host's existing monitor. */
internal object NativeEcarxMqtt {
    init { NativeMedia.requireAvailable() }
    @JvmStatic external fun connectPacket(client: ByteArray): ByteArray
    @JvmStatic external fun publishPacket(id: Int, topic: ByteArray, payload: ByteArray): ByteArray
    @JvmStatic external fun frame(kind: Int, body: ByteArray): ByteArray
    @JvmStatic external fun mqttString(value: ByteArray): ByteArray
    @JvmStatic external fun jsonEscape(value: String): String
    @JvmStatic external fun ack(header: Int, body: ByteArray?, packetId: Int): Int
    @JvmStatic external fun readPacket(input: InputStream, maximum: Int): Array<Any?>?
    @JvmStatic external fun projectionCreate(): Array<Any?>
    @JvmStatic external fun projectionUpdate(state: Array<Any?>, wanted: Boolean): Int
    @JvmStatic external fun projectionPlan(state: Array<Any?>): String?
    @JvmStatic external fun projectionCommit(state: Array<Any?>, published: String)
    @JvmStatic external fun projectionPacketId(state: Array<Any?>): Int
    @JvmStatic external fun projectionOngoing(state: Array<Any?>, on: Boolean, destination: String): String?
    @JvmStatic external fun projectionDescription(state: Array<Any?>, wantedOnly: Boolean): String
    @JvmStatic external fun projectionPayload(published: String): String
    @JvmStatic external fun publishReply(header: Int, body: ByteArray?, packetId: Int): String
    @JvmStatic external fun publishLoop(host: Any, topic: String, payload: String): String
    @JvmStatic external fun observerCreate(): Array<Any?>
    @JvmStatic external fun observerClear(state: Array<Any?>)
    @JvmStatic external fun observerReport(state: Array<Any?>, now: Long): String
    @JvmStatic external fun observerConnack(header: Int, body: ByteArray?): Boolean
    @JvmStatic external fun observerSubscribe(): ByteArray
    @JvmStatic external fun observerSuback(body: ByteArray): Array<String>
    @JvmStatic external fun observerPublication(header: Int, body: ByteArray): Array<Any?>?
    @JvmStatic external fun observerObserve(state: Array<Any?>, topic: String, json: Any?, empty: Boolean,
        retained: Boolean, now: Long, debug: Boolean): String?
    @JvmStatic external fun observerKey(topic: String, summary: String): String
    @JvmStatic external fun observerRetryMillis(): Long

    data class Subscription(val status: String, val report: String, val rejected: Boolean)
    fun subscription(body: ByteArray): Subscription = observerSuback(body).let {
        Subscription(it[0], it[1], it[2].toBoolean())
    }
    data class Publication(val topic: String, val payload: String, val retained: Boolean)
    fun publication(header: Int, body: ByteArray): Publication? = observerPublication(header, body)?.let {
        Publication(it[0] as String, String(it[1] as ByteArray, Charsets.UTF_8), it[2] as Boolean)
    }
}
