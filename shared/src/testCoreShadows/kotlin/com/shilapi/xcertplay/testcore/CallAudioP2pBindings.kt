package com.shilapi.xcertplay.testcore

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.nio.ByteBuffer

@Implements(className = "com.shilapi.xcertplay.media.NativeEchoReference", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeEchoReference {
    companion object {
        @JvmStatic @Implementation fun create(rate: Int, capacityMillis: Int): Long =
            HostNative.call("media.NativeEchoReference", "create", rate, capacityMillis) as Long
        @JvmStatic @Implementation fun retain(handle: Long): Long =
            HostNative.call("media.NativeEchoReference", "retain", handle) as Long
        @JvmStatic @Implementation fun destroy(handle: Long) { HostNative.call("media.NativeEchoReference", "destroy", handle) }
        @JvmStatic @Implementation fun append(handle: Long, pcm: ByteArray, offset: Int, length: Int, channels: Int, playing: Boolean, pendingFrames: Long, nowNs: Long) {
            HostNative.call("media.NativeEchoReference", "append", handle, pcm, offset, length, channels, playing, pendingFrames, nowNs)
        }
        @JvmStatic @Implementation fun appendBuffer(handle: Long, pcm: ByteBuffer, offset: Int, length: Int, channels: Int, playing: Boolean, pendingFrames: Long, nowNs: Long) {
            HostNative.call("media.NativeEchoReference", "appendBuffer", handle, pcm, offset, length, channels, playing, pendingFrames, nowNs)
        }
        @JvmStatic @Implementation fun reset(handle: Long) { HostNative.call("media.NativeEchoReference", "reset", handle) }
        @JvmStatic @Implementation fun read(handle: Long, out: ShortArray, endNs: Long): Boolean =
            HostNative.call("media.NativeEchoReference", "read", handle, out, endNs) as Boolean
        @JvmStatic @Implementation fun captureRead(owner: LongArray, rate: Int, samples: Int, nowNs: Long, stamped: Boolean, timestampNs: Long, framePosition: Long) {
            HostNative.call("media.NativeEchoReference", "captureRead", owner, rate, samples, nowNs, stamped, timestampNs, framePosition)
        }
        @JvmStatic @Implementation fun captureEnd(owner: LongArray, rate: Int, samplesIntoRead: Int): Long =
            HostNative.call("media.NativeEchoReference", "captureEnd", owner, rate, samplesIntoRead) as Long
    }
}

@Implements(className = "com.shilapi.xcertplay.media.NativeAudioRouting", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeAudioRouting {
    companion object {
        @JvmStatic @Implementation fun map(audioType: String, payload: Int, mode: Int, navigationStream: Int): IntArray =
            HostNative.call("media.NativeAudioRouting", "map", audioType, payload, mode, navigationStream) as IntArray
        @JvmStatic @Implementation fun speaker(channel: Int, content: Int, stream: Int): IntArray =
            HostNative.call("media.NativeAudioRouting", "speaker", channel, content, stream) as IntArray
    }
}

@Implements(className = "com.shilapi.xcertplay.network.NativeP2pStartup", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeP2pStartup {
    companion object {
        @JvmStatic @Implementation fun validChannel(channel: Int): Boolean =
            HostNative.call("network.NativeP2pStartup", "validChannel", channel) as Boolean
        @JvmStatic @Implementation fun frequency(channel: Int): Int =
            HostNative.call("network.NativeP2pStartup", "frequency", channel) as Int
        @JvmStatic @Implementation fun remembered(frequency: Int): Int =
            HostNative.call("network.NativeP2pStartup", "remembered", frequency) as Int
        @JvmStatic @Implementation fun hotspotConflict(sdk: Int, hotspotOn: Boolean, busy: Boolean, replyMillis: Long): Boolean =
            HostNative.call("network.NativeP2pStartup", "hotspotConflict", sdk, hotspotOn, busy, replyMillis) as Boolean
        @JvmStatic @Implementation fun plan(station: Int, preferredMode: Int, preferredFrequency: Int, hasPreferredFrequency: Boolean, channel: Int, bandRequests: Boolean, fiveGhz: Boolean): IntArray =
            HostNative.call("network.NativeP2pStartup", "plan", station, preferredMode, preferredFrequency, hasPreferredFrequency, channel, bandRequests, fiveGhz) as IntArray
        @JvmStatic @Implementation fun start(station: Int, preferredMode: Int, preferredFrequency: Int, hasPreferredFrequency: Boolean, channel: Int, bandRequests: Boolean, fiveGhz: Boolean): IntArray =
            HostNative.call("network.NativeP2pStartup", "start", station, preferredMode, preferredFrequency, hasPreferredFrequency, channel, bandRequests, fiveGhz) as IntArray
        @JvmStatic @Implementation fun current(owner: IntArray): IntArray =
            HostNative.call("network.NativeP2pStartup", "current", owner) as IntArray
        @JvmStatic @Implementation fun compatibility(owner: IntArray): Int =
            HostNative.call("network.NativeP2pStartup", "compatibility", owner) as Int
        @JvmStatic @Implementation fun rejected(owner: IntArray, busy: Boolean, terminal: Boolean): Int =
            HostNative.call("network.NativeP2pStartup", "rejected", owner, busy, terminal) as Int
    }
}
