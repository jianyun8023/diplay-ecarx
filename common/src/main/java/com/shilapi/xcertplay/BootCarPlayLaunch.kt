package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotStatus
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.orchestration.CarPlayStartupTimeline
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.setup.SetupGuide

/**
 * Opens the CarPlay screen straight from BOOT_COMPLETED instead of going through the DiPlay home
 * screen. On a cold boot the home screen, its hotspot check and the projection request took 5.5 s
 * before the CarPlay screen was even created (v129 car test, 2026-11-07).
 *
 * Only the common, fully configured case takes this path: wireless auto-connect to a chosen
 * iPhone, over the car hotspot or an existing Wi-Fi. Anything the home screen would have to ask
 * the driver about returns [Decision.HOME], and the boot opens the home screen as before.
 */
internal object BootCarPlayLaunch {
    private const val TAG = "xcertplay-boot"

    enum class Decision { CARPLAY, HOME }

    /** The checks DiPlayActivity makes before an automatic wireless connection, as plain inputs. */
    data class Inputs(
        val autoConnect: Boolean,
        val wireless: Boolean,
        val phoneChosen: Boolean,
        val setupGuidePending: Boolean,
        val authenticationReady: Boolean,
        val sessionRunning: Boolean,
        val mode: WirelessHotspotMode,
        val hotspotDetailsValid: Boolean,
        val hotspotReportedOff: Boolean,
        val appStartsHotspot: Boolean,
        val notificationPermissionPending: Boolean,
    )

    fun decide(inputs: Inputs): Decision = with(inputs) {
        val ready = autoConnect && wireless && phoneChosen && !setupGuidePending && authenticationReady &&
            !sessionRunning && !notificationPermissionPending && when (mode) {
                WirelessHotspotMode.EXISTING_WIFI -> true
                // The home screen asks for missing details or offers to open the car settings.
                WirelessHotspotMode.MANUAL -> hotspotDetailsValid && (!hotspotReportedOff || appStartsHotspot)
                // Wi-Fi Direct and local-only hotspots keep their unchanged start-up timing.
                else -> false
            }
        if (ready) Decision.CARPLAY else Decision.HOME
    }

    private fun inputs(context: Context): Inputs {
        val mode = AirPlayPersistence.loadWirelessHotspotMode(context)
        val phoneChosen = DiPlayPreferences.phoneAddress(context) != null
        val appStartsHotspot = CarHotspotSettings.shouldEnable(context, true, mode) && CarHotspotTethering.permitted(context)
        return Inputs(
            autoConnect = DiPlayPreferences.autoConnect(context),
            wireless = DiPlayPreferences.autoConnectWireless(context),
            phoneChosen = phoneChosen,
            setupGuidePending = SetupGuide.shouldOpenOnLaunch(SetupGuide.seen(context), phoneChosen),
            authenticationReady = runCatching {
                DiPlayBootstrap.ensure(context, AirPlayPersistence.loadMfiTarget(context))
            }.isSuccess,
            sessionRunning = CarPlayBackgroundSession.hasSession(),
            mode = mode,
            hotspotDetailsValid = ManualHotspotValidation.error(
                AirPlayPersistence.loadManualHotspotSsid(context),
                AirPlayPersistence.loadManualHotspotPassphrase(context),
            ) == null,
            hotspotReportedOff = CarHotspotSettings.reportedOff(context, CarHotspotStatus.isEnabled(context)),
            appStartsHotspot = appStartsHotspot,
            notificationPermissionPending = Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED,
        )
    }

    /** Returns true when the CarPlay screen was asked to open; false leaves the home-screen launch to the caller. */
    fun open(context: Context): Boolean {
        val inputs = runCatching { inputs(context) }.getOrElse {
            Log.w(TAG, "Boot CarPlay checks failed; opening DiPlay home", it)
            return false
        }
        if (decide(inputs) != Decision.CARPLAY) return false
        CarPlayStartupTimeline.record(context, CarPlayStartupTimeline.Stage.BOOT_DIRECT_HOST, inputs.mode)
        ConnectionDiagnostics.record(context, ConnectionDiagnostics.Source.BOOT, ConnectionDiagnostics.Event.PROJECTION_REQUESTED)
        // The same write DiPlayActivity makes before it opens the projection.
        AirPlayPersistence.saveWirelessEnabled(context, true)
        return try {
            context.startActivity(CarPlayScreen.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            StartupDiagnosticSnapshot.launchResult(context, target = "carplay")
            // Only after the screen is on its way: the home-screen fallback runs its own hotspot start.
            if (inputs.mode == WirelessHotspotMode.MANUAL && inputs.appStartsHotspot) StartupHotspot.start(context)
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Boot could not open the CarPlay screen; opening DiPlay home", error)
            false
        }
    }
}
