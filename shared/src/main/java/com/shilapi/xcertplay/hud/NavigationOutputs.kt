package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.media.CarPlayNowPlaying

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
        // The media card has its own switch and its own session-independent life, so it is armed on
        // its own condition rather than being folded into the guidance one below.
        if (EcarxOutputSettings.mediaEnabled(app) && MediaOutputBackendSettings.dimAllowed(app)) EcarxMediaBridge.initialize(app)
        if (EcarxOutputSettings.enabled(app) || EcarxOutputSettings.presentationSwitch(app)) {
            EcarxHudBridge.initialize(app)
        }
    }

    fun start(context: Context) {
        val app = context.applicationContext
        useEcarx = EcarxOutputSettings.available(app)
        if (useEcarx) {
            ecarx.start { EcarxHudBridge.initialize(app) }
            // Same condition as [onAppOpened]: with the card's switch off there is nothing to send,
            // yet initialize() would still create the DIM handle and start its 1 Hz ticker — the
            // ticker's own check stops the push, not the wasted binder traffic. Turning the switch
            // on calls [ecarxChanged], which initializes on demand, so nothing is lost by skipping.
            if (EcarxOutputSettings.mediaEnabled(app) && MediaOutputBackendSettings.dimAllowed(app)) EcarxMediaBridge.initialize(app)
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
        if (!EcarxOutputSettings.available(app)) return
        useEcarx = true
        EcarxHudBridge.initialize(app)
        // Gated on the card's own switch, same as [onAppOpened] and [start]. This is also the path
        // that turns the card back on, so it has to stay a plain check rather than a removal.
        if (EcarxOutputSettings.mediaEnabled(app) && MediaOutputBackendSettings.dimAllowed(app)) EcarxMediaBridge.initialize(app)
        if (EcarxOutputSettings.enabled(app)) {
            ecarx.start { EcarxHudBridge.initialize(app) }
            ecarx.submit(EcarxHudBridge::settingsChanged)
        } else ecarx.clear()
    }

    /**
     * The iPhone's retained now-playing state, for the cluster's media card. May run on any thread.
     *
     * A separate entry point from [onFrame] on purpose: guidance and media are different DIM
     * channels behind different switches, and a fault in one must not take the other down.
     */
    /** Called off the UI thread after a previous MediaCenter registration is confirmed cleaned. */
    fun mediaBackendReady(context: Context) {
        val app = context.applicationContext
        if (EcarxOutputSettings.mediaEnabled(app) && MediaOutputBackendSettings.dimAllowed(app)) EcarxMediaBridge.initialize(app)
    }

    fun onNowPlaying(now: CarPlayNowPlaying, artwork: android.net.Uri? = null) = EcarxMediaBridge.onNowPlaying(now, artwork)

    /**
     * Routes the cluster bridges' own lines into the session report. Called once the session log
     * exists — see `AndroidMediaSink`'s `onAudioDiagnostic` for the same seam on the audio side.
     *
     * Without it the whole cluster story ("did the call even happen, and on which channel") exists
     * only in logcat, which a car has no way to return.
     */
    fun attachReport(sink: (String) -> Unit) {
        EcarxHudBridge.attachReport(sink)
        EcarxMediaBridge.attachReport(sink)
    }

    /**
     * Hands the cluster panel to the only component that can open a window on it — the host
     * activity, whose `EcarxClusterPresentation` shows CarPlay's instrument-cluster stream there.
     * `null` unregisters.
     *
     * The owner is called from the DIM worker thread with the bridge's lock held, so it must take
     * the request and return: it is "a route wants the panel", not "the panel is up now". This seam
     * exists because [EcarxHudBridge] is internal to this module while the activity is not.
     */
    fun setClusterPanelOwner(owner: ((Boolean) -> Unit)?) {
        EcarxHudBridge.clusterPresentationRequested = owner
    }

    internal fun onFrame(frame: Iap2Frame) {
        if (frame.messageId != RouteGuidanceState.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != RouteGuidanceState.ROUTE_GUIDANCE_MANEUVER_UPDATE &&
            frame.messageId != 0x5204
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
