package com.shihab.diplay.testcore

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(className = "com.shihab.diplay.media.NativeMicrophone", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeMicrophone {
    companion object {
        @JvmStatic @Implementation fun create(key: ByteArray, payloadType: Int, address: ByteArray, scopeId: Int, port: Int,
            bitrate: Int, samplesPerPacket: Int, frameBytes: Int, sampleRate: Int, channels: Int): Long =
            HostNative.call("media.NativeMicrophone", "create", key, payloadType, address, scopeId, port,
                bitrate, samplesPerPacket, frameBytes, sampleRate, channels) as Long
        @JvmStatic @Implementation fun enableEcho(handle: Long, source: Any?): Boolean =
            HostNative.call("media.NativeMicrophone", "enableEcho", handle, source) as Boolean
        @JvmStatic @Implementation fun disableEcho(handle: Long) { HostNative.call("media.NativeMicrophone", "disableEcho", handle) }
        @JvmStatic @Implementation fun feed(handle: Long, input: ByteArray, count: Int, now: Long, stamped: Boolean,
            timestamp: Long, position: Long, host: Any, stats: LongArray): Int =
            HostNative.call("media.NativeMicrophone", "feed", handle, input, count, now, stamped, timestamp, position, host, stats) as Int
        @JvmStatic @Implementation fun close(handle: Long) { HostNative.call("media.NativeMicrophone", "close", handle) }
        @JvmStatic @Implementation fun uplinkConfigCreate(): Long =
            HostNative.call("media.NativeMicrophone", "uplinkConfigCreate") as Long
        @JvmStatic @Implementation fun uplinkConfigDecide(handle: Long, fingerprint: String): Int =
            HostNative.call("media.NativeMicrophone", "uplinkConfigDecide", handle, fingerprint) as Int
        @JvmStatic @Implementation fun uplinkConfigCommit(handle: Long, fingerprint: String) {
            HostNative.call("media.NativeMicrophone", "uplinkConfigCommit", handle, fingerprint)
        }
        @JvmStatic @Implementation fun uplinkConfigDestroy(handle: Long) {
            HostNative.call("media.NativeMicrophone", "uplinkConfigDestroy", handle)
        }
    }
}

@Implements(className = "com.shihab.diplay.media.NativeCaptureStats", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeCaptureStats {
    companion object {
        @JvmStatic @Implementation fun create(now: Long): LongArray = HostNative.call("media.NativeCaptureStats", "create", now) as LongArray
        @JvmStatic @Implementation fun event(owner: LongArray, event: Int, value: Long, extra: Long, now: Long) {
            HostNative.call("media.NativeCaptureStats", "event", owner, event, value, extra, now)
        }
        @JvmStatic @Implementation fun level(owner: LongArray, pcm: ByteArray) { HostNative.call("media.NativeCaptureStats", "level", owner, pcm) }
        @JvmStatic @Implementation fun snapshot(owner: LongArray, now: Long, ended: Boolean): LongArray? =
            HostNative.call("media.NativeCaptureStats", "snapshot", owner, now, ended) as LongArray?
        @JvmStatic @Implementation fun peakDbfs(peak: Int): Long = HostNative.call("media.NativeCaptureStats", "peakDbfs", peak) as Long
    }
}
