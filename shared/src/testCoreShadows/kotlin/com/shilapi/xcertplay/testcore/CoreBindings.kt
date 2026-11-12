@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "UNCHECKED_CAST")

package com.shilapi.xcertplay.testcore

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.hud.AppleManeuver
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Bootstrap-valued bindings execute the real host core; application DTOs are rebuilt locally. */
@Implements(className = "com.shilapi.xcertplay.airplay.NativeAirPlayNegotiation", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeAirPlayNegotiation {
    companion object {
        @JvmStatic @Implementation fun create(capabilities: Int): LongArray =
            HostNative.call("airplay.NativeAirPlayNegotiation", "create", capabilities) as LongArray

        @JvmStatic @Implementation fun query(state: LongArray, field: Int): Long =
            HostNative.call("airplay.NativeAirPlayNegotiation", "query", state, field) as Long

        @JvmStatic @Implementation fun event(state: LongArray, event: Int, value: Int): Boolean =
            HostNative.call("airplay.NativeAirPlayNegotiation", "event", state, event, value) as Boolean

        @JvmStatic @Implementation fun route(state: LongArray, method: String, path: String, bodyBytes: Int): Int =
            HostNative.call("airplay.NativeAirPlayNegotiation", "route", state, method, path, bodyBytes) as Int

        @JvmStatic @Implementation fun streamAction(state: LongArray, type: Int): Int =
            HostNative.call("airplay.NativeAirPlayNegotiation", "streamAction", state, type) as Int

        @JvmStatic @Implementation fun accepted(state: LongArray, type: Int): Int =
            HostNative.call("airplay.NativeAirPlayNegotiation", "accepted", state, type) as Int

        @JvmStatic @Implementation fun streams(state: LongArray): IntArray =
            HostNative.call("airplay.NativeAirPlayNegotiation", "streams", state) as IntArray

        @JvmStatic @Implementation fun timingAction(state: LongArray, peerPort: Int, hasPeer: Boolean): Int =
            HostNative.call("airplay.NativeAirPlayNegotiation", "timingAction", state, peerPort, hasPeer) as Int

        @JvmStatic @Implementation fun prepareTeardown(state: LongArray, types: IntArray?): Int =
            HostNative.call("airplay.NativeAirPlayNegotiation", "prepareTeardown", state, types) as Int

        @JvmStatic @Implementation fun finishTeardown(state: LongArray, all: Boolean): Boolean =
            HostNative.call("airplay.NativeAirPlayNegotiation", "finishTeardown", state, all) as Boolean

        @JvmStatic @Implementation fun nightResponse(state: LongArray, sequence: Int): Int =
            HostNative.call("airplay.NativeAirPlayNegotiation", "nightResponse", state, sequence) as Int

        @JvmStatic @Implementation fun navigation(state: LongArray, event: Int, value: Long, present: Boolean): Int =
            HostNative.call("airplay.NativeAirPlayNegotiation", "navigation", state, event, value, present) as Int

        @JvmStatic @Implementation fun features(capabilities: Int, proposed: Array<String>): Array<String> =
            HostNative.call("airplay.NativeAirPlayNegotiation", "features", capabilities, proposed) as Array<String>

        @JvmStatic @Implementation fun teardownTypes(types: IntArray, ids: LongArray, present: BooleanArray): IntArray? =
            HostNative.call("airplay.NativeAirPlayNegotiation", "teardownTypes", types, ids, present) as IntArray?
    }
}

@Implements(className = "com.shilapi.xcertplay.airplay.NativeTouchSlots", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeTouchSlots {
    companion object {
        @JvmStatic @Implementation fun create(slots: Int): Long =
            HostNative.call("airplay.NativeTouchSlots", "create", slots) as Long

        @JvmStatic @Implementation fun destroy(handle: Long) { HostNative.call("airplay.NativeTouchSlots", "destroy", handle) }

        @JvmStatic @Implementation fun assignReport(handle: Long, ids: IntArray, xs: DoubleArray, ys: DoubleArray, down: BooleanArray, widthPixels: Int, heightPixels: Int): ByteArray =
            HostNative.call("airplay.NativeTouchSlots", "assignReport", handle, ids, xs, ys, down, widthPixels, heightPixels) as ByteArray
    }
}

@Implements(className = "com.shilapi.xcertplay.airplay.NativeBplist", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeBplist {
    companion object {
        @JvmStatic @Implementation fun encode(root: Any?): ByteArray =
            HostNative.call("airplay.NativeBplist", "encode", root) as ByteArray

        @JvmStatic @Implementation fun decode(bytes: ByteArray): Any? =
            HostNative.call("airplay.NativeBplist", "decode", bytes) as Any?
    }
}

@Implements(className = "com.shilapi.xcertplay.airplay.NativeControlCipher", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeControlCipher {
    companion object {
        @JvmStatic @Implementation fun create(readKey: ByteArray, writeKey: ByteArray): Long =
            HostNative.call("airplay.NativeControlCipher", "create", readKey, writeKey) as Long

        @JvmStatic @Implementation fun encrypt(handle: Long, plaintext: ByteArray): ByteArray =
            HostNative.call("airplay.NativeControlCipher", "encrypt", handle, plaintext) as ByteArray

        @JvmStatic @Implementation fun decrypt(handle: Long, buffer: ByteArray): ControlCipher.Decrypted =
            CoreDtos.decrypted(HostNative.call("airplay.NativeControlCipher", "decrypt", handle, buffer)!!)

        @JvmStatic @Implementation fun destroy(handle: Long) { HostNative.call("airplay.NativeControlCipher", "destroy", handle) }
    }
}

@Implements(className = "com.shilapi.xcertplay.airplay.NativeCrypto", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeCrypto {
    companion object {
        @JvmStatic @Implementation fun x25519Generate(): Array<ByteArray> =
            HostNative.call("airplay.NativeCrypto", "x25519Generate") as Array<ByteArray>

        @JvmStatic @Implementation fun x25519Shared(privateKey: ByteArray, peer: ByteArray): ByteArray =
            HostNative.call("airplay.NativeCrypto", "x25519Shared", privateKey, peer) as ByteArray

        @JvmStatic @Implementation fun ed25519Generate(): Array<ByteArray> =
            HostNative.call("airplay.NativeCrypto", "ed25519Generate") as Array<ByteArray>

        @JvmStatic @Implementation fun ed25519Sign(seed: ByteArray, data: ByteArray): ByteArray =
            HostNative.call("airplay.NativeCrypto", "ed25519Sign", seed, data) as ByteArray

        @JvmStatic @Implementation fun ed25519Verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
            HostNative.call("airplay.NativeCrypto", "ed25519Verify", publicKey, data, signature) as Boolean

        @JvmStatic @Implementation fun hkdfSha512(input: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
            HostNative.call("airplay.NativeCrypto", "hkdfSha512", input, salt, info, length) as ByteArray

        @JvmStatic @Implementation fun sha512(parts: Array<out ByteArray>): ByteArray =
            HostNative.call("airplay.NativeCrypto", "sha512", parts) as ByteArray

        @JvmStatic @Implementation fun chachaSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
            HostNative.call("airplay.NativeCrypto", "chachaSeal", key, nonce, plaintext, aad) as ByteArray

        @JvmStatic @Implementation fun ecdsaDerToRaw(der: ByteArray): ByteArray =
            HostNative.call("airplay.NativeCrypto", "ecdsaDerToRaw", der) as ByteArray

        @JvmStatic @Implementation fun ecdsaRawToDer(raw: ByteArray): ByteArray =
            HostNative.call("airplay.NativeCrypto", "ecdsaRawToDer", raw) as ByteArray
    }
}

@Implements(className = "com.shilapi.xcertplay.airplay.NativeMedia", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeMedia {
    @Implementation fun getAvailable(): Boolean = HostNative.requireCore()
    @Implementation fun requireAvailable() { HostNative.requireCore() }
    @Implementation fun getLoadError(): Throwable? { HostNative.requireCore(); return null }

    companion object {
        @JvmStatic @Implementation fun hidTouchReport(ids: IntArray, xy: DoubleArray, down: BooleanArray): ByteArray =
            HostNative.call("airplay.NativeMedia", "hidTouchReport", ids, xy, down) as ByteArray

        @JvmStatic @Implementation fun hidKnobReport(select: Boolean, home: Boolean, back: Boolean, x: Int, y: Int, wheel: Int): ByteArray =
            HostNative.call("airplay.NativeMedia", "hidKnobReport", select, home, back, x, y, wheel) as ByteArray

        @JvmStatic @Implementation fun hidIndexReport(index: Int): ByteArray =
            HostNative.call("airplay.NativeMedia", "hidIndexReport", index) as ByteArray

        @JvmStatic @Implementation fun hidDescriptor(kind: Int, xMax: Int, yMax: Int): ByteArray =
            HostNative.call("airplay.NativeMedia", "hidDescriptor", kind, xMax, yMax) as ByteArray

        @JvmStatic @Implementation fun wirelessRunCreate(): Long =
            HostNative.call("airplay.NativeMedia", "wirelessRunCreate") as Long

        @JvmStatic @Implementation fun wirelessRunQuery(token: Long, field: Int): Int =
            HostNative.call("airplay.NativeMedia", "wirelessRunQuery", token, field) as Int

        @JvmStatic @Implementation fun wirelessRunTransition(token: Long, event: Int): Long =
            HostNative.call("airplay.NativeMedia", "wirelessRunTransition", token, event) as Long

        @JvmStatic @Implementation fun chachaOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, aadOffset: Int, aadLength: Int, input: ByteArray, inputOffset: Int, inputLength: Int, out: ByteArray, outOffset: Int): Int =
            HostNative.call("airplay.NativeMedia", "chachaOpen", key, nonce, aad, aadOffset, aadLength, input, inputOffset, inputLength, out, outOffset) as Int

        @JvmStatic @Implementation fun vehicleStatusPayload(rangeKm: Int, rangeWarning: Boolean, batteryPercent: Double, currentChargeWh: Long, maxChargeWh: Long, energyMask: Int, maxRangeKm: Int, charging: Boolean): ByteArray? =
            HostNative.call("airplay.NativeMedia", "vehicleStatusPayload", rangeKm, rangeWarning, batteryPercent, currentChargeWh, maxChargeWh, energyMask, maxRangeKm, charging) as ByteArray?
    }
}

@Implements(className = "com.shilapi.xcertplay.airplay.NativeRtsp", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeRtsp {
    companion object {
        @JvmStatic @Implementation fun parse(buffer: ByteArray): RtspMessage.Parsed =
            CoreDtos.rtsp(HostNative.call("airplay.NativeRtsp", "parse", buffer)!!)

        @JvmStatic @Implementation fun response(protocol: String, status: Int, reason: String?, cseq: String?, headers: Array<String>, body: ByteArray): ByteArray =
            HostNative.call("airplay.NativeRtsp", "response", protocol, status, reason, cseq, headers, body) as ByteArray
    }
}

@Implements(className = "com.shilapi.xcertplay.airplay.NativeNtpTiming", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeNtpTiming {
    companion object {
        @JvmStatic @Implementation fun create(monoNanos: Long, wallMillis: Long): LongArray =
            HostNative.call("airplay.NativeNtpTiming", "create", monoNanos, wallMillis) as LongArray

        @JvmStatic @Implementation fun synced(token: LongArray, monoNanos: Long): ByteArray =
            HostNative.call("airplay.NativeNtpTiming", "synced", token, monoNanos) as ByteArray

        @JvmStatic @Implementation fun request(token: LongArray, monoNanos: Long): ByteArray =
            HostNative.call("airplay.NativeNtpTiming", "request", token, monoNanos) as ByteArray

        @JvmStatic @Implementation fun action(message: ByteArray, fromPeer: Boolean): Int =
            HostNative.call("airplay.NativeNtpTiming", "action", message, fromPeer) as Int

        @JvmStatic @Implementation fun reply(token: LongArray, message: ByteArray, receivedNanos: Long, sentNanos: Long): ByteArray =
            HostNative.call("airplay.NativeNtpTiming", "reply", token, message, receivedNanos, sentNanos) as ByteArray

        @JvmStatic @Implementation fun sample(token: LongArray, message: ByteArray, monoNanos: Long) { HostNative.call("airplay.NativeNtpTiming", "sample", token, message, monoNanos) }
    }
}

@Implements(className = "com.shilapi.xcertplay.hud.NativeClusterArrow", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeClusterArrow {
    companion object {
        @JvmStatic @Implementation fun amapToEcarx(iconId: Int, aroundNum: Int): Int =
            HostNative.call("hud.NativeClusterArrow", "amapToEcarx", iconId, aroundNum) as Int

        @JvmStatic @Implementation fun ecarxToAmap(arrowId: Int): Int =
            HostNative.call("hud.NativeClusterArrow", "ecarxToAmap", arrowId) as Int

        @JvmStatic @Implementation fun appleToAmap(type: Int, drivingSide: Int): Int =
            HostNative.call("hud.NativeClusterArrow", "appleToAmap", type, drivingSide) as Int

        @JvmStatic @Implementation fun arrowId(appleType: Int, drivingSide: Int): Int =
            HostNative.call("hud.NativeClusterArrow", "arrowId", appleType, drivingSide) as Int
    }
}

@Implements(className = "com.shilapi.xcertplay.hud.NativeDayNight", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeDayNight {
    companion object {
        @JvmStatic @Implementation fun fromAdaptapi(value: Int): Int =
            HostNative.call("hud.NativeDayNight", "fromAdaptapi", value) as Int

        @JvmStatic @Implementation fun fromAmap(state: Int): Int =
            HostNative.call("hud.NativeDayNight", "fromAmap", state) as Int

        @JvmStatic @Implementation fun fromTwilight(value: Int): Int =
            HostNative.call("hud.NativeDayNight", "fromTwilight", value) as Int
    }
}

@Implements(className = "com.shilapi.xcertplay.hud.NativeLaneIcon", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeLaneIcon {
    companion object {
        @JvmStatic @Implementation fun forLane(status: Int, angles: IntArray, highlightAngle: Int, hasHighlight: Boolean): Int =
            HostNative.call("hud.NativeLaneIcon", "forLane", status, angles, highlightAngle, hasHighlight) as Int
    }
}

@Implements(className = "com.shilapi.xcertplay.hud.NativeRouteGuidance", isInAndroidSdk = false, callNativeMethodsByDefault = true)
internal class ShadowNativeRouteGuidance {
    companion object {
        @JvmStatic @Implementation fun create(staleNs: Long, emptyNs: Long, keepAcrossNoRoute: Boolean): Long =
            HostNative.call("hud.NativeRouteGuidance", "create", staleNs, emptyNs, keepAcrossNoRoute) as Long

        @JvmStatic @Implementation fun accept(handle: Long, messageId: Int, payload: ByteArray, nowNs: Long, wallSeconds: Long, liveSession: Boolean): Int =
            HostNative.call("hud.NativeRouteGuidance", "accept", handle, messageId, payload, nowNs, wallSeconds, liveSession) as Int

        @JvmStatic @Implementation fun current(handle: Long, nowNs: Long, liveSession: Boolean, dim: Boolean): AppleManeuver? =
            HostNative.call("hud.NativeRouteGuidance", "current", handle, nowNs, liveSession, dim)?.let(CoreDtos::maneuver)

        @JvmStatic @Implementation fun query(handle: Long, field: Int): Long =
            HostNative.call("hud.NativeRouteGuidance", "query", handle, field) as Long

        @JvmStatic @Implementation fun phoneStateAge(handle: Long, nowNs: Long): Long =
            HostNative.call("hud.NativeRouteGuidance", "phoneStateAge", handle, nowNs) as Long

        @JvmStatic @Implementation fun expired(handle: Long, nowNs: Long, liveSession: Boolean): Boolean =
            HostNative.call("hud.NativeRouteGuidance", "expired", handle, nowNs, liveSession) as Boolean

        @JvmStatic @Implementation fun text(handle: Long, field: Int, nowNs: Long, liveSession: Boolean): String =
            HostNative.call("hud.NativeRouteGuidance", "text", handle, field, nowNs, liveSession) as String

        @JvmStatic @Implementation fun clear(handle: Long, reason: String): Boolean =
            HostNative.call("hud.NativeRouteGuidance", "clear", handle, reason) as Boolean

        @JvmStatic @Implementation fun destroy(handle: Long) { HostNative.call("hud.NativeRouteGuidance", "destroy", handle) }
    }
}

@Implements(className = "com.shilapi.xcertplay.iap2.wire.NativeIap2Csm", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeIap2Csm {
    companion object {
        @JvmStatic @Implementation fun create(): Long =
            HostNative.call("iap2.wire.NativeIap2Csm", "create") as Long

        @JvmStatic @Implementation fun destroy(handle: Long) { HostNative.call("iap2.wire.NativeIap2Csm", "destroy", handle) }

        @JvmStatic @Implementation fun offer(handle: Long, chunk: ByteArray): Array<Iap2Frame> =
            (HostNative.call("iap2.wire.NativeIap2Csm", "offer", handle, chunk) as Array<*>).map { CoreDtos.frame(it!!) }.toTypedArray()

        @JvmStatic @Implementation fun encode(parameter: Boolean, id: Int, payload: ByteArray): ByteArray =
            HostNative.call("iap2.wire.NativeIap2Csm", "encode", parameter, id, payload) as ByteArray

        @JvmStatic @Implementation fun split(frame: ByteArray, chunkSize: Int): Array<ByteArray> =
            HostNative.call("iap2.wire.NativeIap2Csm", "split", frame, chunkSize) as Array<ByteArray>
    }
}

@Implements(className = "com.shilapi.xcertplay.iap2.wire.NativeIap2Body", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeIap2Body {
    companion object {
        @JvmStatic @Implementation fun validateBuilder(ids: IntArray, kinds: IntArray, specs: IntArray): IntArray? =
            HostNative.call("iap2.wire.NativeIap2Body", "validateBuilder", ids, kinds, specs) as IntArray?

        @JvmStatic @Implementation fun encode(ids: IntArray, payloads: Array<ByteArray>): ByteArray =
            HostNative.call("iap2.wire.NativeIap2Body", "encode", ids, payloads) as ByteArray

        @JvmStatic @Implementation fun parse(encoded: ByteArray): Array<Iap2Parameter> =
            (HostNative.call("iap2.wire.NativeIap2Body", "parse", encoded) as Array<*>).map { CoreDtos.parameter(it!!) }.toTypedArray()
    }
}

@Implements(className = "com.shilapi.xcertplay.iap2.wire.NativeIap2Wire", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeIap2Wire {
    companion object {
        @JvmStatic @Implementation fun encodeScalar(kind: Int, value: Long): ByteArray =
            HostNative.call("iap2.wire.NativeIap2Wire", "encodeScalar", kind, value) as ByteArray

        @JvmStatic @Implementation fun readScalar(kind: Int, bytes: ByteArray, offset: Int, fieldId: Int): Long =
            HostNative.call("iap2.wire.NativeIap2Wire", "readScalar", kind, bytes, offset, fieldId) as Long

        @JvmStatic @Implementation fun encodeU16List(values: IntArray): ByteArray =
            HostNative.call("iap2.wire.NativeIap2Wire", "encodeU16List", values) as ByteArray

        @JvmStatic @Implementation fun readU16List(bytes: ByteArray): IntArray =
            HostNative.call("iap2.wire.NativeIap2Wire", "readU16List", bytes) as IntArray

        @JvmStatic @Implementation fun joinStrings(values: Array<ByteArray>): ByteArray =
            HostNative.call("iap2.wire.NativeIap2Wire", "joinStrings", values) as ByteArray

        @JvmStatic @Implementation fun stringRanges(bytes: ByteArray, multiple: Boolean): IntArray =
            HostNative.call("iap2.wire.NativeIap2Wire", "stringRanges", bytes, multiple) as IntArray

        @JvmStatic @Implementation fun requireVoid(id: Int, bytes: ByteArray) { HostNative.call("iap2.wire.NativeIap2Wire", "requireVoid", id, bytes) }
    }
}

@Implements(className = "com.shilapi.xcertplay.orchestration.NativeWirelessRecovery", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeWirelessRecovery {
    companion object {
        @JvmStatic @Implementation fun budgetCreate(): Long =
            HostNative.call("orchestration.NativeWirelessRecovery", "budgetCreate") as Long

        @JvmStatic @Implementation fun budgetAdvance(token: Long, stage: Int): Long =
            HostNative.call("orchestration.NativeWirelessRecovery", "budgetAdvance", token, stage) as Long

        @JvmStatic @Implementation fun budgetQuery(token: Long, field: Int): Int =
            HostNative.call("orchestration.NativeWirelessRecovery", "budgetQuery", token, field) as Int

        @JvmStatic @Implementation fun attemptCreate(): Long =
            HostNative.call("orchestration.NativeWirelessRecovery", "attemptCreate") as Long

        @JvmStatic @Implementation fun attemptUpdate(token: Long, event: Int, phase: Int): Long =
            HostNative.call("orchestration.NativeWirelessRecovery", "attemptUpdate", token, event, phase) as Long

        @JvmStatic @Implementation fun attemptQuery(token: Long, field: Int): Int =
            HostNative.call("orchestration.NativeWirelessRecovery", "attemptQuery", token, field) as Int

        @JvmStatic @Implementation fun classify(facts: Int): Int =
            HostNative.call("orchestration.NativeWirelessRecovery", "classify", facts) as Int

        @JvmStatic @Implementation fun attemptFailure(token: Long, facts: Int): Int =
            HostNative.call("orchestration.NativeWirelessRecovery", "attemptFailure", token, facts) as Int
    }
}

@Implements(className = "com.shilapi.xcertplay.transport.NativeControlDeadline", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeControlDeadline {
    companion object {
        @JvmStatic @Implementation fun create(timeoutMillis: Long, startedNanos: Long): LongArray =
            HostNative.call("transport.NativeControlDeadline", "create", timeoutMillis, startedNanos) as LongArray

        @JvmStatic @Implementation fun authenticated(token: LongArray): LongArray =
            HostNative.call("transport.NativeControlDeadline", "authenticated", token) as LongArray

        @JvmStatic @Implementation fun needsClock(token: LongArray): Boolean =
            HostNative.call("transport.NativeControlDeadline", "needsClock", token) as Boolean

        @JvmStatic @Implementation fun remainingMillis(token: LongArray, nowNanos: Long): Long =
            HostNative.call("transport.NativeControlDeadline", "remainingMillis", token, nowNanos) as Long
    }
}

@Implements(className = "com.shilapi.xcertplay.transport.NativeIap2Link", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeIap2Link {
    companion object {
        @JvmStatic @Implementation fun create(config: IntArray): Long =
            HostNative.call("transport.NativeIap2Link", "create", config) as Long

        @JvmStatic @Implementation fun destroy(handle: Long) { HostNative.call("transport.NativeIap2Link", "destroy", handle) }

        @JvmStatic @Implementation fun state(handle: Long): Int =
            HostNative.call("transport.NativeIap2Link", "state", handle) as Int

        @JvmStatic @Implementation fun writable(handle: Long): Boolean =
            HostNative.call("transport.NativeIap2Link", "writable", handle) as Boolean

        @JvmStatic @Implementation fun synchronization(handle: Long): IntArray =
            HostNative.call("transport.NativeIap2Link", "synchronization", handle) as IntArray

        @JvmStatic @Implementation fun takeOutput(handle: Long): ByteArray =
            HostNative.call("transport.NativeIap2Link", "takeOutput", handle) as ByteArray

        @JvmStatic @Implementation fun pollEvent(handle: Long): ByteArray? =
            HostNative.call("transport.NativeIap2Link", "pollEvent", handle) as ByteArray?

        @JvmStatic @Implementation fun nextDeadlineMillis(handle: Long): Long? =
            HostNative.call("transport.NativeIap2Link", "nextDeadlineMillis", handle) as Long?

        @JvmStatic @Implementation fun start(handle: Long, wired: Boolean, nowMillis: Long) { HostNative.call("transport.NativeIap2Link", "start", handle, wired, nowMillis) }

        @JvmStatic @Implementation fun feed(handle: Long, bytes: ByteArray, nowMillis: Long) { HostNative.call("transport.NativeIap2Link", "feed", handle, bytes, nowMillis) }

        @JvmStatic @Implementation fun feedEof(handle: Long) { HostNative.call("transport.NativeIap2Link", "feedEof", handle) }

        @JvmStatic @Implementation fun advanceTime(handle: Long, nowMillis: Long) { HostNative.call("transport.NativeIap2Link", "advanceTime", handle, nowMillis) }

        @JvmStatic @Implementation fun sendSession(handle: Long, sessionId: Int, bytes: ByteArray, nowMillis: Long) { HostNative.call("transport.NativeIap2Link", "sendSession", handle, sessionId, bytes, nowMillis) }

        @JvmStatic @Implementation fun encodeSynchronization(values: IntArray): ByteArray =
            HostNative.call("transport.NativeIap2Link", "encodeSynchronization", values) as ByteArray

        @JvmStatic @Implementation fun decodeSynchronization(bytes: ByteArray): IntArray? =
            HostNative.call("transport.NativeIap2Link", "decodeSynchronization", bytes) as IntArray?
    }
}

@Implements(className = "com.shilapi.xcertplay.transport.NativeVehicleIdentification", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeVehicleIdentification {
    companion object {
        @JvmStatic @Implementation fun group20(namePayload: ByteArray, connectors: IntArray): ByteArray =
            HostNative.call("transport.NativeVehicleIdentification", "group20", namePayload, connectors) as ByteArray

        @JvmStatic @Implementation fun group21(namePayload: ByteArray): ByteArray =
            HostNative.call("transport.NativeVehicleIdentification", "group21", namePayload) as ByteArray
    }
}

@Implements(className = "com.shilapi.xcertplay.transport.NativeVehicleSpeed", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeVehicleSpeed {
    companion object {
        @JvmStatic @Implementation fun pascdEncode(gear: Int, formattedParts: Array<String>): String? =
            HostNative.call("transport.NativeVehicleSpeed", "pascdEncode", gear, formattedParts) as String?
    }
}
