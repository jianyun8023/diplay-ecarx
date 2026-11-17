package com.shihab.diplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shihab.diplay.network.CarHotspotReady
import com.shihab.diplay.orchestration.CarPlayStartupTimeline
import com.shihab.diplay.orchestration.WirelessHotspotMode

/**
 * First-post-boot readiness gate: the boot auto-start opens the CarPlay screen only once the car
 * hotspot is usable, so the driver does not stare at the splash while the same hotspot is still
 * starting.
 *
 * The hotspot is car-controlled. Across five cold boots on the ECARX 753 it became enabled anywhere
 * between boot+27 s and boot+1239 s, so the wait can only be bounded, never predicted: after
 * [BOOT_READY_GATE_MILLIS] the launch continues anyway and the controller's unchanged hotspot
 * readiness handles the rest.
 *
 * Only the boot auto-start path waits. User-initiated connects, settings return, USB-to-wireless
 * switching, notification/dashboard screen intents and the reconnect-after-loss path never reach
 * this object: [BootCarPlayLaunch] is only called by [BootReceiver].
 */
internal object BootReadinessGate {
    /**
     * Hard cap for the boot wait. The car's AP start is not under DiPlay's control: measured
     * AP-enabled moments ranged from boot+27 s to boot+1239 s across five cold boots, so a longer
     * wait would delay the success path that the controller can still complete on its own.
     */
    const val BOOT_READY_GATE_MILLIS = 20_000L

    private const val TAG = "diplay-boot"
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Only a car-hotspot run has an AP to wait for. An existing Wi-Fi start (and every other
     * backend, which cannot take the direct path) keeps its unchanged timing.
     */
    fun waits(mode: WirelessHotspotMode): Boolean = mode == WirelessHotspotMode.MANUAL

    /** Fail-open: everything except a cancelled wait opens the screen. */
    fun opens(outcome: CarHotspotReady.Outcome): Boolean = outcome.reason != CarHotspotReady.Reason.CANCELLED

    /**
     * Runs the wait on a worker thread and calls [onDone] on the main thread exactly once.
     * [await] is a seam for tests; production waits through [CarHotspotReady].
     */
    fun awaitAsync(
        context: Context,
        mode: WirelessHotspotMode,
        isCurrent: () -> Boolean = { true },
        await: (Context, () -> Boolean) -> CarHotspotReady.Outcome = ::awaitHotspot,
        onDone: (CarHotspotReady.Outcome) -> Unit,
    ) {
        val app = context.applicationContext
        Thread({
            val outcome = try {
                await(app, isCurrent)
            } catch (error: Throwable) {
                Log.w(TAG, "Boot readiness gate failed; opening anyway", error)
                CarHotspotReady.Outcome(CarHotspotReady.Reason.ERROR, 0, null, null)
            }
            record(app, mode, outcome)
            main.post { onDone(outcome) }
        }, "diplay-boot-gate").apply { isDaemon = true }.start()
    }

    private fun awaitHotspot(context: Context, isCurrent: () -> Boolean): CarHotspotReady.Outcome =
        CarHotspotReady.await(
            context, BOOT_READY_GATE_MILLIS, isCurrent, { Log.d(TAG, it) },
            // Same saved setting the controller's PMS-style AP path uses (#573): without it the gate
            // could not confirm an AP the platform never reports as a Wi-Fi tether.
            configuredInterface = AirPlayPersistence.loadManualHotspotInterface(context),
        )

    /** Exactly one startup-timeline line per boot launch (`BOOT_READY_GATE`, tag `DiPlay-Startup`). */
    private fun record(context: Context, mode: WirelessHotspotMode, outcome: CarHotspotReady.Outcome) {
        CarPlayStartupTimeline.recordBootReadyGate(
            context, mode, outcome.waitedMs, outcome.reason.name.lowercase(), outcome.apEnabled, outcome.address,
        )
    }
}
