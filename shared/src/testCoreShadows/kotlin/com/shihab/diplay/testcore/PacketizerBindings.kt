@file:Suppress("UNCHECKED_CAST")

package com.shihab.diplay.testcore

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(className = "com.shihab.diplay.airplay.NativeMicrophonePacketizer", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeMicrophonePacketizer {
    companion object {
        @JvmStatic @Implementation fun counters(sequence: Int, timestamp: Int, nonce: Long): LongArray =
            HostNative.call("airplay.NativeMicrophonePacketizer", "counters", sequence, timestamp, nonce) as LongArray
        @JvmStatic @Implementation fun counter(owner: LongArray, field: Int, write: Boolean, value: Long): Long =
            HostNative.call("airplay.NativeMicrophonePacketizer", "counter", owner, field, write, value) as Long
        @JvmStatic @Implementation fun seal(owner: LongArray, key: ByteArray, payloadType: Int, body: ByteArray, samples: Int): ByteArray =
            HostNative.call("airplay.NativeMicrophonePacketizer", "seal", owner, key, payloadType, body, samples) as ByteArray
        @JvmStatic @Implementation fun wirePcm(pcm: ByteArray): ByteArray =
            HostNative.call("airplay.NativeMicrophonePacketizer", "wirePcm", pcm) as ByteArray
        @JvmStatic @Implementation fun plan(opus: Boolean, sampleRate: Int, channels: Int, frameMillis: Int, opusClockRate: Int): IntArray =
            HostNative.call("airplay.NativeMicrophonePacketizer", "plan", opus, sampleRate, channels, frameMillis, opusClockRate) as IntArray
        @JvmStatic @Implementation fun opusClockRate(formatBits: Long): Int =
            HostNative.call("airplay.NativeMicrophonePacketizer", "opusClockRate", formatBits) as Int
        @JvmStatic @Implementation fun pcmFrameMillis(sampleRate: Int, channels: Int): Int =
            HostNative.call("airplay.NativeMicrophonePacketizer", "pcmFrameMillis", sampleRate, channels) as Int
    }
}

@Implements(className = "com.shihab.diplay.transport.NativeIap2FileTransfer", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeIap2FileTransfer {
    companion object {
        @JvmStatic @Implementation fun create(maximum: Int): Array<Any?> =
            HostNative.call("transport.NativeIap2FileTransfer", "create", maximum) as Array<Any?>
        @JvmStatic @Implementation fun accept(state: Array<Any?>, datagram: ByteArray): Array<Any?> =
            HostNative.call("transport.NativeIap2FileTransfer", "accept", state, datagram) as Array<Any?>
        @JvmStatic @Implementation fun clear(state: Array<Any?>) {
            HostNative.call("transport.NativeIap2FileTransfer", "clear", state)
        }
    }
}
