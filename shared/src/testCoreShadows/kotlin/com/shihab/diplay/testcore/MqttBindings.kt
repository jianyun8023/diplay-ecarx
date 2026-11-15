@file:Suppress("UNCHECKED_CAST")

package com.shihab.diplay.testcore

import java.io.InputStream
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(className = "com.shihab.diplay.hud.NativeEcarxMqtt", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeEcarxMqtt {
    companion object {
        @JvmStatic @Implementation fun connectPacket(client: ByteArray): ByteArray =
            HostNative.call("hud.NativeEcarxMqtt", "connectPacket", client) as ByteArray

        @JvmStatic @Implementation fun publishPacket(id: Int, topic: ByteArray, payload: ByteArray): ByteArray =
            HostNative.call("hud.NativeEcarxMqtt", "publishPacket", id, topic, payload) as ByteArray

        @JvmStatic @Implementation fun frame(kind: Int, body: ByteArray): ByteArray =
            HostNative.call("hud.NativeEcarxMqtt", "frame", kind, body) as ByteArray

        @JvmStatic @Implementation fun mqttString(value: ByteArray): ByteArray =
            HostNative.call("hud.NativeEcarxMqtt", "mqttString", value) as ByteArray

        @JvmStatic @Implementation fun jsonEscape(value: String): String =
            HostNative.call("hud.NativeEcarxMqtt", "jsonEscape", value) as String

        @JvmStatic @Implementation fun ack(header: Int, body: ByteArray?, packetId: Int): Int =
            HostNative.call("hud.NativeEcarxMqtt", "ack", header, body, packetId) as Int

        @JvmStatic @Implementation fun readPacket(input: InputStream, maximum: Int): Array<Any?>? =
            HostNative.call("hud.NativeEcarxMqtt", "readPacket", input, maximum) as Array<Any?>?

        @JvmStatic @Implementation fun projectionCreate(): Array<Any?> =
            HostNative.call("hud.NativeEcarxMqtt", "projectionCreate") as Array<Any?>

        @JvmStatic @Implementation fun projectionUpdate(state: Array<Any?>, wanted: Boolean): Int =
            HostNative.call("hud.NativeEcarxMqtt", "projectionUpdate", state, wanted) as Int

        @JvmStatic @Implementation fun projectionPlan(state: Array<Any?>): String? =
            HostNative.call("hud.NativeEcarxMqtt", "projectionPlan", state) as String?

        @JvmStatic @Implementation fun projectionCommit(state: Array<Any?>, published: String) {
            HostNative.call("hud.NativeEcarxMqtt", "projectionCommit", state, published)
        }

        @JvmStatic @Implementation fun projectionPacketId(state: Array<Any?>): Int =
            HostNative.call("hud.NativeEcarxMqtt", "projectionPacketId", state) as Int

        @JvmStatic @Implementation fun projectionOngoing(state: Array<Any?>, on: Boolean, destination: String): String? =
            HostNative.call("hud.NativeEcarxMqtt", "projectionOngoing", state, on, destination) as String?

        @JvmStatic @Implementation fun projectionDescription(state: Array<Any?>, wantedOnly: Boolean): String =
            HostNative.call("hud.NativeEcarxMqtt", "projectionDescription", state, wantedOnly) as String

        @JvmStatic @Implementation fun projectionPayload(published: String): String =
            HostNative.call("hud.NativeEcarxMqtt", "projectionPayload", published) as String

        @JvmStatic @Implementation fun publishReply(header: Int, body: ByteArray?, packetId: Int): String =
            HostNative.call("hud.NativeEcarxMqtt", "publishReply", header, body, packetId) as String
        @JvmStatic @Implementation fun publishLoop(host: Any, topic: String, payload: String): String =
            HostNative.call("hud.NativeEcarxMqtt", "publishLoop", host, topic, payload) as String

        @JvmStatic @Implementation fun observerCreate(): Array<Any?> =
            HostNative.call("hud.NativeEcarxMqtt", "observerCreate") as Array<Any?>

        @JvmStatic @Implementation fun observerClear(state: Array<Any?>) {
            HostNative.call("hud.NativeEcarxMqtt", "observerClear", state)
        }

        @JvmStatic @Implementation fun observerReport(state: Array<Any?>, now: Long): String =
            HostNative.call("hud.NativeEcarxMqtt", "observerReport", state, now) as String

        @JvmStatic @Implementation fun observerConnack(header: Int, body: ByteArray?): Boolean =
            HostNative.call("hud.NativeEcarxMqtt", "observerConnack", header, body) as Boolean

        @JvmStatic @Implementation fun observerSubscribe(): ByteArray =
            HostNative.call("hud.NativeEcarxMqtt", "observerSubscribe") as ByteArray

        @JvmStatic @Implementation fun observerSuback(body: ByteArray): Array<String> =
            HostNative.call("hud.NativeEcarxMqtt", "observerSuback", body) as Array<String>

        @JvmStatic @Implementation fun observerPublication(header: Int, body: ByteArray): Array<Any?>? =
            HostNative.call("hud.NativeEcarxMqtt", "observerPublication", header, body) as Array<Any?>?

        @JvmStatic @Implementation fun observerObserve(state: Array<Any?>, topic: String, json: Any?, empty: Boolean, retained: Boolean, now: Long, debug: Boolean): String? =
            HostNative.call("hud.NativeEcarxMqtt", "observerObserve", state, topic, json, empty, retained, now, debug) as String?

        @JvmStatic @Implementation fun observerKey(topic: String, summary: String): String =
            HostNative.call("hud.NativeEcarxMqtt", "observerKey", topic, summary) as String

        @JvmStatic @Implementation fun observerRetryMillis(): Long =
            HostNative.call("hud.NativeEcarxMqtt", "observerRetryMillis") as Long
    }
}
