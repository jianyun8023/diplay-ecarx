@file:Suppress("UNCHECKED_CAST")

package com.shihab.diplay.testcore

import com.shihab.diplay.media.CarPlayGlanceSong
import com.shihab.diplay.media.CarPlayNowPlaying
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Run the actual host JNI while rebuilding its publication DTO in the Android sandbox. */
@Implements(className = "com.shihab.diplay.media.NativePlaybackCore", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativePlaybackCore {
    companion object {
        @JvmStatic @Implementation fun nowCreate(): LongArray = HostNative.call("media.NativePlaybackCore", "nowCreate") as LongArray
        @JvmStatic @Implementation fun nowSnapshot(owner: Array<LongArray>): CarPlayNowPlaying =
            snapshot(requireNotNull(HostNative.call("media.NativePlaybackCore", "nowSnapshot", owner)))
        @JvmStatic @Implementation fun nowAccept(owner: Array<LongArray>, id: Int, payload: ByteArray): CarPlayNowPlaying? =
            HostNative.call("media.NativePlaybackCore", "nowAccept", owner, id, payload)?.let(::snapshot)
        @JvmStatic @Implementation fun nowClear(owner: Array<LongArray>, all: Boolean): CarPlayNowPlaying? =
            HostNative.call("media.NativePlaybackCore", "nowClear", owner, all)?.let(::snapshot)
        @JvmStatic @Implementation fun glanceCreate(): LongArray = HostNative.call("media.NativePlaybackCore", "glanceCreate") as LongArray
        @JvmStatic @Implementation fun glanceSnapshot(owner: Array<LongArray>): CarPlayGlanceSong? =
            HostNative.call("media.NativePlaybackCore", "glanceSnapshot", owner)?.let(::glance)
        @JvmStatic @Implementation fun glanceAccept(owner: Array<LongArray>, id: Int, payload: ByteArray): CarPlayGlanceSong? =
            HostNative.call("media.NativePlaybackCore", "glanceAccept", owner, id, payload)?.let(::glance)
        @JvmStatic @Implementation fun glanceClear(owner: Array<LongArray>): CarPlayGlanceSong? =
            HostNative.call("media.NativePlaybackCore", "glanceClear", owner)?.let(::glance)
        @JvmStatic @Implementation fun navigationCreate(): LongArray = HostNative.call("media.NativePlaybackCore", "navigationCreate") as LongArray
        @JvmStatic @Implementation fun navigationEvent(owner: Array<LongArray>, event: Int, id: Long,
            stream: Int, hasStream: Boolean, now: Long, buffered: Long, tail: Long, priority: Boolean): Long =
            HostNative.call("media.NativePlaybackCore", "navigationEvent", owner, event, id, stream, hasStream, now, buffered, tail, priority) as Long
        @JvmStatic @Implementation fun navigationSnapshot(owner: Array<LongArray>, now: Long): LongArray =
            HostNative.call("media.NativePlaybackCore", "navigationSnapshot", owner, now) as LongArray

        private fun snapshot(value: Any) = CarPlayNowPlaying(
            HostNative.component(value, 1) as String?, HostNative.component(value, 2) as String?,
            HostNative.component(value, 3) as String?, HostNative.component(value, 4) as Int?,
            HostNative.component(value, 5) as String?, HostNative.component(value, 6) as Long?,
            HostNative.component(value, 7) as Long?, HostNative.component(value, 8) as Boolean,
        )

        private fun glance(value: Any) = CarPlayGlanceSong(
            HostNative.component(value, 1) as String?, HostNative.component(value, 2) as String?,
            HostNative.component(value, 3) as Boolean,
        )
    }
}

@Implements(className = "com.shihab.diplay.network.NativeHotspotAddress", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeHotspotAddress {
    companion object {
        @JvmStatic @Implementation fun select(facts: IntArray, operation: Int, scopedOnly: Boolean): Int =
            HostNative.call("network.NativeHotspotAddress", "select", facts, operation, scopedOnly) as Int
        @JvmStatic @Implementation fun readiness(preferIpv6: Boolean, facts: IntArray): Int =
            HostNative.call("network.NativeHotspotAddress", "readiness", preferIpv6, facts) as Int
        @JvmStatic @Implementation fun filter(facts: IntArray): IntArray =
            HostNative.call("network.NativeHotspotAddress", "filter", facts) as IntArray
        @JvmStatic @Implementation fun flipGate(phoneOnHotspot: Boolean, familyReachable: Boolean): Int =
            HostNative.call("network.NativeHotspotAddress", "flipGate", phoneOnHotspot, familyReachable) as Int
        @JvmStatic @Implementation fun deadline(start: Long, timeout: Long): LongArray =
            HostNative.call("network.NativeHotspotAddress", "deadline", start, timeout) as LongArray
        @JvmStatic @Implementation fun time(token: LongArray, elapsed: Boolean, now: Long): Long =
            HostNative.call("network.NativeHotspotAddress", "time", token, elapsed, now) as Long
    }
}

@Implements(className = "com.shihab.diplay.network.NativeHotspotRecovery", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeHotspotRecovery {
    companion object {
        @JvmStatic @Implementation fun readinessCreate(now: Long, timeout: Long): LongArray =
            HostNative.call("network.NativeHotspotRecovery", "readinessCreate", now, timeout) as LongArray
        @JvmStatic @Implementation fun readinessSample(token: LongArray, selected: Boolean, hadPrevious: Boolean,
            same: Boolean, confirmed: Boolean): Boolean =
            HostNative.call("network.NativeHotspotRecovery", "readinessSample", token, selected, hadPrevious, same, confirmed) as Boolean
        @JvmStatic @Implementation fun readinessTime(token: LongArray, expired: Boolean, now: Long): Long =
            HostNative.call("network.NativeHotspotRecovery", "readinessTime", token, expired, now) as Long
        @JvmStatic @Implementation fun returnWait(start: Long, timeout: Long, now: Long): Long =
            HostNative.call("network.NativeHotspotRecovery", "returnWait", start, timeout, now) as Long
        @JvmStatic @Implementation fun outageCreate(): LongArray = HostNative.call("network.NativeHotspotRecovery", "outageCreate") as LongArray
        @JvmStatic @Implementation fun outageEvent(token: LongArray, operation: Int, now: Long, budget: Long): Boolean =
            HostNative.call("network.NativeHotspotRecovery", "outageEvent", token, operation, now, budget) as Boolean
    }
}

@Implements(className = "com.shihab.diplay.iap2.message.NativeLaneGuidance", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeLaneGuidance {
    companion object {
        @JvmStatic @Implementation fun parse(payload: ByteArray): Array<IntArray>? =
            HostNative.call("iap2.message.NativeLaneGuidance", "parse", payload) as Array<IntArray>?
    }
}
