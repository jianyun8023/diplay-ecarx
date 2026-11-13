package com.shihab.diplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shihab.diplay.network.CarHotspotTethering
import com.shihab.diplay.network.CarWifiClientGuard
import com.shihab.diplay.network.HotspotHeadStart
import com.shihab.diplay.orchestration.CarPlayStartupTimeline

/**
 * Starts the car hotspot as soon as the head unit boots, while the CarPlay screen is still being
 * created. Android 9 takes about 2.4 s to bring the SoftAP up on a cold boot; doing it here takes
 * that time off the path to the first picture.
 *
 * The hotspot control allows one start at a time and a second caller gets BUSY. The controller
 * waits for this start through [HotspotHeadStart] and then only confirms the AP; the home screen
 * uses [whenIdle] before its own hotspot start.
 */
internal object StartupHotspot {
    private const val TAG = "DiPlay-Hotspot"
    private val waiting = mutableListOf<() -> Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun isRunning(): Boolean = HotspotHeadStart.isRunning()

    /** Returns false when a start is already running; it is never queued twice. */
    fun start(context: Context): Boolean {
        if (!HotspotHeadStart.begin()) return false
        val app = context.applicationContext
        val mode = AirPlayPersistence.loadWirelessHotspotMode(app)
        CarPlayStartupTimeline.record(app, CarPlayStartupTimeline.Stage.HOTSPOT_PREPARE_BEGIN, mode)
        Thread({
            var result: CarHotspotTethering.Result? = null
            try {
                // With the client allowed, start after the car settings app turned it on, not before.
                CarWifiClientGuard.awaitClientRestore(app, CarWifiClientGuard.CLIENT_RESTORE_WAIT_MILLIS,
                    isCancelled = { !com.shihab.diplay.network.CarHotspotSettings.enabled(app) })?.let {
                    Log.i(TAG, "Boot hotspot waited for the car Wi-Fi client waitedMs=$it")
                    SettingsActionDiagnostics.record(app, SettingsActionDiagnostics.Action.MANUAL_HOTSPOT,
                        "phase=boot-prepare clientWaitMs=$it")
                }
                result = CarHotspotSetup.prepare(app,
                    isCancelled = { !com.shihab.diplay.network.CarHotspotSettings.enabled(app) },
                    onProgress = { phase ->
                        SettingsActionDiagnostics.record(app, SettingsActionDiagnostics.Action.MANUAL_HOTSPOT,
                            "phase=boot-prepare state=$phase")
                    },
                    log = {
                        Log.i(TAG, it)
                        // The report keeps this one; logcat drops most lines at boot.
                        if (it.startsWith("car Wi-Fi client")) SettingsActionDiagnostics.record(app,
                            SettingsActionDiagnostics.Action.MANUAL_HOTSPOT, "phase=boot-prepare $it")
                    })
            } catch (error: RuntimeException) {
                Log.w(TAG, "Boot hotspot start failed", error)
            } finally {
                if (result == CarHotspotTethering.Result.READY) {
                    CarPlayStartupTimeline.record(app, CarPlayStartupTimeline.Stage.HOTSPOT_PREPARE_END, mode)
                }
                // The controller still validates the AP and reports any failure; this start is only a head start.
                Log.i(TAG, "Boot hotspot start finished result=${result ?: "error"}")
                finish()
            }
        }, "diplay-boot-hotspot").start()
        return true
    }

    /** Runs [action] on the main thread now, or once the boot hotspot start has finished. */
    fun whenIdle(action: () -> Unit) {
        val runNow = synchronized(waiting) {
            if (HotspotHeadStart.isRunning()) { waiting += action; false } else true
        }
        if (runNow) action()
    }

    private fun finish() {
        val ready = synchronized(waiting) {
            HotspotHeadStart.finish()
            waiting.toList().also { waiting.clear() }
        }
        ready.forEach { main.post(it) }
    }
}
