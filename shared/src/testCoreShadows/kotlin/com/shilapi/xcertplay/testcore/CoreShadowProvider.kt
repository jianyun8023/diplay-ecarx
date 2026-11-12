package com.shilapi.xcertplay.testcore

import org.robolectric.internal.ClassTracker
import org.robolectric.internal.ShadowProvider
import java.util.AbstractMap

/** Registered only in unit-test resources; no shadow or Robolectric code enters Android APKs. */
class CoreShadowProvider : ShadowProvider {
    override fun getProvidedPackageNames(): Array<String> = arrayOf("com.shilapi.xcertplay")

    override fun getShadows(): Collection<Map.Entry<String, String>> = bindings.map {
        AbstractMap.SimpleImmutableEntry(it.first, it.second)
    }

    override fun reset(classes: ClassTracker) {
        // Facades own close(). Process-lifetime owners must survive a reused Android sandbox.
    }

    private val bindings = listOf(
        "com.shilapi.xcertplay.media.NativeAudioScheduler" to "com.shilapi.xcertplay.testcore.ShadowNativeAudioScheduler",
        "com.shilapi.xcertplay.media.NativeVideoPolicy" to "com.shilapi.xcertplay.testcore.ShadowNativeVideoPolicy",
        "com.shilapi.xcertplay.orchestration.NativeCarPlayController" to "com.shilapi.xcertplay.testcore.ShadowNativeCarPlayController",
        "com.shilapi.xcertplay.orchestration.NativeStartupWatchdog" to "com.shilapi.xcertplay.testcore.ShadowNativeStartupWatchdog",
        "com.shilapi.xcertplay.transport.NativeUsbMux" to "com.shilapi.xcertplay.testcore.ShadowNativeUsbMux",
        "com.shilapi.xcertplay.transport.NativeIap2Handshake" to "com.shilapi.xcertplay.testcore.ShadowNativeIap2Handshake",
        "com.shilapi.xcertplay.airplay.NativeScreenCodec" to "com.shilapi.xcertplay.testcore.ShadowNativeScreenCodec",
        "com.shilapi.xcertplay.airplay.NativeAirPlayNegotiation" to "com.shilapi.xcertplay.testcore.ShadowNativeAirPlayNegotiation",
        "com.shilapi.xcertplay.airplay.NativeTouchSlots" to "com.shilapi.xcertplay.testcore.ShadowNativeTouchSlots",
        "com.shilapi.xcertplay.airplay.NativeBplist" to "com.shilapi.xcertplay.testcore.ShadowNativeBplist",
        "com.shilapi.xcertplay.airplay.NativeControlCipher" to "com.shilapi.xcertplay.testcore.ShadowNativeControlCipher",
        "com.shilapi.xcertplay.airplay.NativeCrypto" to "com.shilapi.xcertplay.testcore.ShadowNativeCrypto",
        "com.shilapi.xcertplay.airplay.NativeMedia" to "com.shilapi.xcertplay.testcore.ShadowNativeMedia",
        "com.shilapi.xcertplay.airplay.NativeRtsp" to "com.shilapi.xcertplay.testcore.ShadowNativeRtsp",
        "com.shilapi.xcertplay.airplay.NativeNtpTiming" to "com.shilapi.xcertplay.testcore.ShadowNativeNtpTiming",
        "com.shilapi.xcertplay.hud.NativeClusterArrow" to "com.shilapi.xcertplay.testcore.ShadowNativeClusterArrow",
        "com.shilapi.xcertplay.hud.NativeDayNight" to "com.shilapi.xcertplay.testcore.ShadowNativeDayNight",
        "com.shilapi.xcertplay.hud.NativeLaneIcon" to "com.shilapi.xcertplay.testcore.ShadowNativeLaneIcon",
        "com.shilapi.xcertplay.hud.NativeRouteGuidance" to "com.shilapi.xcertplay.testcore.ShadowNativeRouteGuidance",
        "com.shilapi.xcertplay.iap2.wire.NativeIap2Csm" to "com.shilapi.xcertplay.testcore.ShadowNativeIap2Csm",
        "com.shilapi.xcertplay.iap2.wire.NativeIap2Body" to "com.shilapi.xcertplay.testcore.ShadowNativeIap2Body",
        "com.shilapi.xcertplay.iap2.wire.NativeIap2Wire" to "com.shilapi.xcertplay.testcore.ShadowNativeIap2Wire",
        "com.shilapi.xcertplay.orchestration.NativeWirelessRecovery" to "com.shilapi.xcertplay.testcore.ShadowNativeWirelessRecovery",
        "com.shilapi.xcertplay.transport.NativeControlDeadline" to "com.shilapi.xcertplay.testcore.ShadowNativeControlDeadline",
        "com.shilapi.xcertplay.transport.NativeIap2Link" to "com.shilapi.xcertplay.testcore.ShadowNativeIap2Link",
        "com.shilapi.xcertplay.transport.NativeVehicleIdentification" to "com.shilapi.xcertplay.testcore.ShadowNativeVehicleIdentification",
        "com.shilapi.xcertplay.transport.NativeVehicleSpeed" to "com.shilapi.xcertplay.testcore.ShadowNativeVehicleSpeed",
        "com.shilapi.xcertplay.airplay.NativePairVerify" to "com.shilapi.xcertplay.testcore.ShadowNativePairVerify",
        "com.shilapi.xcertplay.airplay.NativePairSetup" to "com.shilapi.xcertplay.testcore.ShadowNativePairSetup",
    )
}
