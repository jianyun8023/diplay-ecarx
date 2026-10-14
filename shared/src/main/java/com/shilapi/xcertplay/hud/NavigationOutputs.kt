package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/**
 * Nonblocking boundary between phone control messages and the head unit's instrument-cluster service.
 *
 * ECARX-only: the one cluster channel is the XUI DIM API (see [EcarxHudBridge]). The unit of work is
 * still a [NavigationOutputWorker] so that a slow or blocked platform call can never stall the iAP2
 * reader thread that feeds [onFrame].
 */
object NavigationOutputs {
    /** Whether the ECARX DIM path is wired on this head unit. */
    @Volatile private var useEcarx = false

    private val ecarx = NavigationOutputWorker("diplay-ecarx-output", EcarxHudBridge::clear)

    /**
     * Re-assert the ECARX cluster switches when the app opens, even before a phone connects, so a
     * head unit that was left with the projection switch on restores it on the next app open.
     *
     * This is the call that matters after an ignition cycle: the cluster service dies with the
     * ignition, and whoever opens DiPlay next re-arms the watch that puts the mirror back.
     */
    fun onAppOpened(context: Context) {
        val app = context.applicationContext
        if (!EcarxOutputSettings.available(app)) return
        if (EcarxOutputSettings.enabled(app) || EcarxOutputSettings.presentationSwitch(app)) {
            EcarxHudBridge.initialize(app)
        }
    }

    fun start(context: Context) {
        val app = context.applicationContext
        useEcarx = EcarxOutputSettings.available(app)
        if (useEcarx) ecarx.start { EcarxHudBridge.initialize(app) }
    }

    /**
     * An instrument-cluster switch changed; applies at once. Restarts the worker so a switch
     * flipped before the first session still takes effect.
     *
     * The DIM bridge is initialized even when the guidance output is off, because the projection
     * switch is a separate feature and it is the bridge that owns its retry watch.
     */
    fun ecarxChanged(context: Context) {
        val app = context.applicationContext
        if (!EcarxOutputSettings.available(app)) return
        useEcarx = true
        EcarxHudBridge.initialize(app)
        if (EcarxOutputSettings.enabled(app)) ecarx.start { EcarxHudBridge.initialize(app) } else ecarx.clear()
    }

    /**
     * Routes the cluster bridge's own lines into the session report. Called once the session log
     * exists — see `AndroidMediaSink`'s `onAudioDiagnostic` for the same seam on the audio side.
     *
     * Without it the whole HUD story ("did the call even happen, and on which channel") exists only
     * in logcat, which a car has no way to return.
     */
    fun attachReport(sink: (String) -> Unit) = EcarxHudBridge.attachReport(sink)

    internal fun onFrame(frame: Iap2Frame) {
        if (frame.messageId != RouteGuidanceState.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != RouteGuidanceState.ROUTE_GUIDANCE_MANEUVER_UPDATE
        ) {
            return
        }
        val owned = frame // Iap2Frame is immutable and defensively copies its payload.
        if (useEcarx) ecarx.submit { EcarxHudBridge.onFrame(owned) }
    }

    /** Best effort while alive; Android does not guarantee callbacks before force-stop. */
    fun endNow() {
        ecarx.clear()
    }
}
