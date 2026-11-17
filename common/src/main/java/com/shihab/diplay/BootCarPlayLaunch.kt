package com.shihab.diplay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.shihab.diplay.network.CarHotspotSettings
import com.shihab.diplay.network.CarHotspotStatus
import com.shihab.diplay.network.CarHotspotTethering
import com.shihab.diplay.orchestration.CarPlayStartupTimeline
import com.shihab.diplay.orchestration.ManualHotspotValidation
import com.shihab.diplay.orchestration.WirelessHotspotMode
import com.shihab.diplay.setup.SetupGuide

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
    private const val TAG = "diplay-boot"

    /** A gated boot launch is in flight; a second boot callback must not open the screen again. */
    @Volatile private var launchPending = false

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

    /**
     * Returns true when the CarPlay screen is open or was asked to open; false leaves the home-screen
     * launch to the caller. [early]: called at user unlock, before BOOT_COMPLETED.
     *
     * On this path a car-hotspot start waits for the hotspot first ([BootReadinessGate]); the screen
     * is then opened from the gate's main-thread callback, so the receiver never blocks. Both boot
     * callbacks (unlock and BOOT_COMPLETED) return true while that launch is in flight, which keeps
     * the home screen from opening over it.
     */
    fun open(context: Context, early: Boolean = false): Boolean {
        // An earlier call at user unlock already opened it; BOOT_COMPLETED must not open Home over it.
        if (CarPlayScreen.current() != null) {
            Log.i(TAG, "Boot: CarPlay screen already open")
            if (!early) StartupDiagnosticSnapshot.launchResult(context, target = "carplay-already-open")
            return true
        }
        val inputs = runCatching { inputs(context) }.getOrElse {
            Log.w(TAG, "Boot CarPlay checks failed; opening DiPlay home", it)
            return false
        }
        if (decide(inputs) != Decision.CARPLAY) return false
        // The unlock callback and BOOT_COMPLETED can both arrive while a gated launch is waiting for
        // the hotspot; the screen is still on its way, so neither may fall through to the home screen.
        if (launchPending) {
            Log.i(TAG, "Boot: CarPlay launch already waiting for the car hotspot")
            return true
        }
        CarPlayStartupTimeline.record(context,
            if (early) CarPlayStartupTimeline.Stage.UNLOCK_DIRECT_HOST else CarPlayStartupTimeline.Stage.BOOT_DIRECT_HOST,
            inputs.mode)
        ConnectionDiagnostics.record(context, ConnectionDiagnostics.Source.BOOT, ConnectionDiagnostics.Event.PROJECTION_REQUESTED)
        // The same write DiPlayActivity makes before it opens the projection.
        AirPlayPersistence.saveWirelessEnabled(context, true)
        // Started before the gate: the gate waits for this head start, it must never hold it back. The
        // hotspot control allows one start at a time and the controller attaches to this one.
        // The home-screen fallback runs its own hotspot start after this method returns false.
        if (inputs.mode == WirelessHotspotMode.MANUAL && inputs.appStartsHotspot) StartupHotspot.start(context)
        if (!BootReadinessGate.waits(inputs.mode)) return openScreen(context, early)
        launchPending = true
        BootReadinessGate.awaitAsync(context, inputs.mode, isCurrent = { CarPlayScreen.current() == null }) { outcome ->
            launchPending = false
            if (BootReadinessGate.opens(outcome)) {
                // The receiver has already returned true, so this cannot fall back to DiPlay home any more.
                if (!openScreen(context, early)) {
                    Log.w(TAG, "Boot could not open the CarPlay screen after the readiness gate")
                }
            } else {
                // Another path (a notification, the dashboard, a USB attachment) owns the screen now.
                Log.i(TAG, "Boot readiness gate cancelled reason=${outcome.reason}; not opening CarPlay")
            }
        }
        return true
    }

    /** Starts the screen and its side effects. False only when the start itself threw. */
    private fun openScreen(context: Context, early: Boolean): Boolean = try {
        context.startActivity(CarPlayScreen.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        StartupDiagnosticSnapshot.launchResult(context, target = if (early) "carplay-unlock" else "carplay")
        // The home screen schedules this when it opens; this path skips it.
        runCatching { com.shihab.diplay.update.BackgroundUpdateScheduler.schedule(context.applicationContext) }
        true
    } catch (error: RuntimeException) {
        Log.w(TAG, "Boot could not open the CarPlay screen", error)
        false
    }
}
