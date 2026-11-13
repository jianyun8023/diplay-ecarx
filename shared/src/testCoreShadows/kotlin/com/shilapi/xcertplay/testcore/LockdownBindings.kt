@file:Suppress("UNCHECKED_CAST")

package com.shilapi.xcertplay.testcore

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** SDK lexical/I/O callback objects remain in the sandbox; JNI looks up their actual classes. */
@Implements(className = "com.shilapi.xcertplay.transport.NativeLockdown", isInAndroidSdk = false, callNativeMethodsByDefault = true)
class ShadowNativeLockdown {
    companion object {
        @JvmStatic @Implementation fun encode(source: Any): Array<Any?> =
            HostNative.call("transport.NativeLockdown", "encode", source) as Array<Any?>

        @JvmStatic @Implementation fun encodeTree(tree: Array<Any?>): Array<Any?> =
            HostNative.call("transport.NativeLockdown", "encodeTree", tree) as Array<Any?>

        @JvmStatic @Implementation fun decode(source: Any): Array<Any?> =
            HostNative.call("transport.NativeLockdown", "decode", source) as Array<Any?>

        @JvmStatic @Implementation fun channelCreate(maximum: Int, defaultTimeout: Long): LongArray =
            HostNative.call("transport.NativeLockdown", "channelCreate", maximum, defaultTimeout) as LongArray

        @JvmStatic @Implementation fun channelState(state: LongArray, operation: Int): Long =
            HostNative.call("transport.NativeLockdown", "channelState", state, operation) as Long

        @JvmStatic @Implementation fun validateTimeout(timeout: Long) {
            HostNative.call("transport.NativeLockdown", "validateTimeout", timeout)
        }

        @JvmStatic @Implementation fun frame(maximum: Int, payload: ByteArray): Array<Any?> =
            HostNative.call("transport.NativeLockdown", "frame", maximum, payload) as Array<Any?>

        @JvmStatic @Implementation fun read(maximum: Int, io: Any, budget: Long, human: Boolean): Array<Any?> =
            HostNative.call("transport.NativeLockdown", "read", maximum, io, budget, human) as Array<Any?>

        @JvmStatic @Implementation fun pairCreate(label: String, hostId: String, buid: String, budget: Long, now: Long): Array<Any?> =
            HostNative.call("transport.NativeLockdown", "pairCreate", label, hostId, buid, budget, now) as Array<Any?>

        @JvmStatic @Implementation fun pairStep(state: Array<Any?>, event: Int, value: Array<Any?>?, cancelled: Boolean, now: Long): Array<Any?> =
            HostNative.call("transport.NativeLockdown", "pairStep", state, event, value, cancelled, now) as Array<Any?>

        @JvmStatic @Implementation fun pairWait(state: Array<Any?>, cancelled: Boolean, now: Long): Int =
            HostNative.call("transport.NativeLockdown", "pairWait", state, cancelled, now) as Int

        @JvmStatic @Implementation fun serviceCreate(label: String, hostId: String, buid: String, service: String): Array<Any?> =
            HostNative.call("transport.NativeLockdown", "serviceCreate", label, hostId, buid, service) as Array<Any?>

        @JvmStatic @Implementation fun serviceStep(state: Array<Any?>, response: Array<Any?>?): Array<Any?> =
            HostNative.call("transport.NativeLockdown", "serviceStep", state, response) as Array<Any?>
    }
}
