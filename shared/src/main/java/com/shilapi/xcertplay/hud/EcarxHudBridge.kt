package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.iap2.message.Iap2LaneGuidance
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Publishes CarPlay guidance to an ECARX (亿咖通) instrument cluster through the XUI DIM API.
 *
 * The call sequence is the one the shipped ECARX projection tool uses, recovered from its
 * bytecode (a Runnable the tool posts every 1000 ms):
 * ```
 * navi.updateNavigationInfo(info); navi.updateTurnByTurnArrow(v++);
 * ```
 * so the field order and the 1 s cadence here are not invented.
 *
 * What is proven vs. what needs calibration on the car:
 *  * **Proven.** `updateNavigationInfo` with the 15-getter `INavigationInfo` payload; the getter
 *    set is exhaustive (the tool's implementation class implements exactly these); `updateDistanceToDestination`,
 *    `updateDistanceToNextGuidancePoint`, `updateETA`, `updateNextGuidancePointName`,
 *    `updateLaneInfo`, `notifyTurnByTurnStarted/Stopped` take the arguments used below.
 *  * **Proven — and the arrow is drawn, not coded.** The head unit's own navigation app
 *    (`ecarx.naviservice`, the shipped 导航) renders the manoeuvre into SVG markup in
 *    `ecarx/naviservice/widget/f.smali` and pushes it through `updateTurnByTurnArrow(String)`
 *    (`ecarx/naviservice/a/g.smali`, log tag `NaviDashboardManager`); it never calls the `(Bitmap)`
 *    overload, and it skips the `int` overload entirely when the code is `<= 0`. So the SVG
 *    overload is the primary path here ([turnArrowSvg]); a locally drawn bitmap is the fallback for
 *    a head unit that lacks it, and the recovered code table ([EcarxArrowCode]) the last resort.
 *  * **Needs calibration.** `getDrivingDirection()` is a bare `int` whose scale was never
 *    recovered, so it carries [DRIVING_DIRECTION_UNKNOWN] instead of Apple's flag dressed up as
 *    data. The arrow is still gated behind [EcarxOutputSettings.turnArrowExperimental] until the
 *    car confirms what the cluster actually draws.
 *
 * Every ECARX call is reflection-only (see [EcarxDimApi]): a direct reference would turn a
 * missing system class into a `NoClassDefFoundError` on every non-ECARX head unit.
 */
internal object EcarxHudBridge {
    private const val TAG = "DiPlay-ECARX-HUD"

    /** Re-send even when nothing changed, so the cluster never quietly drops the card. */
    private const val KEEPALIVE_TICKS = 5

    /** How many lines are held for a report that has not attached yet; see [report]. */
    private const val REPORT_BACKLOG = 24

    /**
     * `getNavigationStatus()` while guidance is live. The toolkit copies its own value through
     * unchanged, so the two states are the only ones recovered; 0 is used for idle on teardown.
     */
    private const val NAVI_STATUS_ACTIVE = 1
    private const val NAVI_STATUS_IDLE = 0

    /**
     * `drivingDirection` is deliberately left at 0. The field is a plain `int` in `s1/r`, its
     * source value is the toolkit's own, and no scale was recovered — passing Apple's
     * left/right-hand-traffic flag here would be a guess dressed up as data.
     */
    private const val DRIVING_DIRECTION_UNKNOWN = 0

    /**
     * The cluster's SVG dial: the markup skeleton, byte for byte, that the head unit's navigation
     * app hands to `updateTurnByTurnArrow(String)`.
     *
     * Recovered from `ecarx/naviservice/widget/f.smali` — the document template, the per-path
     * template, and the two layers it fills them with:
     *  * layer 1 (`widget/f.smali`, `.line 83`): the `rgb(88,90,102)` edge at `stroke-width`
     *    **28.235294** (`Float(0x41e1e1e2)`), with the **same colour in `fill`** — this is a filled
     *    shape, not a hairline outline;
     *  * layer 2 (`.line 122`): the `rgb(255,255,255)` core, `stroke-width` the **string `"3"`**,
     *    again with the same colour in `fill`.
     * Both `d` values are closed — `Z` is part of this template, not of the caller's data — and the
     * canvas is 255×255, from the `Integer(0xff)` pair the format call passes. The two `%s` in the
     * document are the two `<path>` elements, in that order.
     *
     * The two spaces after `<path` are the template's, and reproducing the markup exactly is the
     * whole point: the cluster parses it itself, and a previous version of this file got the two
     * `fill` values, the core width and the geometry wrong (see [arrowDrawing]).
     */
    private const val SVG_DOC =
        "<svg width=\"%s\" height=\"%s\" xmlns=\"http://www.w3.org/2000/svg\">\n   %s   %s</svg>"
    private const val SVG_PATH = "<path  d=\"%sZ\" stroke-width=\"%s\" stroke=\"%s\" fill=\"%s\"/>\n"
    private const val SVG_SIZE = 255
    private const val SVG_EDGE = "rgb(88,90,102)"
    private const val SVG_CORE = "rgb(255,255,255)"
    /** The recovered value is a `float` and the stock app formats it as one. */
    private const val SVG_EDGE_WIDTH = "28.235294"
    /** The recovered value is a string literal, not a float. */
    private const val SVG_CORE_WIDTH = "3"
    private const val SVG_HALF = SVG_SIZE / 2f
    private const val SVG_RADIUS = SVG_SIZE * 0.34f
    /** The shapes are written in `[-100, 100]`; this maps that onto the 255 canvas. */
    private const val SVG_SCALE = SVG_RADIUS / 100f

    /**
     * Shape proportions, in the same `[-100, 100]` space the drawings are written in.
     *
     * [STEM_X] is the stem's offset from the centre line, which is what gives a turn somewhere to
     * bend to; [STEM_BOTTOM] is deliberately near the bottom edge so the stem leaves the canvas the
     * way a road leaves a junction.
     */
    private const val STEM_X = 28
    private const val STEM_BOTTOM = 112
    private const val ARM_LENGTH = 74
    private const val HEAD_LENGTH = 52
    private const val HEAD_HALF = 38
    /** Where the bend sits for a 45/90/135-degree turn, highest first. */
    private const val SLIGHT_CORNER_Y = -6
    private const val TURN_CORNER_Y = -26
    private const val SHARP_CORNER_Y = -54
    /** A U-turn is wide enough for its own band: the half-circle's radius is the leg offset. */
    private const val UTURN_LEG = 46
    private const val UTURN_CORNER_Y = -30
    private const val UTURN_ARM_Y = 30
    /** Roundabout ring, and the pin of the destination marker. */
    private const val RING_CENTRE_Y = 26
    private const val RING_RADIUS = 72
    private const val PIN_CENTRE_Y = -24
    private const val PIN_RADIUS = 48
    private const val PIN_SHOULDER = 34
    private const val PIN_TIP_Y = 78

    /** Points per arc. Twelve is smooth at this size and keeps the markup short. */
    private const val ARC_STEPS = 12

    /** Reported when every arrow overload this head unit declares refused the drawing. */
    private const val ARROW_OFF = "off"

    /**
     * The projection watch. `setPresentationToDimSwitch` is a **runtime** call, not a saved
     * setting: the cluster service is torn down with the ignition and comes back with it, so a
     * single call at app start is thrown away on a car that is only on accessory power — which is
     * exactly how a head unit boots. Measured on the car (领克 DCY11): with the engine off the
     * cluster is absent and the call does nothing, and nothing re-sent it once the engine started.
     *
     * So the bridge keeps re-asserting the switch: once every [PRESENTATION_FAST_SECONDS] until
     * [PRESENTATION_FAST_ATTEMPTS] tries are spent (the cluster usually shows up as soon as the
     * engine does), then every [PRESENTATION_SLOW_SECONDS] for as long as the user leaves the
     * switch on. Both are cheap `void` binder calls, and the slow phase is what covers "the driver
     * started the car ten minutes after the head unit woke up".
     */
    private const val PRESENTATION_FAST_SECONDS = 2
    private const val PRESENTATION_FAST_ATTEMPTS = 30
    private const val PRESENTATION_SLOW_SECONDS = 10

    private val lock = Any()
    private val route = RouteGuidanceState()
    private var context: Context? = null
    private var ecarxDetected = false
    private var dim: Any? = null
    private var navi: Any? = null
    private var protocol: Any? = null
    /** Non-null once the DIM can reach us; the proxies are held so the platform cannot collect them. */
    private var dimCallback: Any? = null
    private var naviCallback: Any? = null
    /** Where the session report gets its HUD lines; see [attachReport]. */
    private var reportSink: ((String) -> Unit)? = null
    /** Lines logged before a report existed, replayed by [attachReport] so the early state survives. */
    private val unreported = ArrayDeque<String>()
    private var tickerStarted = false
    private var displayWatchRegistered = false

    /** The saved projection intent, and how many ticks are left before it is re-asserted. */
    private var presentationWanted = false
    private var presentationCountdown = 0
    private var presentationAttempts = 0

    /**
     * What the last accepted `setPresentationToDimSwitch` named — the empty string while a mirror is
     * open, null when none is. Printed by [diagnostics] so a report can tell "the switch is off" from
     * "the switch is off but the platform still has a session open".
     */
    private var presentedPackage: String? = null

    private var lastPushed: EcarxDimApi.Guidance? = null
    private var lanes: List<EcarxDimApi.Lane>? = null
    private var lastLanePushed: List<EcarxDimApi.Lane>? = null
    /** Which arrow channel the last frame left on, so the report prints the changes and not the ticks. */
    private var lastArrowChannel: String? = null
    private var ticksSincePush = 0
    private var guidanceActive = false
    private var lastGuidanceEnd = "none"

    /**
     * Called with `true` when a route starts and `false` when it ends, for the owner that draws the
     * cluster itself — the CarPlay instrument-cluster stream (display 1, `EcarxClusterPresentation`).
     *
     * A plain `var` and not a listener interface: the only consumer is the host activity, and one
     * slot cannot accumulate a second registration. It is assigned with a **method reference**, never
     * a lambda: a lambda here would shift every following `$$ExternalSyntheticLambda` index in this
     * file and turn a one-line change into an unattributable class diff.
     */
    var clusterPresentationRequested: ((Boolean) -> Unit)? = null

    /**
     * A screen appearing or disappearing is the closest thing to "the car was just started" that a
     * third-party app can observe, so it cancels the backoff instead of making the cluster wait
     * for the next keepalive. On this head unit the cluster screen is not in `getDisplays()` at all
     * (probe v4), so this is a hint, never the mechanism — the keepalive is.
     */
    private val displayWatch = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            nudgeProjection("display $displayId added")
        }

        override fun onDisplayRemoved(displayId: Int) {
            nudgeProjection("display $displayId removed")
        }

        override fun onDisplayChanged(displayId: Int) = Unit
    }

    /** Resolves the DIM handles once per process. Safe to call from any thread; idempotent. */
    fun initialize(appContext: Context) = synchronized(lock) {
        if (context != null) return@synchronized
        val app = appContext.applicationContext
        context = app
        ecarxDetected = EcarxCapabilities.detect(app).identified
        if (!ecarxDetected) {
            report("ECARX identity absent, output disabled: ${EcarxCapabilities.describe(app)}")
            return@synchronized
        }
        // Read the previous request for the startup reset below, not to restore a mirror.
        presentationWanted = EcarxOutputSettings.presentationSwitch(app)
        reclaimLocked()
        watchDisplaysLocked(app)
        report(
            "DIM ready navi=${navi != null} protocol=${protocol != null} " +
                "dimCb=${dimCallback != null} naviCb=${naviCallback != null} " +
                "projection=$presentationWanted ${EcarxCapabilities.describe(app)}",
        )
        // ★ Nothing is restored here any more (fix37). The switch now means "this panel shows what
        // is on display 1", and at app start nothing of ours is on display 1 — restoring it painted
        // the platform's mirror of the centre screen on the cluster with no route running, over a
        // window that did not exist. A route arms it (see [clusterPresentationRequested]) and the
        // end of a route releases it; the saved value is only brought back in line here.
        if (presentationWanted) {
            presentationWanted = false
            EcarxOutputSettings.setPresentationSwitch(app, false)
            report("projection not restored at start-up; the cluster panel is route-driven")
        }
        startTickerLocked()
    }

    /**
     * Routes the HUD's own lines into the session report as well as logcat.
     *
     * The report file is the only channel a car without adb can return, and a HUD change with no
     * lines in it is unfalsifiable on the road — `Log.i` never reaches the file. The seam is the
     * same one audio already uses (`AndroidMediaSink`'s `onAudioDiagnostic`): a plain sink, owned by
     * the caller, so nothing in this package needs to know what a report is.
     *
     * Idempotent and safe from any thread. Whatever was logged before the first session started is
     * replayed, because [initialize] runs at app open and the report only exists once a phone has
     * connected — which is exactly how the interesting lines would otherwise be lost.
     */
    fun attachReport(sink: (String) -> Unit) = synchronized(lock) {
        reportSink = sink
        EcarxDimApi.attachReport(sink)
        EcarxProjectionChannel.attachReport(sink)
        unreported.forEach(sink)
        unreported.clear()
    }

    /**
     * logcat *and* the report. Report lines deliberately carry no timestamp: the report writer adds
     * one, so a line that formats its own would end up with two.
     */
    private fun report(line: String) {
        Log.i(TAG, line)
        val sink = reportSink
        if (sink != null) {
            sink(line)
            return
        }
        // The report only exists per session; keep the newest lines so the pre-session state — the
        // only place `dimCb`/`arrow` are ever printed — still lands in the first report.
        while (unreported.size >= REPORT_BACKLOG) unreported.removeFirst()
        unreported.addLast(line)
    }

    /**
     * (Re)resolves the DIM handles. Retried by the ticker while anything is still missing:
     * `DimInteraction.create()` hands back `null` until the cluster service is up, and on a car
     * that is switched on with the engine off — the state every head unit boots in — that is
     * exactly what happens. [ecarxDetected] only selects the adapter from identity evidence;
     * these handles can appear later, so the retry is the point.
     */
    private fun reclaimLocked(): Boolean {
        val app = context ?: return false
        val hadNavi = navi != null
        if (dim == null && Looper.myLooper() != Looper.getMainLooper()) {
            // DIM constructs a Handler; never wait for the main thread while holding the HUD lock.
            Handler(Looper.getMainLooper()).post { synchronized(lock) { reclaimLocked() } }
            return false
        }
        if (dim == null) dim = EcarxDimApi.createDimInteraction(app)
        val interaction = dim ?: return false
        if (navi == null) navi = EcarxDimApi.naviInteraction(interaction)
        if (protocol == null) protocol = EcarxDimApi.dimProtocolManager(app)
        // The DIM does not only receive, it asks: the stock navigation app registers a callback and
        // its handler logs `onDoInteractionAction ACTION_GET_FAVORITE`, i.e. the cluster queries the
        // navigation side for data. Without a registration those requests have no receiver on our
        // side and simply time out, so register both handles the stock app registers (`a/g.smali`
        // lines 146 and 149). The proxies are only logged for now — see [EcarxDimApi.DimCallbackHandler].
        if (dimCallback == null) dimCallback = EcarxDimApi.registerInteractionCallback(interaction, "diplay")
        val naviRef = navi
        if (naviRef != null && naviCallback == null) {
            naviCallback = EcarxDimApi.registerNavigationInteractionCallback(naviRef, "diplay")
        }
        // The handles stay null until the cluster service is up, so "the navigation half just
        // appeared" is a state change worth a line of its own: it is the difference between a head
        // unit that was never ready and one that became ready when the engine started. The overload
        // set is included because it is the single field that says whether the cluster is being fed
        // the same channel its own navigation app uses, or a fallback of ours.
        if (navi != null && !hadNavi) {
            report(
                "DIM navi acquired dimCb=${dimCallback != null} naviCb=${naviCallback != null} " +
                    "arrow=${EcarxDimApi.arrowOverloads(navi)} protocol=${protocol != null}",
            )
        }
        return true
    }

    private fun watchDisplaysLocked(app: Context) {
        if (displayWatchRegistered) return
        displayWatchRegistered = runCatching {
            val manager = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            manager.registerDisplayListener(displayWatch, Handler(Looper.getMainLooper()))
            true
        }.getOrElse {
            report("display watch unavailable: ${it.javaClass.simpleName}")
            false
        }
    }

    private fun nudgeProjection(reason: String) = synchronized(lock) {
        if (!presentationWanted) return@synchronized
        report("projection watch: $reason")
        presentationCountdown = 0
    }

    private fun startTickerLocked() {
        if (tickerStarted) return
        tickerStarted = true
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "diplay-ecarx-hud").apply { isDaemon = true }
        }.scheduleAtFixedRate(::tick, 1, 1, TimeUnit.SECONDS)
    }

    fun onFrame(frame: Iap2Frame, source: String = "unspecified") = synchronized(lock) {
        if (!ecarxDetected) return@synchronized
        if (frame.messageId == 0x5204) {
            val parsed = Iap2LaneGuidance.parse(frame.payload) ?: run {
                return@synchronized
            }
            val app = context
            if (parsed.isEmpty() || app == null || !EcarxOutputSettings.enabled(app) ||
                !EcarxOutputSettings.laneGuidanceEnabled(app) || route.current() == null || navi == null) {
                lanes = null
            } else {
                val mapped = parsed.map { lane ->
                    EcarxLaneIcon.forLane(lane)?.let { EcarxDimApi.Lane(lane.index + 1, it) }
                }
                lanes = if (mapped.all { it != null }) mapped.filterNotNull() else null
                if (lanes == null) report("DIM lane unsupported; old strip withdrawn")
            }
            pushLaneLocked(false)
            return@synchronized
        }
        val change = route.accept(frame.messageId, frame.payload)
        report("DIM input source=$source id=0x${frame.messageId.toString(16)} ${route.diagnosticInput} " +
            "change=$change currentNext=${route.current()?.distanceMeters ?: "none"} " +
            "elapsedMs=${SystemClock.elapsedRealtime()} ${route.diagnosticState()}")
        when (change) {
            RouteGuidanceChange.GUIDANCE -> {
                pushLocked(force = false)
                pushLaneLocked(false)
            }
            RouteGuidanceChange.CLEAR -> {
                if (lastPushed != null) endLocked(route.diagnosticState()) else clearLanesLocked()
            }
            RouteGuidanceChange.NONE -> Unit
        }
    }

    fun clear() = synchronized(lock) {
        route.clear() // Stop the keepalive from restoring guidance after cleanup.
        if (lastPushed != null) endLocked("reason=clear-or-session-stop") else {
            clearLanesLocked()
            context?.let { report("factory navigation ${EcarxFactoryNavigationControl.release(it)}") }
        }
    }

    fun settingsChanged() = synchronized(lock) {
        val app = context ?: return@synchronized
        if (!EcarxOutputSettings.pauseFactoryNavigation(app)) {
            report("factory navigation ${EcarxFactoryNavigationControl.release(app)}")
        } else if (lastPushed != null) {
            report("factory navigation ${EcarxFactoryNavigationControl.acquire(app)}")
        }
        if (!EcarxOutputSettings.laneGuidanceEnabled(app)) lanes = null
        pushLaneLocked(false)
    }

    /** Whether guidance is currently on the cluster. */
    fun isActive(): Boolean = synchronized(lock) { lastPushed != null }

    // ---------------------------------------------------------------- OEM switches

    /**
     * The 仪表投屏 switch — mirrors the **centre screen as it is at that moment** onto the cluster.
     *
     * The call is `setPresentationToDimSwitch(1, "", "", show)`: what both shipped ECARX apps send,
     * and the only form measured working on the car. The two package strings stay empty because the platform
     * ignores them — every named variant (`com.autonavi.amapauto`, this app's own package) produced
     * the same picture on the car.
     *
     * Two things are measured now rather than assumed:
     *  * what gets mirrored is **whatever the centre screen is showing when the call lands**. Opening
     *    this switch from the settings screen puts the settings screen on the cluster, which is
     *    exactly what the driver reported as "the whole picture moved over" (10-24 14:32: the switch
     *    was on for 27 s and the driver never left this page);
     *  * the platform answers immediately, in the other direction: `true` makes it publish
     *    `firstFrameReady` on `ihu/events/navigation/ongoing` within 1 ms, `false` makes it publish
     *    `naviAppOff` within 3 ms. Those two events are the **reply**, not the input — which is why
     *    this project no longer publishes anything on that topic.
     *
     * Turning it on records the intent and arms the watch; turning it off releases once. Besides the
     * settings screen, the other caller is the panel's owner: it creates its window first, then
     * enables projection (fix37, see [clusterPresentationRequested]). Both callers use this same
     * saved request and retry state; neither owns a separate projection preference.
     *
     * It does **not** report whether the cluster changed, because nothing can: the platform call is
     * `void`, unchecked, and returns the same thing whether or not the cluster is even powered.
     * [EcarxResult.ok] when enabling means "the watch owns delivery now", even if a DIM handle is
     * not ready. Disabling returns the immediate platform result but always clears the request.
     */
    fun applyPresentation(show: Boolean, origin: String = ""): EcarxDimApi.EcarxResult = synchronized(lock) {
        // Who asked. The 18:24 report shows the mirror being turned *off* half a second after the
        // process started, while the settings screen held "on" — and nothing in this file can tell
        // the difference between the driver, a dialog that cancelled itself, and a switch the
        // settings screen rebuilt. This line is what separates them next time.
        report("presentation request show=$show origin=$origin")
        val app = context ?: return EcarxDimApi.EcarxResult.failed("DIM not initialized")
        EcarxOutputSettings.setPresentationSwitch(app, show)
        presentationWanted = show
        if (!show) {
            presentationAttempts = 0
            presentationCountdown = 0
            val result = applyPresentationLocked(false)
            resendLocked()
            return result
        }
        if (!ecarxDetected) return EcarxDimApi.EcarxResult.failed("ECARX head unit not detected")
        requestPresentationLocked()
        EcarxDimApi.EcarxResult.ok("projection watch armed")
    }

    /** One attempt now and a fresh fast phase, so a manual toggle is never throttled by the watch. */
    private fun requestPresentationLocked(): EcarxDimApi.EcarxResult {
        val result = applyPresentationLocked(true)
        presentationAttempts = 1
        // A package change armed its own wait in [applyPresentationLocked]; do not shorten it, or the
        // release and the re-open land in the same tick and the platform coalesces them.
        if (presentationCountdown < PRESENTATION_FAST_SECONDS - 1) {
            presentationCountdown = PRESENTATION_FAST_SECONDS - 1
        }
        report("projection armed; first attempt ${result.reason}")
        return result
    }

    private fun applyPresentationLocked(show: Boolean): EcarxDimApi.EcarxResult {
        val interaction = dim ?: return EcarxDimApi.EcarxResult.failed("DimInteraction missing")
        val packageName = EcarxOutputSettings.PRESENTATION_PACKAGE
        val result = EcarxDimApi.setPresentationToDimSwitch(interaction, show, packageName = packageName)
        if (!result.ok) {
            report("presentation switch failed: ${result.reason}")
            return result
        }
        presentedPackage = if (show) packageName else null
        report("presentation switch -> $show package=\"$packageName\"")
        return result
    }

    /**
     * Re-sends what the cluster should be showing after something else cleared it.
     *
     * Only the delivery bookkeeping is reset, never the guidance: [lastArrowChannel] is what
     * suppresses a repeat of an unchanged arrow and [ticksSincePush] is what schedules the
     * keepalive. Releasing the mirror makes the cluster hand its navigation card back to the head
     * unit, so the arrow leaves the screen while the value it carries never changed — the same
     * frames have to go out again for the card to be re-pointed at the phone.
     */
    private fun resendLocked() {
        if (lastPushed == null) return
        lastArrowChannel = null
        ticksSincePush = 0
        pushLocked(force = true)
        report("DIM re-sent after the mirror was released")
    }

    // ---------------------------------------------------------------- push path

    private fun tick() = synchronized(lock) {
        if (!ecarxDetected) return@synchronized
        // The cluster service is not up when the head unit is only on accessory power, so keep
        // asking for the handles as well as for the projection itself.
        if (dim == null || navi == null) reclaimLocked()
        watchProjectionLocked()
        // Guidance can expire without a frame (a list that stays empty), so check every second.
        if (route.current() == null && (lastPushed != null || lanes != null || lastLanePushed != null)) {
            if (lastPushed != null) endLocked(route.diagnosticState()) else clearLanesLocked()
        }
        else if (++ticksSincePush >= KEEPALIVE_TICKS) {
            pushLocked(force = true)
            pushLaneLocked(true)
        }
    }

    /**
     * Re-asserts the projection switch until the cluster is up, then keeps it alive.
     *
     * Nothing on this platform reports that the projection took: `getShowPresentationOption()` is
     * the only read-back and it is `-1` on this head unit even while the cluster is mirroring
     * (probe v3/v4, nine reads), so a read-back loop would immediately give up. Re-sending the
     * switch is idempotent and harmless, which is why the watch is unconditional instead.
     */
    private fun watchProjectionLocked() {
        if (!presentationWanted) return
        if (presentationCountdown > 0) {
            presentationCountdown -= 1
            return
        }
        val fast = presentationAttempts < PRESENTATION_FAST_ATTEMPTS
        val interval = if (fast) PRESENTATION_FAST_SECONDS else PRESENTATION_SLOW_SECONDS
        // The ticker runs once a second, so the countdown is a plain seconds counter.
        presentationCountdown = interval - 1
        val result = applyPresentationLocked(true)
        presentationAttempts += 1
        // One line at the start of each phase and then once a minute, not once a second.
        if (presentationAttempts == 1 || !fast && presentationAttempts % 6 == 0) {
            report("projection re-asserted #$presentationAttempts every ${interval}s (${result.reason})")
        }
    }

    private fun pushLocked(force: Boolean) {
        val app = context ?: return
        if (!EcarxOutputSettings.enabled(app)) {
            if (lastPushed != null) endLocked("reason=setting-disabled")
            return
        }
        val naviRef = navi ?: return
        val maneuver = route.current()
        if (maneuver == null) {
            if (lastPushed != null) endLocked(route.diagnosticState())
            return
        }
        val guidance = guidanceFor(maneuver)
        if (!force && guidance == lastPushed) return
        val firstFrame = lastPushed == null
        if (firstFrame) report("factory navigation ${EcarxFactoryNavigationControl.acquire(app)}")

        // Order matches the toolkit: the aggregate payload first, then the discrete fields.
        val result = EcarxDimApi.updateNavigationInfo(naviRef, guidance)
        if (!result.ok) {
            report("updateNavigationInfo failed next=${maneuver.distanceMeters}m: ${result.reason}")
            if (firstFrame) report("factory navigation ${EcarxFactoryNavigationControl.release(app)}")
            return
        }
        if (firstFrame) {
            val started = EcarxDimApi.notifyTurnByTurnStarted(naviRef)
            report("DIM lifecycle start status=$NAVI_STATUS_ACTIVE aggregate=${outcome(result)} " +
                "notify=${outcome(started)} detail=${started.reason}")
        }
        // The `void` notification above has never been confirmed to reach the service. Publish the
        // event the service itself would have published, so the cluster is told a navigation exists
        // even if that call went nowhere; see EcarxProjectionChannel.setNavigationOngoing.
        if (firstFrame && EcarxOutputSettings.naviCardEvent(app)) {
            EcarxProjectionChannel.setNavigationOngoing(true, maneuver.road)
        }
        val nextPoint = EcarxDimApi.updateDistanceToNextGuidancePoint(naviRef, maneuver.distanceMeters.toLong())
        val remaining = maneuver.remainingMeters?.let { EcarxDimApi.updateDistanceToDestination(naviRef, it) }
        val eta = maneuver.arrivalEpochSeconds?.let { EcarxDimApi.updateEta(naviRef, it) }
        maneuver.road.takeIf { it.isNotEmpty() }
            ?.let { EcarxDimApi.updateNextGuidancePointName(naviRef, it) }
        if (EcarxOutputSettings.turnArrowExperimental(app)) {
            // The arrow goes to the cluster's own navigation page, which the head unit renders as
            // soon as the 仪表投屏 switch is on — so nothing here has to claim the phone as the
            // cluster's navigation source. That claim is the one that turns the page into a
            // rectangle of mirrored CarPlay; it stays off unless the driver asks for it. See
            // EcarxOutputSettings.navigationProjection.
            if (EcarxOutputSettings.navigationProjection(app)) {
                EcarxProjectionChannel.setNavigationProjection(true)
            }
            val channel = pushArrowLocked(naviRef, maneuver, guidance)
            if (channel != lastArrowChannel) {
                lastArrowChannel = channel
                report(
                    "DIM arrow=$channel arrowId=${guidance.turnId} " +
                        "appleType=${maneuver.type} side=${maneuver.drivingSide}",
                )
            }
        }

        lastPushed = guidance
        ticksSincePush = 0
        report("DIM publish next=${maneuver.distanceMeters}m arrowId=${guidance.turnId} " +
            "status=${guidance.status} force=$force first=$firstFrame aggregate=${outcome(result)} " +
            "next=${outcome(nextPoint)} destination=${outcome(remaining)} eta=${outcome(eta)} " +
            "elapsedMs=${SystemClock.elapsedRealtime()}")
        if (!guidanceActive) {
            guidanceActive = true
            report(
                "DIM guidance started arrowId=${guidance.turnId} next=${maneuver.distanceMeters}m " +
                    "road=\"${maneuver.road}\" elapsedMs=${SystemClock.elapsedRealtime()} ${route.diagnosticState()}",
            )
            // Every one of these calls has always been fired and its answer thrown away, so a
            // field the cluster never received looked exactly like one the cluster ignored. The
            // 16:10 comparison showed the head unit's own app publishing `distance` while this
            // project never did — one line per guidance start now says whether that field was
            // absent from the phone, refused by the platform, or accepted and then dropped.
            report(
                "DIM fields next=${outcome(nextPoint)} " +
                    "distance=${outcome(remaining)}:${maneuver.remainingMeters ?: -1}m " +
                    "eta=${outcome(eta)}:at=${maneuver.arrivalEpochSeconds ?: -1} " +
                    "raw=${maneuver.etaRawSeconds ?: -1}",
            )
            // ★ fix34 removed the guard that used to sit on the next line, and the mirror came back
            // (fix35 reports): the platform opens its navigation page on display 1 as a *native*
            // reaction to the guidance update. fix36 then answered it with `false`, which turned out
            // to detach the panel from display 1 entirely — our window went up and the driver saw the
            // stock cluster (report 7, 17:22:18). fix37 leaves the switch alone at a guidance start:
            // the platform's own `ongoingNavi` is what puts the cluster into navigation, and the
            // panel's owner turns the switch *on* once its window exists.
            report("DIM mirror left to the platform at guidance start (wanted=$presentationWanted)")
            // The cluster's own display (HDMI, id 1, 1920x720) gets CarPlay's instrument-cluster
            // stream instead of a scaled-down copy of the centre screen. Fired here and not from
            // the ticker so it lands with the first guidance frame, before the platform's mirror
            // has had time to paint a wrong-ratio picture on the same panel.
            clusterPresentationRequested?.invoke(true)
        }
    }

    private fun pushLaneLocked(force: Boolean) {
        val naviRef = navi ?: return
        val wanted = lanes
        if (wanted == null) {
            if (lastLanePushed != null) {
                val result = EcarxDimApi.clearLaneInfo(naviRef)
                report("DIM lane clear ${result.reason}")
                if (result.ok) lastLanePushed = null
            }
            return
        }
        if (lastPushed == null || route.current() == null || !EcarxOutputSettings.laneGuidanceEnabled(context ?: return)) return
        if (!force && wanted == lastLanePushed) return
        val result = EcarxDimApi.updateLaneInfo(naviRef, wanted)
        report("DIM lane ${result.reason}")
        if (result.ok) lastLanePushed = wanted
    }

    private fun clearLanesLocked(force: Boolean = false) {
        if (!force && lanes == null && lastLanePushed == null) return
        lanes = null
        val naviRef = navi ?: return
        val result = EcarxDimApi.clearLaneInfo(naviRef)
        if (lastLanePushed != null || !result.ok) report("DIM lane clear ${result.reason}")
        if (result.ok) lastLanePushed = null
    }

    private fun outcome(result: EcarxDimApi.EcarxResult?): String = when {
        result == null -> "absent"
        result.ok -> "ok"
        else -> "failed"
    }

    /**
     * Sends the next-turn arrow on the best channel this head unit actually offers, and names the one
     * that took, for the report.
     *
     * The numeric turn id goes first, and it is the only channel whose result can be trusted at all:
     * every overload here is a `void` binder call, so `ok` means "the method existed and the binder
     * accepted it", never "the cluster drew it". With the drawing channels ordered first, a call that
     * was accepted and silently ignored would end the cascade — which is exactly what this project
     * spent weeks chasing. The id channel is the one the car has been observed to render (ids 101–143,
     * see [EcarxArrowCode]), so it is asked first and the drawings are kept as the answer for a head
     * unit that lacks `updateTurnByTurnArrow(int)`.
     */
    private fun pushArrowLocked(
        navi: Any,
        maneuver: AppleManeuver,
        guidance: EcarxDimApi.Guidance,
    ): String {
        val id = guidance.turnId
        if (id > 0 && EcarxDimApi.updateTurnByTurnArrow(navi, id).ok) return "id:$id"
        val svg = turnArrowSvg(maneuver.type, maneuver.drivingSide)
        if (svg != null && EcarxDimApi.updateTurnByTurnArrow(navi, svg).ok) return "svg:${svg.length}B"
        val bitmap = turnArrowBitmap(maneuver.type, maneuver.drivingSide)
        if (bitmap != null && EcarxDimApi.updateTurnByTurnArrow(navi, bitmap).ok) return "bitmap"
        return ARROW_OFF
    }

    private fun endLocked(reason: String) {
        lastGuidanceEnd = "$reason elapsedMs=${SystemClock.elapsedRealtime()}"
        lanes = null
        val naviRef = navi
        if (naviRef != null) {
            // The recovered SDK's Stop only clears lanes/exits; idle resets its cached active state for the next start.
            val idle = EcarxDimApi.updateNavigationInfo(naviRef, EcarxDimApi.Guidance(
                status = NAVI_STATUS_IDLE,
                distanceToNextMeters = 0,
                distanceToDestinationMeters = 0,
                etaEpochSeconds = 0,
            ))
            val stopped = EcarxDimApi.notifyTurnByTurnStopped(naviRef)
            report("DIM lifecycle stop status=$NAVI_STATUS_IDLE aggregate=${outcome(idle)} " +
                "notify=${outcome(stopped)} aggregateDetail=${idle.reason} notifyDetail=${stopped.reason}")
            if (context?.let(EcarxOutputSettings::naviCardEvent) == true) {
                EcarxProjectionChannel.setNavigationOngoing(false, "")
            }
            if (context?.let(EcarxOutputSettings::turnArrowExperimental) == true) {
                // Clear through the same family that drew it: the id the stock app reserves for
                // "nothing to show" first, then an empty drawing for a head unit whose only
                // overload is the string one. Whichever overload this head unit lacks just refuses.
                EcarxDimApi.updateTurnByTurnArrow(naviRef, EcarxArrowCode.NO_ARROW)
                EcarxDimApi.updateTurnByTurnArrow(naviRef, "")
            }
            EcarxDimApi.updateDistanceToNextGuidancePoint(naviRef, 0)
            EcarxDimApi.updateNextGuidancePointName(naviRef, "")
            clearLanesLocked(force = true)
        }
        lastPushed = null
        lastArrowChannel = null
        ticksSincePush = 0
        // Hand the cluster's navigation card back. Only after the clear calls above, so the card is
        // blank before it is re-pointed at whatever the head unit wanted to show.
        val app = context
        if (guidanceActive && app != null && EcarxOutputSettings.navigationProjection(app)) {
            EcarxProjectionChannel.setNavigationProjection(false)
        }
        if (guidanceActive) {
            guidanceActive = false
            report("DIM guidance ended $lastGuidanceEnd")
            // Hand the panel back: the presentation stops requesting the cluster stream, so the
            // head unit's own instrument page returns instead of a frozen last map frame.
            clusterPresentationRequested?.invoke(false)
        }
        context?.let { report("factory navigation ${EcarxFactoryNavigationControl.release(it)}") }
    }

    /**
     * One [EcarxDimApi.Guidance] per maneuver. Distance, destination distance, ETA, the road name
     * and the live/idle status are recovered data; `turnId` comes from the recovered code table and
     * `drivingDirection` is the documented placeholder (see the class comment).
     */
    private fun guidanceFor(maneuver: AppleManeuver): EcarxDimApi.Guidance = EcarxDimApi.Guidance(
        turnId = EcarxArrowCode.arrowId(maneuver.type, maneuver.drivingSide),
        distanceToNextMeters = maneuver.distanceMeters.toLong(),
        distanceToDestinationMeters = maneuver.remainingMeters ?: -1L,
        etaEpochSeconds = maneuver.arrivalEpochSeconds ?: -1L,
        drivingDirection = DRIVING_DIRECTION_UNKNOWN,
        status = NAVI_STATUS_ACTIVE,
        muteState = 0,
        dayNightMode = dayNightMode(),
        nextGuidancePointName = maneuver.road,
    )

    /** `getDayNightMode()` is 0/1 in the tool's implementation; the head unit's own day/night split drives the choice. */
    private fun dayNightMode(): Int {
        val app = context ?: return 0
        val night = app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) 1 else 0
    }

    /**
     * The next-turn drawing as SVG markup, for the cluster's `updateTurnByTurnArrow(String)`
     * overload — the channel the head unit's own navigation app uses, and the one whose shape we
     * control without needing a code table.
     *
     * The document and both `<path>` layers are the stock templates verbatim (see [SVG_DOC]); the
     * geometry is ours, drawn the way the stock app draws it — see [ArrowDrawing]. Returns null for
     * a maneuver we have no drawing for.
     */
    private fun turnArrowSvg(type: Int, drivingSide: Int): String? {
        val drawing = arrowDrawing(type, drivingSide) ?: return null
        val data = svgContour(drawing)
        if (data.isEmpty()) return null
        val edge = SVG_PATH.format(data, SVG_EDGE_WIDTH, SVG_EDGE, SVG_EDGE)
        val core = SVG_PATH.format(data, SVG_CORE_WIDTH, SVG_CORE, SVG_CORE)
        return SVG_DOC.format(SVG_SIZE, SVG_SIZE, edge, core)
    }

    /**
     * An [ArrowDrawing]'s bands and head as SVG `d` data.
     *
     * The stock app appends one `M%s,%s L%s,%s` per segment and lets the document template's own
     * `Z` close whatever happens to be last, so nothing here adds a `Z` either — and that is not a
     * style choice. A band subpath is **two points**, and a two-point subpath closed has zero area,
     * so the template's `fill` draws nothing and the band shows only through its `stroke`. The head
     * is the one subpath with area, and it is meant to be filled. (Closing every subpath with `Z`,
     * as an earlier version did, is what turned the whole drawing into a filled silhouette.)
     *
     * Coordinates are integers all the way through, so nothing here can reach SVG as `1.0E-4`. That
     * was a live failure mode of the previous `StringBuilder.append(Float)` over `Path.approximate`.
     */
    private fun svgContour(drawing: ArrowDrawing): String {
        val out = StringBuilder()
        for (stroke in drawing.strokes) {
            var i = 0
            while (i + 3 < stroke.size) {
                out.append('M').append(px(stroke[i])).append(',').append(px(stroke[i + 1]))
                out.append('L').append(px(stroke[i + 2])).append(',').append(px(stroke[i + 3]))
                i += 2
            }
        }
        drawing.head?.let { out.append(headContour(it)) }
        return out.toString()
    }

    /**
     * The head as one closed triangle — tip, then the two barbs — in the template's own pixel space.
     *
     * Three points on purpose: it is the only subpath that has to fill, and two points could not.
     */
    private fun headContour(head: IntArray): String {
        val points = headVertices(head)
        val out = StringBuilder()
        var i = 0
        while (i + 1 < points.size) {
            out.append(if (i == 0) 'M' else 'L').append(px(points[i])).append(',').append(px(points[i + 1]))
            i += 2
        }
        return out.toString()
    }

    /**
     * [head] is `[tipX, tipY, dirX, dirY]` in the shapes' own `[-100, 100]` space, with the direction
     * given as any non-zero integer vector — `(±1, 0)`, `(±1, ±1)` is all the shapes ever need, and
     * the vector is normalised here so a 45-degree arm gets a head 45 degrees round.
     */
    private fun headVertices(head: IntArray): IntArray {
        val length = Math.hypot(head[2].toDouble(), head[3].toDouble()).coerceAtLeast(1.0)
        val ux = head[2] / length
        val uy = head[3] / length
        // Clockwise normal, so the two barbs land symmetrically either side of the axis.
        val nx = -uy
        val ny = ux
        val baseX = head[0] - ux * HEAD_LENGTH
        val baseY = head[1] - uy * HEAD_LENGTH
        return intArrayOf(
            head[0], head[1],
            Math.round(baseX + nx * HEAD_HALF).toInt(), Math.round(baseY + ny * HEAD_HALF).toInt(),
            Math.round(baseX - nx * HEAD_HALF).toInt(), Math.round(baseY - ny * HEAD_HALF).toInt(),
        )
    }

    /** A normalised `[-100, 100]` coordinate as a canvas pixel, rounded to the nearest. */
    private fun px(normalised: Int): Int = (SVG_HALF + normalised * SVG_SCALE + 0.5f).toInt()

    /**
     * One maneuver as the cluster draws it: stroked bands it can follow, plus one filled head.
     *
     * **Why bands and not a silhouette.** The stock app (`ecarx/naviservice/widget/f.smali`) builds
     * `d` out of `M%s,%s L%s,%s` segments — one per piece of the icon's road geometry, taken from
     * `IconRoadInfo.getVectorPoints()` — and hands the same string to both `<path>` layers: the
     * dark `rgb(88,90,102)` one stroked at 28.235294, and the `rgb(255,255,255)` core at 3. So the
     * stock arrow is a **thick dark band with a thin white line down the middle** following a path,
     * with a template polygon for the head. That is what makes it read as a turning arrow.
     *
     * This file used to fill one closed contour per maneuver instead, and the cluster duly drew a
     * flat arrow glyph pointing at the exit — a left turn came out as `←` rather than a left-turn
     * arrow, which is not what the driver reads off a cluster. The band model is the recovered one.
     *
     * All coordinates are in a normalised space whose centre is `(0, 0)`, `x` grows right, `y` grows
     * down, and 100 maps to [SVG_RADIUS].
     */
    private class ArrowDrawing(val strokes: List<IntArray>, val head: IntArray?)

    /**
     * A turn: a stem rising from the bottom edge of the canvas, a corner, and an arm leaving it at
     * [degrees] (negative turns left, and the angle is measured from straight up).
     *
     * The stem is offset to +[STEM_X] so a left turn has somewhere to bend to; a right turn is this
     * drawing reflected (see [mirror]).
     */
    private fun turn(degrees: Double, cornerY: Int, arm: Int = ARM_LENGTH): ArrowDrawing {
        val radians = Math.toRadians(degrees)
        val dx = Math.round(Math.sin(radians) * arm).toInt()
        val dy = -Math.round(Math.cos(radians) * arm).toInt()
        val endX = STEM_X + dx
        val endY = cornerY + dy
        // Direction as a sign vector: every shape here turns through 45, 90 or 135 degrees, so this
        // is exact, and [headVertices] normalises it for the head's own geometry.
        val dirX = dx.compareTo(0)
        val dirY = dy.compareTo(0)
        val unit = 1.0 / Math.hypot(dirX.toDouble(), dirY.toDouble())
        return ArrowDrawing(
            strokes = listOf(intArrayOf(STEM_X, STEM_BOTTOM, STEM_X, cornerY, endX, endY)),
            head = intArrayOf(
                endX + Math.round(dirX * unit * HEAD_LENGTH).toInt(),
                endY + Math.round(dirY * unit * HEAD_LENGTH).toInt(),
                dirX, dirY,
            ),
        )
    }

    /**
     * A U-turn: up one leg, a half-circle over the top, back down the other. The base drawing is the
     * left-hand one, so [left] only decides whether it is reflected.
     */
    private fun uTurn(left: Boolean): ArrowDrawing = mirror(
        ArrowDrawing(
            strokes = listOf(
                intArrayOf(UTURN_LEG, STEM_BOTTOM, UTURN_LEG, UTURN_CORNER_Y),
                arc(0, UTURN_CORNER_Y, UTURN_LEG, 0.0, -180.0),
                intArrayOf(-UTURN_LEG, UTURN_CORNER_Y, -UTURN_LEG, UTURN_ARM_Y),
            ),
            head = intArrayOf(-UTURN_LEG, UTURN_ARM_Y + HEAD_LENGTH, 0, 1),
        ),
        !left,
    )

    /** A roundabout: the ring, with the head seated on its top edge pointing out of it. */
    private fun roundabout(): ArrowDrawing = ArrowDrawing(
        strokes = listOf(ring(0, RING_CENTRE_Y, RING_RADIUS)),
        head = intArrayOf(0, RING_CENTRE_Y - RING_RADIUS - HEAD_LENGTH, 0, -1),
    )

    /** The destination marker: a pin — a ring with two strokes down to its point. */
    private fun destination(): ArrowDrawing = ArrowDrawing(
        strokes = listOf(
            ring(0, PIN_CENTRE_Y, PIN_RADIUS),
            intArrayOf(0, PIN_TIP_Y, -PIN_SHOULDER, PIN_CENTRE_Y + PIN_SHOULDER),
            intArrayOf(0, PIN_TIP_Y, PIN_SHOULDER, PIN_CENTRE_Y + PIN_SHOULDER),
        ),
        head = null,
    )

    /** Reflects a drawing across the vertical centre line when [apply]; a right turn is a left turn mirrored. */
    private fun mirror(drawing: ArrowDrawing, apply: Boolean = true): ArrowDrawing {
        if (!apply) return drawing
        return ArrowDrawing(
            strokes = drawing.strokes.map { mirrorX(it, -1) },
            head = drawing.head?.let { intArrayOf(-it[0], it[1], -it[2], it[3]) },
        )
    }

    /** Mirrors `x` about zero when [sign] is negative; returns [points] untouched otherwise. */
    private fun mirrorX(points: IntArray, sign: Int): IntArray {
        if (sign > 0) return points
        val out = IntArray(points.size)
        for (i in points.indices) out[i] = if (i % 2 == 0) -points[i] else points[i]
        return out
    }

    /** An arc as a polyline. Angles are degrees and `y` grows down, so 0 is to the right. */
    private fun arc(cx: Int, cy: Int, radius: Int, fromDeg: Double, toDeg: Double): IntArray {
        val points = IntArray((ARC_STEPS + 1) * 2)
        for (step in 0..ARC_STEPS) {
            val t = Math.toRadians(fromDeg + (toDeg - fromDeg) * step / ARC_STEPS)
            points[step * 2] = cx + Math.round(radius * Math.cos(t)).toInt()
            points[step * 2 + 1] = cy + Math.round(radius * Math.sin(t)).toInt()
        }
        return points
    }

    /** A full turn as a polyline; the last point repeats the first, which closes the ring. */
    private fun ring(cx: Int, cy: Int, radius: Int): IntArray = arc(cx, cy, radius, 0.0, 360.0)

    /**
     * Apple's `RouteGuidanceManeuverType`/`DrivingSide` to one of our arrow shapes, grouped the way
     * DiPlay's own dashboard widget groups them (see `NavigationWidget.arrow`). Null means Apple sent
     * a maneuver we have no drawing for, and the caller falls back.
     *
     * The corner heights are what keep the whole drawing inside the 255-unit canvas once the
     * 28.235294 stroke is accounted for: the shallower the turn, the higher the bend has to sit,
     * because a 45-degree arm needs the room a 135-degree one spends going back down.
     */
    private fun arrowDrawing(type: Int, drivingSide: Int): ArrowDrawing? = when (type) {
        1, 20 -> turn(-90.0, TURN_CORNER_Y)
        2, 21 -> mirror(turn(-90.0, TURN_CORNER_Y))
        47 -> turn(-135.0, SHARP_CORNER_Y)
        48 -> mirror(turn(-135.0, SHARP_CORNER_Y))
        13, 22, 49, 52 -> turn(-45.0, SLIGHT_CORNER_Y)
        14, 23, 50, 53 -> mirror(turn(-45.0, SLIGHT_CORNER_Y))
        4, 18, 19, 26 -> uTurn(left = drivingSide != 1)
        6, 7, in 28..46 -> roundabout()
        10, 12, 24, 25, 27 -> destination()
        else -> null
    }

    /**
     * Draws the next-turn arrow locally and returns it as a bitmap — the fallback for a head unit
     * that has no `updateTurnByTurnArrow(String)` overload. Returns null for an unrecognised
     * maneuver so the caller falls back to the recovered code.
     *
     * It strokes the same bands the SVG push strokes and outlines the same head, so the two channels
     * cannot show different arrows.
     */
    private fun turnArrowBitmap(type: Int, drivingSide: Int): android.graphics.Bitmap? {
        val drawing = arrowDrawing(type, drivingSide) ?: return null
        val size = 128
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 12f
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        // Normalised 130 rather than 100, so a 12 px stroke cannot touch the edge of the bitmap.
        val scale = size / 2f / 130f
        val path = android.graphics.Path()
        for (stroke in drawing.strokes) {
            var i = 0
            while (i + 1 < stroke.size) {
                val x = size / 2f + stroke[i] * scale
                val y = size / 2f + stroke[i + 1] * scale
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                i += 2
            }
        }
        drawing.head?.let { head ->
            val points = headVertices(head)
            var i = 0
            while (i + 1 < points.size) {
                val x = size / 2f + points[i] * scale
                val y = size / 2f + points[i + 1] * scale
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                i += 2
            }
            path.close()
        }
        canvas.drawPath(path, paint)
        return bitmap
    }

    /** One-line state for the settings diagnostics block. */
    fun diagnostics(context: Context): String = buildString {
        append("ecarx ").append(EcarxCapabilities.describe(context))
        append(" enabled=").append(EcarxOutputSettings.enabled(context))
        append(" presentation=").append(EcarxOutputSettings.presentationSwitch(context))
        append(" arrowExperimental=").append(EcarxOutputSettings.turnArrowExperimental(context))
        append(" media=").append(EcarxOutputSettings.mediaEnabled(context))
        append(" factoryNavigation=[").append(EcarxFactoryNavigationControl.status(context)).append(']')
        synchronized(lock) {
            append(" wired=").append(ecarxDetected)
            append(" dim=").append(dim != null)
            append(" navi=").append(navi != null)
            append(" protocol=").append(protocol != null)
            append(" dimCb=").append(dimCallback != null)
            append(" naviCb=").append(naviCallback != null)
            // Which updateTurnByTurnArrow overloads this head unit declares, so one report line says
            // whether the cluster is being fed the stock app's SVG channel or a fallback.
            append(" arrow=").append(EcarxDimApi.arrowOverloads(navi))
            append(" projectionWanted=").append(presentationWanted)
            append(" projectionAttempts=").append(presentationAttempts)
            // The package the running mirror was opened with; empty means "the whole centre screen",
            // which is the picture the driver rejected as not fitting the cluster.
            append(" projectionPackage=").append(presentedPackage ?: "")
            append(" arrowId=").append(lastPushed?.turnId ?: -1)
            append(" active=").append(lastPushed != null)
            append(" route=[").append(route.diagnosticState()).append("] lastEnd=[").append(lastGuidanceEnd).append(']')
            append(" status=").append(if (lastPushed != null) NAVI_STATUS_ACTIVE else NAVI_STATUS_IDLE)
            // The only read-back the API offers. Uninterpreted on purpose — it stays -1 while the
            // cluster mirrors (probe v3/v4), so it is printed for correlation, never acted on.
            dim?.let { append(" showPresentationOption=").append(EcarxDimApi.showPresentationOption(it)) }
        }
        append(EcarxProjectionChannel.state())
    }
}
