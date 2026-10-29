package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.Build

/**
 * Detects the ECARX (亿咖通) XUI AdaptAPI on the head unit.
 *
 * The DIM classes live in the head unit's system image and are never packaged in this APK, so
 * every probe here is a `Class.forName` against the running platform, and every call site must
 * tolerate their absence. The contract was recovered from the bytecode of the ECARX apps shipped
 * on this head unit; nothing here is inferred from documentation.
 */
object EcarxCapabilities {
    const val DIM_INTERACTION = "com.ecarx.xui.adaptapi.diminteraction.DimInteraction"
    const val NAVI_INTERACTION = "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction"
    const val NAVI_INFO = "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction\$INavigationInfo"
    const val LANE_INFO = "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction\$ILaneInfo"
    const val MEDIA_INTERACTION = "com.ecarx.xui.adaptapi.diminteraction.IMediaInteraction"
    // `IMediaInteraction` takes two different payloads and they are not the same type: the track on
    // the card (`IPlaybackInfo`, 20 getters) and one queue entry (`IMedia`, 14). The shipped toolkit
    // implements both, and `updatePlaylist`'s erased `List` is the only place `IMedia` is named.
    const val MEDIA_PLAYBACK_INFO = "com.ecarx.xui.adaptapi.diminteraction.IMediaInteraction\$IPlaybackInfo"
    const val MEDIA_ITEM = "com.ecarx.xui.adaptapi.diminteraction.IMediaInteraction\$IMedia"
    const val PHONE_INTERACTION = "com.ecarx.xui.adaptapi.diminteraction.IPhoneCallInteraction"
    const val CALL_INFO = "com.ecarx.xui.adaptapi.diminteraction.IPhoneCallInteraction\$ICallInfo"

    // The callback interfaces the head unit's own navigation app registers on the DIM handles.
    // Registering them is what gives the cluster's own requests (`onDoInteractionAction`, e.g.
    // ACTION_GET_FAVORITE) a receiver on our side instead of dropping them on the floor.
    const val DIM_CALLBACK = "com.ecarx.xui.adaptapi.diminteraction.DimInteraction\$IInteractionCallback"
    const val NAVI_CALLBACK = "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction\$INavigationInteractionCallback"

    const val DIM_PROTOCOL_MANAGER = "ecarx.dimprotocol.DIMProtocolManager"
    const val CAR = "com.ecarx.xui.adaptapi.car.Car"
    const val DEVICE = "com.ecarx.xui.adaptapi.device.Device"

    // Microphone arbitration lives on the SDK policy API, not the XUI adaptapi. The reference app
    // uses `com.ecarx.sdk.policy.PolicyAPI`; the EAS one is the
    // SDK-side alias that also surfaces IAudioPolicy. Both are probed, the first match wins.
    const val POLICY_API = "com.ecarx.sdk.policy.PolicyAPI"
    const val EAS_POLICY_API = "com.ecarx.eas.sdk.policy.api.PolicyAPI"
    const val AUDIO_POLICY = "com.ecarx.sdk.policy.IAudioPolicy"
    const val MIC_CALLBACK = "com.ecarx.sdk.policy.IMicResourceCallback"

    /** Packages that only exist on an ECARX-built head unit. */
    private val ecarxPackages = listOf(
        "com.ecarx.xui.service",
        "com.ecarx.eas.service",
        "com.ecarx.assistant",
        "com.ecarx.xiaoka",
        "ecarx.naviservice",
    )

    /** Build fields that identify the vendor image when no package survives de-bloating. */
    private val ecarxBuildMarkers = listOf(
        Build.BRAND, Build.MANUFACTURER, Build.PRODUCT, Build.DEVICE, Build.HARDWARE, Build.FINGERPRINT,
    )

    fun classPresent(name: String): Boolean = runCatching { Class.forName(name) }.isSuccess

    /** Class presence only; the DIM service may still return no handle until the car wakes. */
    fun dimPresent(): Boolean = classPresent(DIM_INTERACTION)

    fun dimMediaPresent(): Boolean = classPresent(MEDIA_INTERACTION)

    enum class Detection {
        DIM_INTERFACE, BUILD_MARKER, VENDOR_PACKAGE, NONE;

        val identified: Boolean get() = this != NONE
    }

    /**
     * Identity evidence, in the existing precedence order. Build/package matches keep the ECARX
     * controls and calibrated display profile enabled; they do not prove any interface is present.
     * Runtime adapters still resolve their own handles and report refusals.
     */
    fun detect(context: Context): Detection {
        if (dimPresent()) return Detection.DIM_INTERFACE
        if (ecarxBuildMarkers.any { it.contains("ecarx", ignoreCase = true) }) return Detection.BUILD_MARKER
        return if (ecarxPackages.any { installed(context, it) }) Detection.VENDOR_PACKAGE else Detection.NONE
    }

    private fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** One-line state for logs and the diagnostics report. */
    fun describe(context: Context): String = buildString {
        append("dim=").append(classPresent(DIM_INTERACTION))
        append(" navi=").append(classPresent(NAVI_INTERACTION))
        append(" naviInfo=").append(classPresent(NAVI_INFO))
        append(" media=").append(classPresent(MEDIA_INTERACTION))
        append(" phone=").append(classPresent(PHONE_INTERACTION))
        append(" dimProtocol=").append(classPresent(DIM_PROTOCOL_MANAGER))
        append(" car=").append(classPresent(CAR))
        append(" device=").append(classPresent(DEVICE))
        append(" brand=").append(Build.BRAND)
        append(" product=").append(Build.PRODUCT)
        val detection = detect(context)
        append(" available=").append(detection.identified)
        append(" detection=").append(detection)
    }
}
