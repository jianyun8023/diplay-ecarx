package com.shihab.diplay.testcore

import org.robolectric.annotation.internal.DoNotInstrument
import org.robolectric.internal.ClassTracker
import org.robolectric.internal.ShadowProvider
import java.util.AbstractMap

/** Registered only in unit-test resources; no shadow or Robolectric code enters Android APKs. */
@DoNotInstrument
class CoreShadowProvider : ShadowProvider {
    // Instrument only JNI facades; application clocks and providers must retain normal behavior.
    override fun getProvidedPackageNames(): Array<String> = bindings.map { it.first }.toTypedArray()

    override fun getShadows(): Collection<Map.Entry<String, String>> = bindings.map {
        AbstractMap.SimpleImmutableEntry(it.first, it.second)
    }

    override fun reset(classes: ClassTracker) {
        // Facades own close(). Process-lifetime owners must survive a reused Android sandbox.
    }

    private val bindings = listOf(
        "com.shihab.diplay.airplay.NativeAirPlayProtocol" to "com.shihab.diplay.testcore.ShadowNativeAirPlayProtocol",
        "com.shihab.diplay.airplay.NativeDataStream" to "com.shihab.diplay.testcore.ShadowNativeDataStream",
        "com.shihab.diplay.transport.NativeNcm" to "com.shihab.diplay.testcore.ShadowNativeNcm",
        "com.shihab.diplay.airplay.NativeMfiSap" to "com.shihab.diplay.testcore.ShadowNativeMfiSap",
        "com.shihab.diplay.airplay.NativeIapStreamQueue" to "com.shihab.diplay.testcore.ShadowNativeIapStreamQueue",
        "com.shihab.diplay.media.NativeAac" to "com.shihab.diplay.testcore.ShadowNativeAac",
        "com.shihab.diplay.media.NativeVideoTiming" to "com.shihab.diplay.testcore.ShadowNativeVideoTiming",
        "com.shihab.diplay.media.NativeVideoFlow" to "com.shihab.diplay.testcore.ShadowNativeVideoFlow",
        "com.shihab.diplay.airplay.AudioStream" to "com.shihab.diplay.testcore.ShadowAudioStream",
        "com.shihab.diplay.transport.NativeIap2ReportSubscription" to "com.shihab.diplay.testcore.ShadowNativeIap2ReportSubscription",
        "com.shihab.diplay.transport.NativeNmeaLocation" to "com.shihab.diplay.testcore.ShadowNativeNmeaLocation",
        "com.shihab.diplay.airplay.NativeMicrophonePacketizer" to "com.shihab.diplay.testcore.ShadowNativeMicrophonePacketizer",
        "com.shihab.diplay.transport.NativeIap2FileTransfer" to "com.shihab.diplay.testcore.ShadowNativeIap2FileTransfer",
        "com.shihab.diplay.transport.NativeLockdown" to "com.shihab.diplay.testcore.ShadowNativeLockdown",
        "com.shihab.diplay.media.NativeAudioScheduler" to "com.shihab.diplay.testcore.ShadowNativeAudioScheduler",
        "com.shihab.diplay.media.NativeVideoPolicy" to "com.shihab.diplay.testcore.ShadowNativeVideoPolicy",
        "com.shihab.diplay.orchestration.NativeCarPlayController" to "com.shihab.diplay.testcore.ShadowNativeCarPlayController",
        "com.shihab.diplay.orchestration.NativeStartupWatchdog" to "com.shihab.diplay.testcore.ShadowNativeStartupWatchdog",
        "com.shihab.diplay.transport.NativeUsbMux" to "com.shihab.diplay.testcore.ShadowNativeUsbMux",
        "com.shihab.diplay.transport.NativeIap2Handshake" to "com.shihab.diplay.testcore.ShadowNativeIap2Handshake",
        "com.shihab.diplay.airplay.NativeScreenCodec" to "com.shihab.diplay.testcore.ShadowNativeScreenCodec",
        "com.shihab.diplay.airplay.NativeAirPlayNegotiation" to "com.shihab.diplay.testcore.ShadowNativeAirPlayNegotiation",
        "com.shihab.diplay.airplay.NativeTouchSlots" to "com.shihab.diplay.testcore.ShadowNativeTouchSlots",
        "com.shihab.diplay.airplay.NativeBplist" to "com.shihab.diplay.testcore.ShadowNativeBplist",
        "com.shihab.diplay.airplay.NativeControlCipher" to "com.shihab.diplay.testcore.ShadowNativeControlCipher",
        "com.shihab.diplay.airplay.NativeCrypto" to "com.shihab.diplay.testcore.ShadowNativeCrypto",
        "com.shihab.diplay.airplay.NativeMedia" to "com.shihab.diplay.testcore.ShadowNativeMedia",
        "com.shihab.diplay.airplay.NativeRtsp" to "com.shihab.diplay.testcore.ShadowNativeRtsp",
        "com.shihab.diplay.airplay.NativeNtpTiming" to "com.shihab.diplay.testcore.ShadowNativeNtpTiming",
        "com.shihab.diplay.hud.NativeClusterArrow" to "com.shihab.diplay.testcore.ShadowNativeClusterArrow",
        "com.shihab.diplay.hud.NativeDayNight" to "com.shihab.diplay.testcore.ShadowNativeDayNight",
        "com.shihab.diplay.hud.NativeLaneIcon" to "com.shihab.diplay.testcore.ShadowNativeLaneIcon",
        "com.shihab.diplay.hud.NativeRouteGuidance" to "com.shihab.diplay.testcore.ShadowNativeRouteGuidance",
        "com.shihab.diplay.iap2.wire.NativeIap2Csm" to "com.shihab.diplay.testcore.ShadowNativeIap2Csm",
        "com.shihab.diplay.iap2.wire.NativeIap2Body" to "com.shihab.diplay.testcore.ShadowNativeIap2Body",
        "com.shihab.diplay.iap2.wire.NativeIap2Wire" to "com.shihab.diplay.testcore.ShadowNativeIap2Wire",
        "com.shihab.diplay.orchestration.NativeWirelessRecovery" to "com.shihab.diplay.testcore.ShadowNativeWirelessRecovery",
        "com.shihab.diplay.transport.NativeControlDeadline" to "com.shihab.diplay.testcore.ShadowNativeControlDeadline",
        "com.shihab.diplay.transport.NativeIap2Link" to "com.shihab.diplay.testcore.ShadowNativeIap2Link",
        "com.shihab.diplay.transport.NativeVehicleIdentification" to "com.shihab.diplay.testcore.ShadowNativeVehicleIdentification",
        "com.shihab.diplay.transport.NativeVehicleSpeed" to "com.shihab.diplay.testcore.ShadowNativeVehicleSpeed",
        "com.shihab.diplay.airplay.NativePairVerify" to "com.shihab.diplay.testcore.ShadowNativePairVerify",
        "com.shihab.diplay.airplay.NativePairSetup" to "com.shihab.diplay.testcore.ShadowNativePairSetup",
        "com.shihab.diplay.media.NativeEchoReference" to "com.shihab.diplay.testcore.ShadowNativeEchoReference",
        "com.shihab.diplay.media.NativeAudioRouting" to "com.shihab.diplay.testcore.ShadowNativeAudioRouting",
        "com.shihab.diplay.network.NativeP2pStartup" to "com.shihab.diplay.testcore.ShadowNativeP2pStartup",
        "com.shihab.diplay.media.NativePlaybackCore" to "com.shihab.diplay.testcore.ShadowNativePlaybackCore",
        "com.shihab.diplay.network.NativeHotspotAddress" to "com.shihab.diplay.testcore.ShadowNativeHotspotAddress",
        "com.shihab.diplay.network.NativeHotspotRecovery" to "com.shihab.diplay.testcore.ShadowNativeHotspotRecovery",
        "com.shihab.diplay.iap2.message.NativeLaneGuidance" to "com.shihab.diplay.testcore.ShadowNativeLaneGuidance",
    )
}
