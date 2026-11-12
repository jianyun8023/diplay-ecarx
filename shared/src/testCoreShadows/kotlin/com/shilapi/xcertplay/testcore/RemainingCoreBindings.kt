@file:Suppress("UNCHECKED_CAST")

package com.shilapi.xcertplay.testcore

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements


@Implements(className = "com.shilapi.xcertplay.media.NativeAudioScheduler", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeAudioScheduler {
    companion object {
        @JvmStatic @Implementation fun configure(handle: Long, planned: Int, capacity: Int, chunk: Int) {
            HostNative.call("media.NativeAudioScheduler", "configure", handle, planned, capacity, chunk)
        }

        @JvmStatic @Implementation fun outputState(handle: Long, field: Int): Long =
            HostNative.call("media.NativeAudioScheduler", "outputState", handle, field) as Long

        @JvmStatic @Implementation fun writeLimit(handle: Long, remaining: Int, chunk: Int): Int =
            HostNative.call("media.NativeAudioScheduler", "writeLimit", handle, remaining, chunk) as Int

        @JvmStatic @Implementation fun completedWrite(handle: Long, bytes: Int, nowNanos: Long): Boolean =
            HostNative.call("media.NativeAudioScheduler", "completedWrite", handle, bytes, nowNanos) as Boolean

        @JvmStatic @Implementation fun playback(handle: Long, playing: Boolean) {
            HostNative.call("media.NativeAudioScheduler", "playback", handle, playing)
        }

        @JvmStatic @Implementation fun retainAfterPause(handle: Long, head: Int, nowNanos: Long) {
            HostNative.call("media.NativeAudioScheduler", "retainAfterPause", handle, head, nowNanos)
        }

        @JvmStatic @Implementation fun needsRebuffer(handle: Long, media: Boolean, underrun: Boolean, empty: Boolean, head: Int): Boolean =
            HostNative.call("media.NativeAudioScheduler", "needsRebuffer", handle, media, underrun, empty, head) as Boolean

        @JvmStatic @Implementation fun waitingForTail(handle: Long, empty: Boolean): Boolean =
            HostNative.call("media.NativeAudioScheduler", "waitingForTail", handle, empty) as Boolean

        @JvmStatic @Implementation fun tailReady(handle: Long, nowNanos: Long, waitNanos: Long): Boolean =
            HostNative.call("media.NativeAudioScheduler", "tailReady", handle, nowNanos, waitNanos) as Boolean

        @JvmStatic @Implementation fun writeDisposition(count: Int, nativeOutput: Boolean): Int =
            HostNative.call("media.NativeAudioScheduler", "writeDisposition", count, nativeOutput) as Int

        @JvmStatic @Implementation fun sanitizeDelay(millis: Int, mode: Int): Int =
            HostNative.call("media.NativeAudioScheduler", "sanitizeDelay", millis, mode) as Int

        @JvmStatic @Implementation fun bufferPlan(media: Boolean, rate: Int, channels: Int, minimum: Int, millis: Int): IntArray =
            HostNative.call("media.NativeAudioScheduler", "bufferPlan", media, rate, channels, minimum, millis) as IntArray

        @JvmStatic @Implementation fun navigationRoute(follows: Boolean, configured: Int, ducking: Boolean): Int =
            HostNative.call("media.NativeAudioScheduler", "navigationRoute", follows, configured, ducking) as Int

        @JvmStatic @Implementation fun create(frameBytes: Int): Long =
            HostNative.call("media.NativeAudioScheduler", "create", frameBytes) as Long

        @JvmStatic @Implementation fun destroy(handle: Long) {
            HostNative.call("media.NativeAudioScheduler", "destroy", handle)
        }

        @JvmStatic @Implementation fun reset(handle: Long) {
            HostNative.call("media.NativeAudioScheduler", "reset", handle)
        }

        @JvmStatic @Implementation fun written(handle: Long, bytes: Int) {
            HostNative.call("media.NativeAudioScheduler", "written", handle, bytes)
        }

        @JvmStatic @Implementation fun queuedBytes(handle: Long, rawHead: Int): Long =
            HostNative.call("media.NativeAudioScheduler", "queuedBytes", handle, rawHead) as Long

        @JvmStatic @Implementation fun shouldRebuffer(handle: Long, isMedia: Boolean, playing: Boolean, underrunSinceStart: Boolean, compressedQueueEmpty: Boolean, rawHead: Int, floorBytes: Long): Boolean =
            HostNative.call("media.NativeAudioScheduler", "shouldRebuffer", handle, isMedia, playing, underrunSinceStart, compressedQueueEmpty, rawHead, floorBytes) as Boolean

        @JvmStatic @Implementation fun startBytesFor(plannedStartBytes: Int, actualCapacityBytes: Int, writeChunkBytes: Int): Int =
            HostNative.call("media.NativeAudioScheduler", "startBytesFor", plannedStartBytes, actualCapacityBytes, writeChunkBytes) as Int

    }
}


@Implements(className = "com.shilapi.xcertplay.media.NativeVideoPolicy", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeVideoPolicy {
    companion object {
        @JvmStatic @Implementation fun softwareName(folded: String): Boolean =
            HostNative.call("media.NativeVideoPolicy", "softwareName", folded) as Boolean

        @JvmStatic @Implementation fun select(flags: IntArray, software: Boolean): Int =
            HostNative.call("media.NativeVideoPolicy", "select", flags, software) as Int

        @JvmStatic @Implementation fun probe(nowNanos: Long, intervalMillis: Long, failed: Boolean): LongArray =
            HostNative.call("media.NativeVideoPolicy", "probe", nowNanos, intervalMillis, failed) as LongArray

        @JvmStatic @Implementation fun probeDue(dueNanos: Long, nowNanos: Long): Boolean =
            HostNative.call("media.NativeVideoPolicy", "probeDue", dueNanos, nowNanos) as Boolean

        @JvmStatic @Implementation fun retryCreate(): LongArray =
            HostNative.call("media.NativeVideoPolicy", "retryCreate") as LongArray

        @JvmStatic @Implementation fun retryAttempt(token: LongArray, delayCount: Int): Int =
            HostNative.call("media.NativeVideoPolicy", "retryAttempt", token, delayCount) as Int

        @JvmStatic @Implementation fun retryCommit(token: LongArray): Int =
            HostNative.call("media.NativeVideoPolicy", "retryCommit", token) as Int

    }
}


@Implements(className = "com.shilapi.xcertplay.orchestration.NativeCarPlayController", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeCarPlayController {
    companion object {
        @JvmStatic @Implementation fun create(wireless: Boolean, location: Boolean): LongArray =
            HostNative.call("orchestration.NativeCarPlayController", "create", wireless, location) as LongArray

        @JvmStatic @Implementation fun query(owner: Array<LongArray>, field: Int): Long =
            HostNative.call("orchestration.NativeCarPlayController", "query", owner, field) as Long

        @JvmStatic @Implementation fun event(owner: Array<LongArray>, event: Int, value: Long, present: Boolean): Long =
            HostNative.call("orchestration.NativeCarPlayController", "event", owner, event, value, present) as Long

        @JvmStatic @Implementation fun decide(owner: Array<LongArray>, decision: Int, value: Long, other: Long, facts: Long): Long =
            HostNative.call("orchestration.NativeCarPlayController", "decide", owner, decision, value, other, facts) as Long

        @JvmStatic @Implementation fun key(owner: Array<LongArray>, event: Int, key: String): Boolean =
            HostNative.call("orchestration.NativeCarPlayController", "key", owner, event, key) as Boolean

        @JvmStatic @Implementation fun teardownPlan(owner: Array<LongArray>): IntArray =
            HostNative.call("orchestration.NativeCarPlayController", "teardownPlan", owner) as IntArray

        @JvmStatic @Implementation fun wirelessClosePlan(current: Boolean): IntArray =
            HostNative.call("orchestration.NativeCarPlayController", "wirelessClosePlan", current) as IntArray

        @JvmStatic @Implementation fun handoff(requested: Boolean, tunnel: Boolean, session: Boolean): Boolean =
            HostNative.call("orchestration.NativeCarPlayController", "handoff", requested, tunnel, session) as Boolean

        @JvmStatic @Implementation fun expectedControlClose(stage: Int, facts: Int): Boolean =
            HostNative.call("orchestration.NativeCarPlayController", "expectedControlClose", stage, facts) as Boolean

        @JvmStatic @Implementation fun configuration(found: Boolean, mode: Boolean, started: Boolean, tried: Int, maximum: Int): Int =
            HostNative.call("orchestration.NativeCarPlayController", "configuration", found, mode, started, tried, maximum) as Int

        @JvmStatic @Implementation fun configurationFallback(found: Boolean, mode: Boolean, started: Boolean): Boolean =
            HostNative.call("orchestration.NativeCarPlayController", "configurationFallback", found, mode, started) as Boolean

        @JvmStatic @Implementation fun vpnCreate(): LongArray =
            HostNative.call("orchestration.NativeCarPlayController", "vpnCreate") as LongArray

        @JvmStatic @Implementation fun vpnQuery(state: LongArray, field: Int): Int =
            HostNative.call("orchestration.NativeCarPlayController", "vpnQuery", state, field) as Int

        @JvmStatic @Implementation fun vpnEvent(state: LongArray, event: Int, generation: Int, current: Boolean): Int =
            HostNative.call("orchestration.NativeCarPlayController", "vpnEvent", state, event, generation, current) as Int

        @JvmStatic @Implementation fun proofCreate(): LongArray =
            HostNative.call("orchestration.NativeCarPlayController", "proofCreate") as LongArray

        @JvmStatic @Implementation fun proofEvent(state: LongArray, event: Int, generation: Int, sameSession: Boolean): Boolean =
            HostNative.call("orchestration.NativeCarPlayController", "proofEvent", state, event, generation, sameSession) as Boolean

        @JvmStatic @Implementation fun wirelessDecision(state: Long, operation: Int, facts: Int): Int =
            HostNative.call("orchestration.NativeCarPlayController", "wirelessDecision", state, operation, facts) as Int

        @JvmStatic @Implementation fun timingPolicy(field: Int): Long =
            HostNative.call("orchestration.NativeCarPlayController", "timingPolicy", field) as Long

        @JvmStatic @Implementation fun permissionDeadline(now: Long): Long =
            HostNative.call("orchestration.NativeCarPlayController", "permissionDeadline", now) as Long

        @JvmStatic @Implementation fun permissionExpired(deadline: Long, now: Long): Boolean =
            HostNative.call("orchestration.NativeCarPlayController", "permissionExpired", deadline, now) as Boolean

    }
}


@Implements(className = "com.shilapi.xcertplay.orchestration.NativeStartupWatchdog", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeStartupWatchdog {
    companion object {
        @JvmStatic @Implementation fun create(kind: Int, now: Long, timeout: Long): LongArray =
            HostNative.call("orchestration.NativeStartupWatchdog", "create", kind, now, timeout) as LongArray

        @JvmStatic @Implementation fun finished(state: LongArray): Boolean =
            HostNative.call("orchestration.NativeStartupWatchdog", "finished", state) as Boolean

        @JvmStatic @Implementation fun event(state: LongArray, event: Int, value: Int, time: Long, condition: Boolean): LongArray =
            HostNative.call("orchestration.NativeStartupWatchdog", "event", state, event, value, time, condition) as LongArray

        @JvmStatic @Implementation fun budget(missing: Int): Long =
            HostNative.call("orchestration.NativeStartupWatchdog", "budget", missing) as Long

        @JvmStatic @Implementation fun elapsed(start: Long, now: Long): Long =
            HostNative.call("orchestration.NativeStartupWatchdog", "elapsed", start, now) as Long

        @JvmStatic @Implementation fun remaining(timeout: Long, start: Long, now: Long): Long =
            HostNative.call("orchestration.NativeStartupWatchdog", "remaining", timeout, start, now) as Long

        @JvmStatic @Implementation fun bluetoothStall(reached: Int): Boolean =
            HostNative.call("orchestration.NativeStartupWatchdog", "bluetoothStall", reached) as Boolean

        @JvmStatic @Implementation fun startAgain(now: Long, start: Long, opened: Long, hasOpened: Boolean, active: Boolean, connectionAttempt: Boolean): Int =
            HostNative.call("orchestration.NativeStartupWatchdog", "startAgain", now, start, opened, hasOpened, active, connectionAttempt) as Int

    }
}


@Implements(className = "com.shilapi.xcertplay.transport.NativeUsbMux", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeUsbMux {
    companion object {
        @JvmStatic @Implementation fun framerCreate(): Long =
            HostNative.call("transport.NativeUsbMux", "framerCreate") as Long

        @JvmStatic @Implementation fun framerAppend(handle: Long, bytes: ByteArray) {
            HostNative.call("transport.NativeUsbMux", "framerAppend", handle, bytes)
        }

        @JvmStatic @Implementation fun framerRead(handle: Long): Array<Any?>? =
            HostNative.call("transport.NativeUsbMux", "framerRead", handle) as Array<Any?>?

        @JvmStatic @Implementation fun framerBuffered(handle: Long): Int =
            HostNative.call("transport.NativeUsbMux", "framerBuffered", handle) as Int

        @JvmStatic @Implementation fun framerDestroy(handle: Long) {
            HostNative.call("transport.NativeUsbMux", "framerDestroy", handle)
        }

        @JvmStatic @Implementation fun hostCreate(): Long =
            HostNative.call("transport.NativeUsbMux", "hostCreate") as Long

        @JvmStatic @Implementation fun version(): ByteArray =
            HostNative.call("transport.NativeUsbMux", "version") as ByteArray

        @JvmStatic @Implementation fun handshake(handle: Long, protocol: Int, length: Int, word8: Int): Int =
            HostNative.call("transport.NativeUsbMux", "handshake", handle, protocol, length, word8) as Int

        @JvmStatic @Implementation fun muxFrame(handle: Long, tcp: ByteArray?): ByteArray =
            HostNative.call("transport.NativeUsbMux", "muxFrame", handle, tcp) as ByteArray

        @JvmStatic @Implementation fun muxSent(handle: Long) {
            HostNative.call("transport.NativeUsbMux", "muxSent", handle)
        }

        @JvmStatic @Implementation fun muxReceived(handle: Long, sequence: Int) {
            HostNative.call("transport.NativeUsbMux", "muxReceived", handle, sequence)
        }

        @JvmStatic @Implementation fun sourcePort(handle: Long, active: IntArray): Int =
            HostNative.call("transport.NativeUsbMux", "sourcePort", handle, active) as Int

        @JvmStatic @Implementation fun hostDestroy(handle: Long) {
            HostNative.call("transport.NativeUsbMux", "hostDestroy", handle)
        }

        @JvmStatic @Implementation fun parseTcp(protocol: Int, frame: ByteArray): Array<Any?>? =
            HostNative.call("transport.NativeUsbMux", "parseTcp", protocol, frame) as Array<Any?>?

        @JvmStatic @Implementation fun encodeTcp(source: Int, destination: Int, sequence: Int, ack: Int, flags: Int, payload: ByteArray): ByteArray =
            HostNative.call("transport.NativeUsbMux", "encodeTcp", source, destination, sequence, ack, flags, payload) as ByteArray

        @JvmStatic @Implementation fun tcpCreate(source: Int, destination: Int): Long =
            HostNative.call("transport.NativeUsbMux", "tcpCreate", source, destination) as Long

        @JvmStatic @Implementation fun tcpStatus(handle: Long, retired: ByteArray): Int =
            HostNative.call("transport.NativeUsbMux", "tcpStatus", handle, retired) as Int

        @JvmStatic @Implementation fun tcpPrepare(handle: Long, bytes: ByteArray, offset: Int): Array<Any?> =
            HostNative.call("transport.NativeUsbMux", "tcpPrepare", handle, bytes, offset) as Array<Any?>

        @JvmStatic @Implementation fun tcpSent(handle: Long, bytes: Int) {
            HostNative.call("transport.NativeUsbMux", "tcpSent", handle, bytes)
        }

        @JvmStatic @Implementation fun tcpControl(handle: Long, retired: ByteArray, command: Int): ByteArray =
            HostNative.call("transport.NativeUsbMux", "tcpControl", handle, retired, command) as ByteArray

        @JvmStatic @Implementation fun tcpPacket(handle: Long, retired: ByteArray, flags: Int, sequence: Int, payload: ByteArray): Int =
            HostNative.call("transport.NativeUsbMux", "tcpPacket", handle, retired, flags, sequence, payload) as Int

        @JvmStatic @Implementation fun tcpAckSent(handle: Long): Int =
            HostNative.call("transport.NativeUsbMux", "tcpAckSent", handle) as Int

        @JvmStatic @Implementation fun tcpRecv(handle: Long, retired: ByteArray, maxBytes: Int): ByteArray? =
            HostNative.call("transport.NativeUsbMux", "tcpRecv", handle, retired, maxBytes) as ByteArray?

        @JvmStatic @Implementation fun tcpClosePlan(handle: Long, abort: Boolean): Int =
            HostNative.call("transport.NativeUsbMux", "tcpClosePlan", handle, abort) as Int

        @JvmStatic @Implementation fun tcpHostClose(handle: Long, failed: Boolean): Boolean =
            HostNative.call("transport.NativeUsbMux", "tcpHostClose", handle, failed) as Boolean

        @JvmStatic @Implementation fun tcpFreeze(handle: Long): ByteArray =
            HostNative.call("transport.NativeUsbMux", "tcpFreeze", handle) as ByteArray

    }
}


@Implements(className = "com.shilapi.xcertplay.transport.NativeIap2Handshake", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeIap2Handshake {
    companion object {
        @JvmStatic @Implementation fun create(kind: Int): LongArray =
            HostNative.call("transport.NativeIap2Handshake", "create", kind) as LongArray

        @JvmStatic @Implementation fun query(owner: Array<LongArray>, field: Int): Long =
            HostNative.call("transport.NativeIap2Handshake", "query", owner, field) as Long

        @JvmStatic @Implementation fun event(owner: Array<LongArray>, event: Int, value: Int, condition: Boolean) {
            HostNative.call("transport.NativeIap2Handshake", "event", owner, event, value, condition)
        }

        @JvmStatic @Implementation fun message(owner: Array<LongArray>, id: Int): Int =
            HostNative.call("transport.NativeIap2Handshake", "message", owner, id) as Int

        @JvmStatic @Implementation fun omitted(owner: Array<LongArray>): IntArray =
            HostNative.call("transport.NativeIap2Handshake", "omitted", owner) as IntArray

        @JvmStatic @Implementation fun rejected(owner: Array<LongArray>, parameters: IntArray, ids: IntArray, counts: IntArray): Boolean =
            HostNative.call("transport.NativeIap2Handshake", "rejected", owner, parameters, ids, counts) as Boolean

        @JvmStatic @Implementation fun wifiPlan(owner: Array<LongArray>): Int =
            HostNative.call("transport.NativeIap2Handshake", "wifiPlan", owner) as Int

        @JvmStatic @Implementation fun startAgain(owner: Array<LongArray>, decision: Int): Boolean =
            HostNative.call("transport.NativeIap2Handshake", "startAgain", owner, decision) as Boolean

        @JvmStatic @Implementation fun boundPoll(owner: Array<LongArray>, requested: Long): Long =
            HostNative.call("transport.NativeIap2Handshake", "boundPoll", owner, requested) as Long

        @JvmStatic @Implementation fun challengeValid(length: Int): Boolean =
            HostNative.call("transport.NativeIap2Handshake", "challengeValid", length) as Boolean

        @JvmStatic @Implementation fun rejectionMetadata(ids: IntArray, payloads: Array<ByteArray>): Array<IntArray?> =
            HostNative.call("transport.NativeIap2Handshake", "rejectionMetadata", ids, payloads) as Array<IntArray?>

        @JvmStatic @Implementation fun omittable(parameters: IntArray, ids: IntArray, counts: IntArray): IntArray? =
            HostNative.call("transport.NativeIap2Handshake", "omittable", parameters, ids, counts) as IntArray?

        @JvmStatic @Implementation fun deadlinePrepare(kind: Int, timeout: Long): LongArray =
            HostNative.call("transport.NativeIap2Handshake", "deadlinePrepare", kind, timeout) as LongArray

        @JvmStatic @Implementation fun deadlineNeedsStart(state: LongArray): Boolean =
            HostNative.call("transport.NativeIap2Handshake", "deadlineNeedsStart", state) as Boolean

        @JvmStatic @Implementation fun deadlineStart(state: LongArray, now: Long) {
            HostNative.call("transport.NativeIap2Handshake", "deadlineStart", state, now)
        }

        @JvmStatic @Implementation fun deadlinePlan(state: LongArray): LongArray =
            HostNative.call("transport.NativeIap2Handshake", "deadlinePlan", state) as LongArray

        @JvmStatic @Implementation fun deadlineAt(state: LongArray, now: Long): LongArray =
            HostNative.call("transport.NativeIap2Handshake", "deadlineAt", state, now) as LongArray

        @JvmStatic @Implementation fun deadlineKeepAlive(state: LongArray, plan: Int, alive: Boolean): Long =
            HostNative.call("transport.NativeIap2Handshake", "deadlineKeepAlive", state, plan, alive) as Long

        @JvmStatic @Implementation fun waitCreate(wait: Long, now: Long): LongArray =
            HostNative.call("transport.NativeIap2Handshake", "waitCreate", wait, now) as LongArray

        @JvmStatic @Implementation fun waitNeedsClock(state: LongArray, sent: Int, notified: Boolean): Boolean =
            HostNative.call("transport.NativeIap2Handshake", "waitNeedsClock", state, sent, notified) as Boolean

        @JvmStatic @Implementation fun waitExpired(state: LongArray, sent: Int, notified: Boolean, now: Long): Boolean =
            HostNative.call("transport.NativeIap2Handshake", "waitExpired", state, sent, notified, now) as Boolean

        @JvmStatic @Implementation fun waitBound(state: LongArray, requested: Long, sent: Int, notified: Boolean, now: Long): Long =
            HostNative.call("transport.NativeIap2Handshake", "waitBound", state, requested, sent, notified, now) as Long

    }
}
