@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "UNCHECKED_CAST")

package com.shilapi.xcertplay.testcore

import com.shilapi.xcertplay.airplay.ControlCipher
import com.shilapi.xcertplay.airplay.RtspMessage
import com.shilapi.xcertplay.hud.AppleManeuver
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter

/** JNI creates parent-loader DTOs; keep Android-facing DTO identity in the current sandbox. */
internal object CoreDtos {
    fun rtsp(value: Any): RtspMessage.Parsed = RtspMessage.Parsed(
        (HostNative.component(value, 1) as List<Any>).map { request ->
            RtspMessage.Request(
                HostNative.component(request, 1) as String,
                HostNative.component(request, 2) as String,
                HostNative.component(request, 3) as String,
                HostNative.component(request, 4) as Map<String, String>,
                HostNative.component(request, 5) as ByteArray,
            )
        },
        HostNative.component(value, 2) as ByteArray,
    )

    fun decrypted(value: Any): ControlCipher.Decrypted = ControlCipher.Decrypted(
        HostNative.component(value, 1) as ByteArray,
        HostNative.component(value, 2) as ByteArray,
    )

    fun maneuver(value: Any): AppleManeuver = AppleManeuver(
        HostNative.component(value, 1) as Int,
        HostNative.component(value, 2) as Int,
        HostNative.component(value, 3) as Int,
        HostNative.component(value, 4) as String,
        HostNative.component(value, 5) as Long?,
        HostNative.component(value, 6) as Long?,
        HostNative.component(value, 7) as Long?,
        HostNative.component(value, 8) as Long?,
    )

    fun frame(value: Any): Iap2Frame = Iap2Frame(
        value.javaClass.getMethod("getMessageId").invoke(value) as Int,
        value.javaClass.getMethod("getPayload").invoke(value) as ByteArray,
    )

    fun parameter(value: Any): Iap2Parameter = Iap2Parameter(
        value.javaClass.getMethod("getId").invoke(value) as Int,
        value.javaClass.getMethod("getPayload").invoke(value) as ByteArray,
    )
}
