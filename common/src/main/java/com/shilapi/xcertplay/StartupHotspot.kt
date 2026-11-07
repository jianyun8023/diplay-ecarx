package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.orchestration.CarPlayStartupTimeline
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts the car hotspot as soon as the head unit boots, while the CarPlay screen is still being
 * created. Android 9 takes about 2.4 s to bring the SoftAP up on a cold boot; doing it here takes
 * that time off the path to the first picture.
 *
 * The hotspot control allows one start at a time and a second caller gets BUSY, so the CarPlay
 * screen must call [whenIdle] before its controller starts the hotspot itself. By then the AP is
 * usually on and the controller only confirms it.
 */
internal object StartupHotspot {
    private const val TAG = "DiPlay-Hotspot"
    private val running = AtomicBoolean(false)
    private val waiting = mutableListOf<() -> Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun isRunning(): Boolean = running.get()

    /** Returns false when a start is already running; it is never queued twice. */
    fun start(context: Context): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val app = context.applicationContext
        val mode = AirPlayPersistence.loadWirelessHotspotMode(app)
        CarPlayStartupTimeline.record(app, CarPlayStartupTimeline.Stage.HOTSPOT_PREPARE_BEGIN, mode)
        Thread({
            var result: CarHotspotTethering.Result? = null
            try {
                result = CarHotspotSetup.prepare(app,
                    isCancelled = { !com.shilapi.xcertplay.network.CarHotspotSettings.enabled(app) },
                    onProgress = { phase ->
                        SettingsActionDiagnostics.record(app, SettingsActionDiagnostics.Action.MANUAL_HOTSPOT,
                            "phase=boot-prepare state=$phase")
                    },
                    log = { Log.i(TAG, it) })
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
            if (running.get()) { waiting += action; false } else true
        }
        if (runNow) action()
    }

    private fun finish() {
        val ready = synchronized(waiting) {
            running.set(false)
            waiting.toList().also { waiting.clear() }
        }
        ready.forEach { main.post(it) }
    }
}
