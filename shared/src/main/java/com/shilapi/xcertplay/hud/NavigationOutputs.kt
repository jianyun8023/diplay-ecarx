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
    /** Whether identity detection selected ECARX; actual DIM handles belong to the bridge. */
    @Volatile private var useEcarx = false
    @Volatile private var currentSession: (() -> Boolean)? = null

    private val ecarx = NavigationOutputWorker("diplay-ecarx-output", EcarxHudBridge::clear)

    /**
     * Initialize the bridge before a phone connects. It clears an old projection request rather
     * than restoring the platform mirror before the host has a CarPlay window (fix37).
     */
    fun onAppOpened(context: Context) {
        val app = context.applicationContext
        if (!EcarxCapabilities.detect(app).identified) return
        if (EcarxOutputSettings.enabled(app) || EcarxOutputSettings.presentationSwitch(app)) {
            EcarxHudBridge.initialize(app)
        }
    }

    fun start(context: Context) {
        val app = context.applicationContext
        useEcarx = EcarxCapabilities.detect(app).identified
        if (useEcarx) {
            ecarx.start {
                EcarxHudBridge.initialize(app)
                EcarxHudBridge.setCurrentSession(currentSession)
            }
        }
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
        if (!EcarxCapabilities.detect(app).identified) return
        useEcarx = true
        EcarxHudBridge.initialize(app)
        if (EcarxOutputSettings.enabled(app)) {
            ecarx.start {
                EcarxHudBridge.initialize(app)
                EcarxHudBridge.setCurrentSession(currentSession)
            }
            ecarx.submit(EcarxHudBridge::settingsChanged)
        } else ecarx.clear()
    }

    /**
     * Shared entry for settings and the host window. The bridge owns the saved request, runtime
     * intent and retry watch; acceptance does not prove the instrument panel is displaying it.
     */
    fun requestPresentation(context: Context, show: Boolean, origin: String = ""): Boolean {
        EcarxHudBridge.initialize(context)
        return EcarxHudBridge.applyPresentation(show, origin).ok
    }

    fun diagnosticReport(context: Context): String = EcarxHudBridge.diagnostics(context)

    /**
     * Routes the cluster bridges' own lines into the session report. Called once the session log
     * exists — see `AndroidMediaSink`'s `onAudioDiagnostic` for the same seam on the audio side.
     *
     * Without it the whole cluster story ("did the call even happen, and on which channel") exists
     * only in logcat, which a car has no way to return.
     */
    fun attachReport(sink: (String) -> Unit) {
        EcarxHudBridge.attachReport(sink)
    }

    /**
     * The retained ECARX session owns the instrument-cluster window, independently of Activities.
     * Registration replays the current route intent; `null` unregisters.
     *
     * The owner is called from the DIM worker thread with the bridge's lock held, so it must take
     * the request and return: it is "a route wants the panel", not "the panel is up now". This seam
     * exists because [EcarxHudBridge] is internal to this module while the activity is not.
     */
    fun setClusterPanelOwner(owner: ((Boolean) -> Unit)?) {
        EcarxHudBridge.setClusterPanelOwner(owner)
    }

    fun clearClusterPanelOwner(expected: (Boolean) -> Unit) {
        EcarxHudBridge.clearClusterPanelOwner(expected)
    }

    internal fun onFrame(frame: Iap2Frame, source: String = "unspecified") {
        if (frame.messageId != RouteGuidanceState.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != RouteGuidanceState.ROUTE_GUIDANCE_MANEUVER_UPDATE &&
            frame.messageId != 0x5204
        ) {
            return
        }
        val owned = frame // Iap2Frame is immutable and defensively copies its payload.
        if (useEcarx) ecarx.submit { EcarxHudBridge.onFrame(owned, source) }
    }

    /** Route-update silence is not a disconnect; the transport owns this liveness check. */
    internal fun onSessionActive(isCurrent: () -> Boolean) {
        currentSession = isCurrent
        if (useEcarx) ecarx.submit { EcarxHudBridge.setCurrentSession(isCurrent) }
    }

    /** Best effort while alive; Android does not guarantee callbacks before force-stop. */
    fun endNow() {
        currentSession = null
        ecarx.clear()
    }
}
