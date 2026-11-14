@file:Suppress("UNCHECKED_CAST")

package com.shihab.diplay.testcore

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Collections and scalar DTOs keep their bootstrap JVM types across the test sandbox. */
@Implements(className = "com.shihab.diplay.airplay.NativeAirPlayProtocol", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeAirPlayProtocol {
    companion object {
        @JvmStatic @Implementation fun info(facts: Map<String, Any?>): Map<String, Any?> =
            HostNative.call("airplay.NativeAirPlayProtocol", "info", facts) as Map<String, Any?>

        @JvmStatic @Implementation fun features(disableAudio: Boolean): Long =
            HostNative.call("airplay.NativeAirPlayProtocol", "features", disableAudio) as Long

        @JvmStatic @Implementation fun bufferedEnabled(buffered: Boolean, disableAudio: Boolean): Boolean =
            HostNative.call("airplay.NativeAirPlayProtocol", "bufferedEnabled", buffered, disableAudio) as Boolean

        @JvmStatic @Implementation fun viewArea(index: Int, count: Int): Map<String, Any?> =
            HostNative.call("airplay.NativeAirPlayProtocol", "viewArea", index, count) as Map<String, Any?>

        @JvmStatic @Implementation fun video(operation: Int, arguments: List<Any?>): Any? =
            HostNative.call("airplay.NativeAirPlayProtocol", "video", operation, arguments)

        @JvmStatic @Implementation fun control(operation: Int, arguments: List<Any?>): Any? =
            HostNative.call("airplay.NativeAirPlayProtocol", "control", operation, arguments)

        @JvmStatic @Implementation fun bonjour(operation: Int, arguments: List<Any?>): Any? =
            HostNative.call("airplay.NativeAirPlayProtocol", "bonjour", operation, arguments)

        @JvmStatic @Implementation fun probeCreate(): Long =
            HostNative.call("airplay.NativeAirPlayProtocol", "probeCreate") as Long

        @JvmStatic @Implementation fun probeRetryCreate(): LongArray =
            HostNative.call("airplay.NativeAirPlayProtocol", "probeRetryCreate") as LongArray

        @JvmStatic @Implementation fun probeRetry(token: LongArray, event: Int, closed: Boolean): Long =
            HostNative.call("airplay.NativeAirPlayProtocol", "probeRetry", token, event, closed) as Long

        @JvmStatic @Implementation fun probeFeed(handle: Long, byte: Int): List<ByteArray?>? =
            HostNative.call("airplay.NativeAirPlayProtocol", "probeFeed", handle, byte) as List<ByteArray?>?

        @JvmStatic @Implementation fun probeDestroy(handle: Long) {
            HostNative.call("airplay.NativeAirPlayProtocol", "probeDestroy", handle)
        }

        @JvmStatic @Implementation fun availabilityCreate(): LongArray =
            HostNative.call("airplay.NativeAirPlayProtocol", "availabilityCreate") as LongArray

        @JvmStatic @Implementation fun availability(token: LongArray, event: Int, value: Boolean): Int =
            HostNative.call("airplay.NativeAirPlayProtocol", "availability", token, event, value) as Int

        @JvmStatic @Implementation fun cornerMask(value: Any?, depth: Int): ByteArray? =
            HostNative.call("airplay.NativeAirPlayProtocol", "cornerMask", value, depth) as ByteArray?

        @JvmStatic @Implementation fun audioSetup(
            type: Int,
            audioType: String,
            stream: Map<String, Any?>,
            sharedSecret: ByteArray?,
            microphoneEnabled: Boolean,
        ): Map<String, Any?>? =
            HostNative.call("airplay.NativeAirPlayProtocol", "audioSetup", type, audioType, stream, sharedSecret, microphoneEnabled) as Map<String, Any?>?

        @JvmStatic @Implementation fun dataStreamSetup(stream: Map<String, Any?>): Map<String, Any?>? =
            HostNative.call("airplay.NativeAirPlayProtocol", "dataStreamSetup", stream) as Map<String, Any?>?

        @JvmStatic @Implementation fun dataStreamKey(sharedSecret: ByteArray, seed: String, label: String): ByteArray? =
            HostNative.call("airplay.NativeAirPlayProtocol", "dataStreamKey", sharedSecret, seed, label) as ByteArray?

        @JvmStatic @Implementation fun setupReply(
            operation: Int,
            type: Int,
            streamId: Long,
            dataPort: Int,
            controlPort: Int,
            connectionId: Any?,
        ): Map<String, Any?>? =
            HostNative.call("airplay.NativeAirPlayProtocol", "setupReply", operation, type, streamId, dataPort, controlPort, connectionId) as Map<String, Any?>?
    }
}
