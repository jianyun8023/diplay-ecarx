@file:Suppress("UNCHECKED_CAST")

package com.shihab.diplay.testcore

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements


@Implements(className = "com.shihab.diplay.airplay.NativeDataStream", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeDataStream {
    companion object {
        @JvmStatic @Implementation fun create(mode: Int): Long =
            HostNative.call("airplay.NativeDataStream", "create", mode) as Long

        @JvmStatic @Implementation fun begin(handle: Long): Long =
            HostNative.call("airplay.NativeDataStream", "begin", handle) as Long

        @JvmStatic @Implementation fun append(handle: Long, id: Long, bytes: ByteArray) {
            HostNative.call("airplay.NativeDataStream", "append", handle, id, bytes)
        }

        @JvmStatic @Implementation fun next(handle: Long, id: Long, readKey: ByteArray, writeKey: ByteArray?): Array<Any?>? =
            HostNative.call("airplay.NativeDataStream", "next", handle, id, readKey, writeKey) as Array<Any?>?

        @JvmStatic @Implementation fun end(handle: Long, id: Long) {
            HostNative.call("airplay.NativeDataStream", "end", handle, id)
        }

        @JvmStatic @Implementation fun destroy(handle: Long) {
            HostNative.call("airplay.NativeDataStream", "destroy", handle)
        }

    }
}


@Implements(className = "com.shihab.diplay.transport.NativeNcm", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeNcm {
    companion object {
        @JvmStatic @Implementation fun valueCreate(kind: Int): LongArray =
            HostNative.call("transport.NativeNcm", "valueCreate", kind) as LongArray

        @JvmStatic @Implementation fun ntbBuild(frame: ByteArray, sequence: Int): ByteArray =
            HostNative.call("transport.NativeNcm", "ntbBuild", frame, sequence) as ByteArray

        @JvmStatic @Implementation fun ntbParse(block: ByteArray, offset: Int, length: Int): Array<ByteArray> =
            HostNative.call("transport.NativeNcm", "ntbParse", block, offset, length) as Array<ByteArray>

        @JvmStatic @Implementation fun ethernetParse(frame: ByteArray): Array<ByteArray>? =
            HostNative.call("transport.NativeNcm", "ethernetParse", frame) as Array<ByteArray>?

        @JvmStatic @Implementation fun ethernetView(frame: ByteArray): IntArray? =
            HostNative.call("transport.NativeNcm", "ethernetView", frame) as IntArray?

        @JvmStatic @Implementation fun ethernetBuild(source: ByteArray, destination: ByteArray, ipv6: ByteArray): ByteArray =
            HostNative.call("transport.NativeNcm", "ethernetBuild", source, destination, ipv6) as ByteArray

        @JvmStatic @Implementation fun ethernetMulticast(ipv6: ByteArray): ByteArray? =
            HostNative.call("transport.NativeNcm", "ethernetMulticast", ipv6) as ByteArray?

        @JvmStatic @Implementation fun ethernetFixNa(ipv6: ByteArray, hostMac: ByteArray): ByteArray? =
            HostNative.call("transport.NativeNcm", "ethernetFixNa", ipv6, hostMac) as ByteArray?

        @JvmStatic @Implementation fun trafficHeader(state: LongArray, frame: ByteArray): Int =
            HostNative.call("transport.NativeNcm", "trafficHeader", state, frame) as Int

        @JvmStatic @Implementation fun trafficTime(state: LongArray, port: Int, now: Long) {
            HostNative.call("transport.NativeNcm", "trafficTime", state, port, now)
        }

        @JvmStatic @Implementation fun trafficSent(state: LongArray) {
            HostNative.call("transport.NativeNcm", "trafficSent", state)
        }

        @JvmStatic @Implementation fun trafficSummary(state: LongArray, port: Int): String =
            HostNative.call("transport.NativeNcm", "trafficSummary", state, port) as String

        @JvmStatic @Implementation fun trafficAttempt(state: LongArray, port: Int, since: Long): Boolean =
            HostNative.call("transport.NativeNcm", "trafficAttempt", state, port, since) as Boolean

        @JvmStatic @Implementation fun txSequence(state: LongArray): Int =
            HostNative.call("transport.NativeNcm", "txSequence", state) as Int

        @JvmStatic @Implementation fun txTimeout(state: LongArray, requested: Int): Int =
            HostNative.call("transport.NativeNcm", "txTimeout", state, requested) as Int

        @JvmStatic @Implementation fun txBegin(state: LongArray, transferred: Int): LongArray =
            HostNative.call("transport.NativeNcm", "txBegin", state, transferred) as LongArray

        @JvmStatic @Implementation fun txOpened(state: LongArray, now: Long) {
            HostNative.call("transport.NativeNcm", "txOpened", state, now)
        }

        @JvmStatic @Implementation fun txOpenedAt(state: LongArray): LongArray =
            HostNative.call("transport.NativeNcm", "txOpenedAt", state) as LongArray

        @JvmStatic @Implementation fun txFinish(state: LongArray, transferred: Int, expected: Int): Int =
            HostNative.call("transport.NativeNcm", "txFinish", state, transferred, expected) as Int

        @JvmStatic @Implementation fun receiveCreate(packetSize: Int): Long =
            HostNative.call("transport.NativeNcm", "receiveCreate", packetSize) as Long

        @JvmStatic @Implementation fun receiveAppend(handle: Long, chunk: ByteArray, length: Int) {
            HostNative.call("transport.NativeNcm", "receiveAppend", handle, chunk, length)
        }

        @JvmStatic @Implementation fun receiveStep(handle: Long, hasTime: Boolean, now: Long, detail: Array<String?>): Int =
            HostNative.call("transport.NativeNcm", "receiveStep", handle, hasTime, now, detail) as Int

        @JvmStatic @Implementation fun receivePoll(handle: Long): ByteArray? =
            HostNative.call("transport.NativeNcm", "receivePoll", handle) as ByteArray?

        @JvmStatic @Implementation fun receiveClose(handle: Long) {
            HostNative.call("transport.NativeNcm", "receiveClose", handle)
        }

        @JvmStatic @Implementation fun routeInbound(state: LongArray, frame: ByteArray): IntArray? =
            HostNative.call("transport.NativeNcm", "routeInbound", state, frame) as IntArray?

        @JvmStatic @Implementation fun routeDestination(state: LongArray, ipv6: ByteArray, detail: LongArray): ByteArray? =
            HostNative.call("transport.NativeNcm", "routeDestination", state, ipv6, detail) as ByteArray?

    }
}


@Implements(className = "com.shihab.diplay.airplay.NativeMfiSap", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeMfiSap {
    companion object {
        @JvmStatic @Implementation fun handle(body: ByteArray, sdk: Any): ByteArray? =
            HostNative.call("airplay.NativeMfiSap", "handle", body, sdk) as ByteArray?

    }
}


@Implements(className = "com.shihab.diplay.airplay.NativeIapStreamQueue", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeIapStreamQueue {
    companion object {
        @JvmStatic @Implementation fun create(): Array<Any?> =
            HostNative.call("airplay.NativeIapStreamQueue", "create") as Array<Any?>

        @JvmStatic @Implementation fun offer(state: Array<Any?>, bytes: ByteArray): Int =
            HostNative.call("airplay.NativeIapStreamQueue", "offer", state, bytes) as Int

        @JvmStatic @Implementation fun take(state: Array<Any?>, maximum: Int): ByteArray? =
            HostNative.call("airplay.NativeIapStreamQueue", "take", state, maximum) as ByteArray?

        @JvmStatic @Implementation fun status(state: Array<Any?>): Int =
            HostNative.call("airplay.NativeIapStreamQueue", "status", state) as Int

        @JvmStatic @Implementation fun fail(state: Array<Any?>): Boolean =
            HostNative.call("airplay.NativeIapStreamQueue", "fail", state) as Boolean

        @JvmStatic @Implementation fun close(state: Array<Any?>): Boolean =
            HostNative.call("airplay.NativeIapStreamQueue", "close", state) as Boolean

    }
}


@Implements(className = "com.shihab.diplay.media.NativeAac", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeAac {
    companion object {
        @JvmStatic @Implementation fun frequencyIndex(sampleRate: Int): Int =
            HostNative.call("media.NativeAac", "frequencyIndex", sampleRate) as Int

        @JvmStatic @Implementation fun writeAdtsHeader(target: ByteArray, accessUnitBytes: Int, sampleRate: Int, channels: Int): Int =
            HostNative.call("media.NativeAac", "writeAdtsHeader", target, accessUnitBytes, sampleRate, channels) as Int

        @JvmStatic @Implementation fun audioSpecificConfig(sampleRate: Int, channels: Int): ByteArray =
            HostNative.call("media.NativeAac", "audioSpecificConfig", sampleRate, channels) as ByteArray

        @JvmStatic @Implementation fun accessUnit(payload: ByteArray): ByteArray =
            HostNative.call("media.NativeAac", "accessUnit", payload) as ByteArray

    }
}


@Implements(className = "com.shihab.diplay.media.NativeVideoTiming", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeVideoTiming {
    companion object {
        @JvmStatic @Implementation fun pacerCreate(window: Int, percentile: Int, refreshEvery: Int, slew: Double, resetNanos: Long, maxEarlyNanos: Long): LongArray =
            HostNative.call("media.NativeVideoTiming", "pacerCreate", window, percentile, refreshEvery, slew, resetNanos, maxEarlyNanos) as LongArray

        @JvmStatic @Implementation fun pacerFrame(owner: LongArray, enabled: Boolean, sender: Long, arrival: Long): Long =
            HostNative.call("media.NativeVideoTiming", "pacerFrame", owner, enabled, sender, arrival) as Long

        @JvmStatic @Implementation fun outputCreate(initialDelay: Long): LongArray =
            HostNative.call("media.NativeVideoTiming", "outputCreate", initialDelay) as LongArray

        @JvmStatic @Implementation fun outputQueued(owner: LongArray, presentation: Long, local: Long, now: Long) {
            HostNative.call("media.NativeVideoTiming", "outputQueued", owner, presentation, local, now)
        }

        @JvmStatic @Implementation fun outputPlan(owner: LongArray, presentation: Long, now: Long, shown: Boolean): LongArray =
            HostNative.call("media.NativeVideoTiming", "outputPlan", owner, presentation, now, shown) as LongArray

        @JvmStatic @Implementation fun outputValue(owner: LongArray, field: Int): Long =
            HostNative.call("media.NativeVideoTiming", "outputValue", owner, field) as Long

        @JvmStatic @Implementation fun pacerLocal(owner: LongArray, senderNanos: Long, arrivalNanos: Long): Long =
            HostNative.call("media.NativeVideoTiming", "pacerLocal", owner, senderNanos, arrivalNanos) as Long

        @JvmStatic @Implementation fun delayCreate(initial: Long, min: Long, max: Long, window: Int, percentile: Int, refreshEvery: Int, margin: Long, rise: Long, fall: Long): LongArray =
            HostNative.call("media.NativeVideoTiming", "delayCreate", initial, min, max, window, percentile, refreshEvery, margin, rise, fall) as LongArray

        @JvmStatic @Implementation fun delayFrame(owner: LongArray, neededNanos: Long) {
            HostNative.call("media.NativeVideoTiming", "delayFrame", owner, neededNanos)
        }

        @JvmStatic @Implementation fun delayNanos(owner: LongArray): Long =
            HostNative.call("media.NativeVideoTiming", "delayNanos", owner) as Long

        @JvmStatic @Implementation fun backlogCreate(): LongArray =
            HostNative.call("media.NativeVideoTiming", "backlogCreate") as LongArray

        @JvmStatic @Implementation fun backlogEvent(owner: LongArray, event: Int, nowNanos: Long, receivedNanos: Long, pending: Int, newestNanos: Long, hasNewest: Boolean): Boolean =
            HostNative.call("media.NativeVideoTiming", "backlogEvent", owner, event, nowNanos, receivedNanos, pending, newestNanos, hasNewest) as Boolean

    }
}


@Implements(className = "com.shihab.diplay.media.NativeVideoFlow", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeVideoFlow {
    companion object {
        @JvmStatic @Implementation fun queueCreate(maxFrames: Int, maxBytes: Int, resync: Any): Array<Any?> =
            HostNative.call("media.NativeVideoFlow", "queueCreate", maxFrames, maxBytes, resync) as Array<Any?>

        @JvmStatic @Implementation fun queueOffer(owner: Array<Any?>, job: Any, kind: Int, size: Int, received: Long): Array<Any?> =
            HostNative.call("media.NativeVideoFlow", "queueOffer", owner, job, kind, size, received) as Array<Any?>

        @JvmStatic @Implementation fun queueRemove(owner: Array<Any?>, op: Int): Array<Any?> =
            HostNative.call("media.NativeVideoFlow", "queueRemove", owner, op) as Array<Any?>

        @JvmStatic @Implementation fun queueBacklog(owner: Array<Any?>): LongArray =
            HostNative.call("media.NativeVideoFlow", "queueBacklog", owner) as LongArray

        @JvmStatic @Implementation fun queueResync(owner: Array<Any?>): Boolean =
            HostNative.call("media.NativeVideoFlow", "queueResync", owner) as Boolean

        @JvmStatic @Implementation fun reference(owner: Long, event: Int): Long =
            HostNative.call("media.NativeVideoFlow", "reference", owner, event) as Long

        @JvmStatic @Implementation fun referenceNeeds(owner: Long): Boolean =
            HostNative.call("media.NativeVideoFlow", "referenceNeeds", owner) as Boolean

        @JvmStatic @Implementation fun referenceAccepts(owner: Long, randomAccess: Boolean): Boolean =
            HostNative.call("media.NativeVideoFlow", "referenceAccepts", owner, randomAccess) as Boolean

        @JvmStatic @Implementation fun obsolete(now: Long, received: Long, pending: Int, newest: Long, hasNewest: Boolean): Boolean =
            HostNative.call("media.NativeVideoFlow", "obsolete", now, received, pending, newest, hasNewest) as Boolean

        @JvmStatic @Implementation fun keyframeCreate(): LongArray =
            HostNative.call("media.NativeVideoFlow", "keyframeCreate") as LongArray

        @JvmStatic @Implementation fun keyframeRequest(owner: LongArray, now: Long, force: Boolean): Boolean =
            HostNative.call("media.NativeVideoFlow", "keyframeRequest", owner, now, force) as Boolean

        @JvmStatic @Implementation fun inputCreate(start: Long, timeout: Long): LongArray =
            HostNative.call("media.NativeVideoFlow", "inputCreate", start, timeout) as LongArray

        @JvmStatic @Implementation fun inputStep(owner: LongArray, event: Int, value: Long): Int =
            HostNative.call("media.NativeVideoFlow", "inputStep", owner, event, value) as Int

        @JvmStatic @Implementation fun inputResult(owner: LongArray): Int =
            HostNative.call("media.NativeVideoFlow", "inputResult", owner) as Int

        @JvmStatic @Implementation fun touchSent(sent: Long) {
            HostNative.call("media.NativeVideoFlow", "touchSent", sent)
        }

        @JvmStatic @Implementation fun touchFrame(now: Long): Long =
            HostNative.call("media.NativeVideoFlow", "touchFrame", now) as Long

    }
}
