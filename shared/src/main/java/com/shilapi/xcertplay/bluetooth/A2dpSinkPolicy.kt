package com.shilapi.xcertplay.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import java.lang.reflect.Method

/** Hidden SDK boundary: Android 9 uses priority, newer stacks use connection policy. */
internal class A2dpSinkPolicy private constructor(
    val profile: BluetoothProfile,
    private val getter: Method,
    private val setter: Method,
    private val log: (String) -> Unit,
) {
    fun read(device: BluetoothDevice): Int? =
        runCatching { getter.invoke(profile, device) as? Int }
            .onFailure { failure(getter.name, it) }.getOrNull()

    fun write(device: BluetoothDevice, policy: Int): Boolean =
        runCatching { setter.invoke(profile, device, policy) == true }
            .onFailure { failure(setter.name, it) }.getOrDefault(false)

    fun connectionState(device: BluetoothDevice): Int? =
        runCatching { profile.getConnectionState(device) }
            .onFailure { failure("getConnectionState", it) }.getOrNull()

    fun disconnect(device: BluetoothDevice): Boolean = call("disconnect", device)
    fun connect(device: BluetoothDevice): Boolean = call("connect", device)

    private fun call(name: String, device: BluetoothDevice): Boolean = runCatching {
        profile.javaClass.getMethod(name, BluetoothDevice::class.java).invoke(profile, device) == true
    }.onFailure { failure(name, it) }.getOrDefault(false)

    private fun failure(operation: String, error: Throwable) {
        val cause = error.cause ?: error
        log("BT guard media: $operation failed ${cause.javaClass.simpleName}")
    }

    companion object {
        // AOSP Android 9 BluetoothProfile.A2DP_SINK; absent from the public SDK stubs.
        const val PROFILE_ID = 11

        fun from(profile: BluetoothProfile, log: (String) -> Unit): A2dpSinkPolicy? {
            val modern = runCatching {
                profile.javaClass.getMethod("getConnectionPolicy", BluetoothDevice::class.java)
            }.getOrNull()
            val getter = modern ?: runCatching {
                profile.javaClass.getMethod("getPriority", BluetoothDevice::class.java)
            }.getOrNull() ?: return null
            val setter = runCatching {
                profile.javaClass.getMethod(if (modern != null) "setConnectionPolicy" else "setPriority",
                    BluetoothDevice::class.java, Int::class.javaPrimitiveType)
            }.getOrNull() ?: return null
            return A2dpSinkPolicy(profile, getter, setter, log)
        }
    }
}
