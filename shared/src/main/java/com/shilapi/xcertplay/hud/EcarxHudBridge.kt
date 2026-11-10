package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.shilapi.xcertplay.shared.R
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
 *    drawings are independent of the aggregate snapshot. Numeric ids ([EcarxArrowCode]) go first
 *    here; [turnArrowSvg] and locally drawn bitmaps are fallbacks when that overload is missing.
 *  * **Needs calibration.** `getDrivingDirection()` is a bare `int` whose scale was never
 *    recovered, so it carries [DRIVING_DIRECTION_UNKNOWN] instead of Apple's flag dressed up as
 *    data. The arrow is still gated behind [EcarxOutputSettings.turnArrowExperimental] until the
 *    car confirms what the cluster actually draws.
 *
 * [EcarxDimApi] uses the compile-only SDK behind app-owned handles. Runtime detection and
 * guarded dispatch keep missing vendor classes from breaking non-ECARX head units.
 */
internal object EcarxHudBridge {
    private val dimHandler by lazy {
        Handler(HandlerThread("diplay-ecarx-dim").apply { start() }.looper)
    }
    private const val TAG = "DiPlay-ECARX-HUD"

    /** Recovery must run even when ordinary distance/ETA updates keep arriving. */
    private const val KEEPALIVE_TICKS = 5

    /** How many lines are held for a report that has not attached yet; see [report]. */
    private const val REPORT_BACKLOG = 24

    /**
     * `getNavigationStatus()` while guidance is live. The toolkit copies its own value through
     * unchanged, so the two states are the only ones recovered; 0 is used for idle on teardown.
     */
    private const val NAVI_STATUS_ACTIVE = EcarxDimApi.NAVIGATION_SNAPSHOT_ACTIVE
    private const val NAVI_STATUS_IDLE = EcarxDimApi.NAVIGATION_SNAPSHOT_IDLE

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
    private var epochSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
    private var currentSession: (() -> Boolean)? = null
    private val route = RouteGuidanceState(liveSession = { currentSession?.invoke() == true })
    private var context: Context? = null
    private var ecarxDetected = false
    private var dim: EcarxDimApi.DimHandle? = null
    private var navi: EcarxDimApi.NaviHandle? = null
    private var protocol: Any? = null
    /** Non-null once the DIM can reach us; callbacks are held for the lifetime of the registration. */
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
    private var releaseStalePresentation = false
    private var startupReleaseOrigin = "none"
    private var startupReleaseAttempts = 0
    private var startupReleaseCountdown = 0
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
    private val lanesByIndex = mutableMapOf<Int, List<EcarxDimApi.Lane>?>()
    private var lastLanePushed: List<EcarxDimApi.Lane>? = null
    private var fallbackLaneTurn: Int? = null
    private var lastDestination: String? = ""
    private var routeNumber = 0L
    private enum class Phase { Idle, Waiting, Guiding, Gap }
    private var phase = Phase.Idle
    private var routeSinceMs = 0L
    private var lastInputShape = ""
    private var lastLaneInput = ""
    private var reassertCount = 0L
    private var visibilityCount = 0L
    private var lastReassertOutcome = ""
    private var lastProjectionOutcome = ""
    private var lastLaneOutcome = ""
    private var lastWithdrawalOutcome = ""
    private var lastDestinationOutcome = ""
    private var lastRawOption: Int? = null
    /** Last arrow channel logged; the SDK call is still made on a forced refresh. */
    private var lastArrowChannel: String? = null
    private var lastNavigationRefreshOutcome: String? = null
    private var ticksSinceRefresh = 0
    private var presentationOption = 0 // Unknown until DIM sends its first callback.
    private var clusterVisibleWanted = true
    private var routeAutoShowMap = true
    private var guidanceActive = false
    private var hudActive = false
    // An aggregate may fail after partial writes, so cleanup tracks attempts as well as starts.
    private var hudOutputTouched = false
    private var lastGuidanceEnd = "none"

    /**
     * Distinguishes a hidden map from an ended route for the owner that draws the
     * cluster itself — the CarPlay instrument-cluster stream (display 1, `EcarxClusterPresentation`).
     *
     * One retained session owns this slot. Registration replays the route intent under the same
     * lock as route transitions, and identity-checked removal cannot unregister a newer session.
     */
    private var clusterPresentationRequested: ((ClusterMapIntent) -> Unit)? = null

    private val mapIntent: ClusterMapIntent get() = when {
        !guidanceActive -> ClusterMapIntent.NO_ROUTE
        clusterVisibleWanted -> ClusterMapIntent.SHOWN
        else -> ClusterMapIntent.HIDDEN
    }

    fun setClusterPanelOwner(owner: ((ClusterMapIntent) -> Unit)?) = synchronized(lock) {
        clusterPresentationRequested = owner
        owner?.invoke(mapIntent)
    }

    fun clearClusterPanelOwner(expected: (ClusterMapIntent) -> Unit) = synchronized(lock) {
        if (clusterPresentationRequested === expected) clusterPresentationRequested = null
    }

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

    /** [onSettled] fires once when the restore chain ends or when there was nothing to restore. */
    fun recoverFactoryNavigationIfIdle(app: Context, onSettled: (() -> Unit)? = null) = synchronized(lock) {
        if (guidanceActive || hudActive) {
            onSettled?.invoke()
            return@synchronized
        }
        report(EcarxFactoryNavigationRestore.restoreIfPausedByUs(app, onSettled))
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
        EcarxOutputSettings.retirePresentationSwitch(app)
        clusterVisibleWanted = EcarxOutputSettings.autoShowMap(app)
        presentationOption = 0
        val pending = EcarxOutputSettings.presentationReleasePending(app)
        releaseStalePresentation = pending
        startupReleaseOrigin = if (pending) "pending" else "none"
        startupReleaseAttempts = 0
        startupReleaseCountdown = 0
        // Preserve legacy residue before clearing the old request, including across another restart.
        if (releaseStalePresentation) EcarxOutputSettings.setPresentationReleasePending(app, true)
        reclaimLocked()
        watchDisplaysLocked(app)
        report(
            "DIM ready navi=${navi != null} protocol=${protocol != null} " +
                "dimCb=${dimCallback != null} naviCb=${naviCallback != null} " +
                "projection=$presentationWanted ${EcarxCapabilities.describe(app)}",
        )
        // The window owner waits for independent video before switching the cluster to that map.
        report("cluster visibility default wanted=$clusterVisibleWanted platformOption=unknown")
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
        if (dim == null && Looper.myLooper() == null) {
            // DIM constructs a Handler; give it a worker Looper without blocking the UI.
            dimHandler.post { synchronized(lock) { reclaimLocked() } }
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
        // lines 146 and 149).
        if (dimCallback == null) {
            val main = Handler(Looper.getMainLooper())
            dimCallback = EcarxDimApi.registerInteractionCallback(interaction, "diplay") { option ->
                // Return to DIM before touching the bridge or its window owner.
                main.post {
                    synchronized(lock) {
                        if (lastRawOption != option) {
                            NavigationOutputs.diagnostic("map option=$option firstSinceBoundary=${lastRawOption == null} source=platform driverInput=unknown")
                            lastRawOption = option
                        }
                        if (option !in 1..3) return@synchronized
                        presentationOption = option
                        val show = when (option) {
                            1 -> if (guidanceActive) routeAutoShowMap else EcarxOutputSettings.autoShowMap(app)
                            2 -> true
                            else -> false
                        }
                        setVisibilityIntent(show, "platform option=$option")
                    }
                }
            }
        }
        val naviRef = navi
        if (naviRef != null && naviCallback == null) {
            naviCallback = EcarxDimApi.registerNavigationInteractionCallback(naviRef, "diplay") { action ->
                if (action == 2) Handler(Looper.getMainLooper()).post {
                    val id = NavigationOutputs.navigationExitAction()
                    val queued = NavigationOutputs.requestNavigationExit?.invoke(id) == true
                    if (!queued) NavigationOutputs.diagnostic("exit queue failed", id)
                    report("navigation exit requested action=2 queued=$queued awaitingPhoneRouteEnd=true")
                    if (!queued) Toast.makeText(app, R.string.carapp_navigation_exit_unavailable, Toast.LENGTH_LONG).show()
                }
            }
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
            val update = Iap2LaneGuidance.parseUpdate(frame.payload) ?: run {
                if (lastLaneInput != "invalid") NavigationOutputs.diagnostic("route=$routeNumber lane input=invalid")
                lastLaneInput = "invalid"
                return@synchronized
            }
            val shape = "index=${update.index} count=${update.lanes.size}"
            if (lastLaneInput != shape) NavigationOutputs.diagnostic("route=$routeNumber lane input $shape")
            lastLaneInput = shape
            val app = context
            if (update.lanes.isEmpty() || app == null) {
                lanesByIndex.clear()
                lanes = null
            } else {
                val mapped = update.lanes.map { lane ->
                    EcarxLaneIcon.forLane(lane)?.let { EcarxDimApi.Lane(lane.index + 1, it) }
                }
                // Without a phone lane cursor, a repeated old group cannot identify a new junction.
                val index = requireNotNull(update.index)
                val group = if (mapped.all { it != null }) mapped.filterNotNull() else null
                if (index !in lanesByIndex || group != lanesByIndex[index]) {
                    fallbackLaneTurn = route.activeIndex.takeIf { it >= 0 }
                }
                lanesByIndex[index] = group
                if (NavigationOutputs.debugLogsEnabled()) report("DIM lane cached index=${update.index} count=${update.lanes.size} " +
                    "current=${route.laneGuidanceIndex ?: "none"} guidance=${route.currentForDim() != null}")
            }
            pushLaneLocked(false)
            return@synchronized
        }
        val hadAnnouncement = route.announced
        val change = route.accept(frame.messageId, frame.payload)
        if (NavigationOutputs.debugLogsEnabled()) report("DIM input source=$source id=0x${frame.messageId.toString(16)} ${route.diagnosticInput} " +
            "change=$change currentNext=${route.currentForDim()?.distanceMeters ?: "none"} " +
            "elapsedMs=${SystemClock.elapsedRealtime()} ${route.diagnosticState()}")
        if (phase == Phase.Idle && !guidanceActive && !route.expired && (route.announced || route.currentForDim() != null)) {
            NavigationOutputs.navigationExitFinished("new-route")
            routeNumber++
            routeSinceMs = SystemClock.elapsedRealtime()
            phase = Phase.Waiting
            lastRawOption = null
        }
        val terminal = route.inputState
        if (terminal == 0 || terminal == 2) {
            NavigationOutputs.navigationExitTerminal(terminal)
            if (guidanceActive) endLocked(route.diagnosticState()) else {
                // Some phones prefetch lanes, then send an initial NoRouteSet before starting.
                if (hadAnnouncement || change == RouteGuidanceChange.CLEAR || terminal == 2) {
                    clearLanesLocked()
                }
                NavigationOutputs.navigationExitFinished("no-owned-output")
            }
        } else if (change == RouteGuidanceChange.GUIDANCE || guidanceActive) {
            pushLocked(force = false)
            pushLaneLocked(false)
        }
        reportRouteChange(source)
    }

    private fun reportRouteChange(source: String) {
        val nextPhase = when {
            guidanceActive -> if (route.currentForDim() == null) Phase.Gap else Phase.Guiding
            route.announced && !route.expired -> Phase.Waiting
            else -> Phase.Idle
        }
        val now = SystemClock.elapsedRealtime()
        val firstTurn = if (phase != Phase.Guiding && phase != Phase.Gap && nextPhase == Phase.Guiding) " firstTurnMs=${now - routeSinceMs}" else ""
        phase = nextPhase
        val shape = "phase=${phase.name.lowercase()} phoneState=${route.lastPhoneState} turn=${route.activeIndex} matched=${route.current() != null} " +
            "laneIndex=${route.laneGuidanceIndex} laneShowing=${route.laneGuidanceShowing} destinationPresent=${route.destinationName.isNotEmpty()}"
        if (shape != lastInputShape) {
            lastInputShape = shape
            NavigationOutputs.diagnostic("route=$routeNumber source=$source $shape$firstTurn")
        }
    }

    fun setCurrentSession(isCurrent: (() -> Boolean)?) = synchronized(lock) {
        lastRawOption = null
        currentSession = isCurrent
    }

    fun clear() = synchronized(lock) {
        currentSession = null
        route.clear() // Stop the keepalive from restoring guidance after cleanup.
        if (guidanceActive || hudActive) endLocked("reason=clear-or-session-stop") else {
            clearLanesLocked()
            context?.let { report(EcarxFactoryNavigationRestore.restoreIfPausedByUs(it)) }
            NavigationOutputs.navigationExitFinished("session-closed")
        }
        reportRouteChange("session-close")
    }

    /** Whether guidance is currently on the cluster. */
    fun isActive(): Boolean = synchronized(lock) { guidanceActive }

    fun settingsChanged() = synchronized(lock) {
        pushLocked(force = true)
        pushLaneLocked(force = true)
    }

    // ---------------------------------------------------------------- OEM switches

    /** OEM controls change this process's intent, independently of route liveness. */
    private fun setVisibilityIntent(show: Boolean, origin: String) = synchronized(lock) {
        visibilityCount++
        clusterVisibleWanted = show
        if (!show) presentationWanted = false
        report("cluster visibility intent show=$show origin=$origin routeActive=$guidanceActive panel=$mapIntent")
        // Repeating an explicit event must also repair a missing or stale window.
        clusterPresentationRequested?.invoke(mapIntent)
    }

    /** Runtime delivery belongs only to the independent window, never to a settings toggle. */
    fun applyPresentation(show: Boolean, origin: String): EcarxDimApi.EcarxResult = synchronized(lock) {
        // Who asked. The 18:24 report shows the mirror being turned *off* half a second after the
        // process started, while the settings screen held "on" — and nothing in this file can tell
        // the difference between the driver, a dialog that cancelled itself, and a switch the
        // settings screen rebuilt. This line is what separates them next time.
        report("presentation request show=$show origin=$origin")
        if (context == null) return EcarxDimApi.EcarxResult.failed("DIM not initialized")
        if (show && (!clusterVisibleWanted || !guidanceActive)) {
            return EcarxDimApi.EcarxResult.failed("cluster display not requested")
        }
        // Only an accepted window request supersedes startup recovery.
        releaseStalePresentation = false
        presentationWanted = show
        if (!show) {
            presentationAttempts = 0
            presentationCountdown = 0
            // A missing map Surface is not navigation-app termination.
            if (guidanceActive) return EcarxDimApi.EcarxResult.ok("map source detached; navigation retained")
            return applyPresentationLocked(false)
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
        // Persist before opening: process death must not lose responsibility for the platform call.
        if (show) context?.let { EcarxOutputSettings.setPresentationReleasePending(it, true) }
        val result = EcarxDimApi.setPresentationToDimSwitch(interaction, show, packageName = packageName)
        val option = presentationOption.takeIf { it != 0 }?.toString() ?: "unknown"
        val state = "route=$routeNumber map sdkSwitch=$show platformOption=$option result=${outcome(result)}"
        if (state != lastProjectionOutcome) {
            lastProjectionOutcome = state
            NavigationOutputs.diagnostic(state)
        }
        if (!result.ok) {
            report("presentation switch failed: ${result.reason}")
            return result
        }
        presentedPackage = if (show) packageName else null
        if (!show) {
            context?.let { EcarxOutputSettings.setPresentationReleasePending(it, false) }
        }
        resendLocked()
        if (NavigationOutputs.debugLogsEnabled()) report("presentation switch -> $show package=\"$packageName\"")
        return result
    }

    /** Reassert current HUD data after a ready source or an idle-time recovery call. */
    private fun resendLocked() {
        if (!guidanceActive || !hudActive || context?.let(EcarxOutputSettings::enabled) != true) return
        ticksSinceRefresh = 0
        pushLocked(force = true)
        if (!guidanceActive) return
        val naviRef = navi ?: return
        // A repeated aggregate can be deduplicated after naviAppOff; this public call always sends.
        val active = EcarxDimApi.updateMuteState(naviRef, lastPushed?.muteState ?: 0)
        pushLaneLocked(force = true)
        reassertCount++
        val state = "route=$routeNumber map=$presentationWanted phase=${phase.name.lowercase()} ongoing=${outcome(active)} lanes=${lastLanePushed?.size ?: 0}"
        val debug = NavigationOutputs.debugLogsEnabled()
        if (state != lastReassertOutcome || debug) {
            lastReassertOutcome = state
            val pushed = lastPushed
            val detail = if (debug) " arrowId=${pushed?.turnId} nextMeters=${pushed?.distanceToNextMeters} " +
                "roadPresent=${!pushed?.nextGuidancePointName.isNullOrEmpty()}" else ""
            NavigationOutputs.diagnostic("reassert $state$detail")
        }
    }

    // ---------------------------------------------------------------- push path

    private fun tick() = synchronized(lock) {
        if (!ecarxDetected) return@synchronized
        runCatching {
            // The cluster service is not up when the head unit is only on accessory power, so keep
            // asking for the handles as well as for the projection itself.
            if (dim == null || navi == null) reclaimLocked()
            if (releaseStalePresentation && dim != null && !guidanceActive) {
                if (startupReleaseCountdown > 0) startupReleaseCountdown -= 1
                else {
                    val fast = startupReleaseAttempts < PRESENTATION_FAST_ATTEMPTS
                    val interval = if (fast) PRESENTATION_FAST_SECONDS else PRESENTATION_SLOW_SECONDS
                    startupReleaseCountdown = interval - 1
                    val result = applyPresentationLocked(false)
                    startupReleaseAttempts += 1
                    if (result.ok) releaseStalePresentation = false
                    if (result.ok || startupReleaseAttempts == 1 || startupReleaseAttempts == PRESENTATION_FAST_ATTEMPTS + 1) {
                        report("startup projection release call origin=$startupReleaseOrigin " +
                            "attempt=$startupReleaseAttempts every=${interval}s: ${result.reason}")
                    }
                }
            }
            watchProjectionLocked()
            if (++ticksSinceRefresh >= KEEPALIVE_TICKS || guidanceActive && route.currentForDim() == null) {
                val force = ticksSinceRefresh >= KEEPALIVE_TICKS
                if (force) ticksSinceRefresh = 0
                pushLocked(force)
                pushLaneLocked(force)
            }
            reportRouteChange("tick")
        }.onFailure { runCatching { clear() } }
    }

    /**
     * Re-asserts the projection switch until the cluster is up, then keeps it alive.
     *
     * Nothing on this platform reports that the projection took: `getShowPresentationOption()` is
     * the only read-back and it is `-1` on this head unit even while the cluster is mirroring
     * (probe v3/v4, nine reads), so a read-back loop would immediately give up. Re-sending the
     * switch keeps projection alive while the latest platform option allows it.
     */
    private fun watchProjectionLocked() {
        if (!presentationWanted || !clusterVisibleWanted || !guidanceActive) return
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
        if (NavigationOutputs.debugLogsEnabled() && (presentationAttempts == 1 || !fast && presentationAttempts % 6 == 0)) {
            report("projection re-asserted #$presentationAttempts every ${interval}s (${result.reason})")
        }
    }

    private fun pushLocked(force: Boolean) {
        val app = context ?: return
        val naviRef = navi ?: return
        val hudEnabled = EcarxOutputSettings.enabled(app)
        if (!hudEnabled && hudActive) stopGuidanceOutputLocked()
        val etaSeconds = route.arrivalEpochSeconds?.let {
            (it - epochSeconds()).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        } ?: -1
        val maneuver = route.currentForDim()
        if (maneuver == null) {
            if (!guidanceActive) return
            if (route.expired) {
                endLocked(route.diagnosticState())
                return
            }
            if (hudEnabled && lastPushed != null) {
                // No unknown-distance representation in this SDK. Withdraw the obsolete instruction
                // without publishing a made-up distance or idling the route that owns the lanes.
                val arrow = EcarxDimApi.updateTurnByTurnArrow(naviRef, EcarxArrowCode.NO_ARROW)
                val svg = EcarxDimApi.updateTurnByTurnArrow(naviRef, "")
                val road = EcarxDimApi.updateNextGuidancePointName(naviRef, "")
                val result = "arrow=${outcome(arrow)} svg=${outcome(svg)} road=${outcome(road)}"
                if (result != lastWithdrawalOutcome) NavigationOutputs.diagnostic("turn withdrawn $result")
                lastWithdrawalOutcome = result
                if ((arrow.ok || svg.ok) && road.ok) lastPushed = null
                lastArrowChannel = null
            }
            if (hudEnabled && hudActive) {
                publishRouteMetadata(naviRef, etaSeconds)
                if (force) refreshNavigationLocked(EcarxDimApi.Guidance(
                    status = NAVI_STATUS_ACTIVE,
                    distanceToDestinationMeters = route.remainingMeters ?: -1L,
                    etaRemainingSeconds = etaSeconds,
                ))
            }
            return
        }
        lastWithdrawalOutcome = ""
        val guidance = guidanceFor(maneuver, etaSeconds)
        if (!hudEnabled) {
            if (!guidanceActive) {
                report(EcarxFactoryNavigationRestore.pauseForGuidance(app))
                startGuidanceLocked(maneuver, guidance)
            }
            return
        }
        if (!force && guidance == lastPushed) {
            publishRouteMetadata(naviRef, etaSeconds)
            return
        }
        val firstFrame = !hudActive

        if (firstFrame) report(EcarxFactoryNavigationRestore.pauseForGuidance(app))

        // Order matches the toolkit: the aggregate payload first, then the discrete fields.
        hudOutputTouched = true
        val result = EcarxDimApi.updateNavigationInfo(naviRef, guidance)
        if (!result.ok) {
            report("updateNavigationInfo failed next=${maneuver.distanceMeters}m: ${result.reason}")
            if (firstFrame && !guidanceActive) {
                stopGuidanceOutputLocked()
                hudOutputTouched = false
                report(EcarxFactoryNavigationRestore.restoreIfPausedByUs(app))
            } else endLocked("reason=aggregate-failed")
            return
        }
        if (firstFrame) {
            val status = EcarxDimApi.notifyNavigationStarted(naviRef)
            val started = EcarxDimApi.notifyTurnByTurnStarted(naviRef)
            report("DIM lifecycle start status=$NAVI_STATUS_ACTIVE aggregate=${outcome(result)} " +
                "notify=${outcome(started)} detail=${started.reason} " +
                "navigationStatus=${outcome(status)} statusDetail=${status.reason}")
        }
        hudActive = true
        // The `void` notification above has never been confirmed to reach the service. Publish the
        // event the service itself would have published, so the cluster is told a navigation exists
        // even if that call went nowhere; see EcarxProjectionChannel.setNavigationOngoing.
        if (firstFrame && EcarxOutputSettings.naviCardEvent(app)) {
            EcarxProjectionChannel.setNavigationOngoing(true, maneuver.road)
        }
        val nextPoint = EcarxDimApi.updateDistanceToNextGuidancePoint(naviRef, maneuver.distanceMeters.toLong())
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

        val (remaining, eta) = publishRouteMetadata(naviRef, etaSeconds)
        if (force || firstFrame) refreshNavigationLocked(guidance)
        lastPushed = guidance
        if (NavigationOutputs.debugLogsEnabled()) report("DIM publish next=${maneuver.distanceMeters}m arrowId=${guidance.turnId} " +
            "status=${guidance.status} force=$force first=$firstFrame aggregate=${outcome(result)} " +
            "next=${outcome(nextPoint)} destination=${outcome(remaining)} eta=${outcome(eta)} " +
            "elapsedMs=${SystemClock.elapsedRealtime()}")
        if (!guidanceActive) {
            // Every one of these calls has always been fired and its answer thrown away, so a
            // field the cluster never received looked exactly like one the cluster ignored. The
            // 16:10 comparison showed the head unit's own app publishing `distance` while this
            // project never did — one line per guidance start now says whether that field was
            // absent from the phone, refused by the platform, or accepted and then dropped.
            report(
                "DIM fields next=${outcome(nextPoint)} " +
                    "distance=${outcome(remaining)}:${maneuver.remainingMeters ?: -1}m " +
                    "eta=${outcome(eta)}:remainingSeconds=${guidance.etaRemainingSeconds} " +
                    "raw=${maneuver.etaRawSeconds ?: -1}",
            )
            startGuidanceLocked(maneuver, guidance)
        }
    }

    private fun startGuidanceLocked(maneuver: AppleManeuver, guidance: EcarxDimApi.Guidance) {
        val app = context ?: return
        guidanceActive = true
        routeAutoShowMap = EcarxOutputSettings.autoShowMap(app)
        clusterVisibleWanted = when (presentationOption) {
            2 -> true
            3 -> false
            else -> routeAutoShowMap
        }
        report("DIM guidance started arrowId=${guidance.turnId} next=${maneuver.distanceMeters}m " +
            "roadPresent=${maneuver.road.isNotEmpty()} hud=${EcarxOutputSettings.enabled(app)} " +
            "elapsedMs=${SystemClock.elapsedRealtime()} ${route.diagnosticState()}")
        // The window owner waits for the source's first frame, independently of HUD output.
        clusterPresentationRequested?.invoke(mapIntent)
    }

    private fun publishRouteMetadata(navi: EcarxDimApi.NaviHandle, etaSeconds: Int): Pair<EcarxDimApi.EcarxResult?, EcarxDimApi.EcarxResult?> {
        val remaining = route.remainingMeters?.let { EcarxDimApi.updateDistanceToDestination(navi, it) }
        val eta = etaSeconds.takeIf { it >= 0 }?.let { EcarxDimApi.updateEta(navi, it) }
        if (remaining?.ok == false || eta?.ok == false) {
            NavigationOutputs.diagnostic("route=$routeNumber metadata distance=${outcome(remaining)} eta=${outcome(eta)}")
        }
        val wanted = route.destinationName
        if (wanted != lastDestination) {
            val result = EcarxDimApi.updateDestinationName(navi, wanted)
            val shape = "present=${wanted.isNotEmpty()} accepted=${result.ok}"
            if (result.ok || lastDestinationOutcome != shape) NavigationOutputs.diagnostic("route=$routeNumber destination $shape")
            lastDestinationOutcome = shape
            if (result.ok) lastDestination = wanted
        }
        return remaining to eta
    }

    private fun refreshNavigationLocked(guidance: EcarxDimApi.Guidance) {
        ticksSinceRefresh = 0
        val result = EcarxDimApi.refreshNavigation(guidance, route.destinationName)
        val state = "accepted=${result.ok} ${result.reason}"
        if (state != lastNavigationRefreshOutcome || NavigationOutputs.debugLogsEnabled()) {
            NavigationOutputs.diagnostic("route=$routeNumber manager refresh $state")
        }
        lastNavigationRefreshOutcome = state
    }

    private fun pushLaneLocked(force: Boolean) {
        val naviRef = navi ?: return
        // 0x5204 is prefetched for several junctions before the first live 0x5201 cursor.
        if (!guidanceActive) return
        val cursor = route.laneGuidanceIndex
        lanes = when {
            route.laneGuidanceShowing == false -> null
            cursor != null -> lanesByIndex[cursor]
            lanesByIndex.size == 1 && route.currentForDim() != null &&
                (fallbackLaneTurn == null || fallbackLaneTurn == route.activeIndex) -> {
                fallbackLaneTurn = route.activeIndex
                lanesByIndex.values.single()
            }
            else -> null
        }
        val wanted = lanes.takeIf { EcarxOutputSettings.enabled(context ?: return) && hudActive }
        if (wanted == null) {
            if (lastLanePushed != null) {
                val result = EcarxDimApi.clearLaneInfo(naviRef)
                if (NavigationOutputs.debugLogsEnabled()) report("DIM lane clear ${result.reason} showing=${route.laneGuidanceShowing}")
                val shape = "clear accepted=${result.ok}"
                if (shape != lastLaneOutcome) NavigationOutputs.diagnostic("route=$routeNumber lane $shape")
                lastLaneOutcome = shape
                if (result.ok) lastLanePushed = null
            }
            return
        }
        if (!force && wanted == lastLanePushed) return
        val result = EcarxDimApi.updateLaneInfo(naviRef, wanted)
        if (NavigationOutputs.debugLogsEnabled() || !result.ok && lastLaneOutcome != "count=${wanted.size} accepted=false") report("DIM lane ${result.reason} index=${route.laneGuidanceIndex ?: "unspecified"} " +
            "showing=${route.laneGuidanceShowing ?: "unspecified"}")
        val shape = "count=${wanted.size} accepted=${result.ok}"
        if (shape != lastLaneOutcome || wanted != lastLanePushed && result.ok) {
            lastLaneOutcome = shape
            NavigationOutputs.diagnostic("route=$routeNumber lane $shape index=$cursor")
        }
        if (result.ok) lastLanePushed = wanted
    }

    private fun clearLanesLocked(force: Boolean = false) {
        fallbackLaneTurn = null
        lanesByIndex.clear()
        if (!force && lanes == null && lastLanePushed == null) return
        lanes = null
        val naviRef = navi ?: return
        val result = EcarxDimApi.clearLaneInfo(naviRef)
        NavigationOutputs.diagnostic("route=$routeNumber cleanup laneAccepted=${result.ok}")
        if (!result.ok || lastLanePushed != null && NavigationOutputs.debugLogsEnabled()) report("DIM lane clear ${result.reason}")
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
     * The numeric turn id goes first. Every SDK overload returns void and can deduplicate or swallow
     * a Binder error, so `ok` confirms method return only. With the drawing channels ordered first, a call that
     * was accepted and silently ignored would end the cascade — which is exactly what this project
     * spent weeks chasing. The id channel is the one the car has been observed to render (ids 101–143,
     * see [EcarxArrowCode]), so it is asked first and the drawings are kept as the answer for a head
     * unit that lacks `updateTurnByTurnArrow(int)`.
     */
    private fun pushArrowLocked(
        navi: EcarxDimApi.NaviHandle,
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

    /** Withdraw HUD output while retaining the phone route, map intent and prefetched lanes. */
    private fun stopGuidanceOutputLocked() {
        if (!hudOutputTouched) return
        val naviRef = navi
        if (naviRef != null) {
            // The recovered SDK's Stop only clears lanes/exits; idle resets its cached active state for the next start.
            val idle = EcarxDimApi.updateNavigationInfo(naviRef, EcarxDimApi.Guidance(
                status = NAVI_STATUS_IDLE,
                distanceToNextMeters = 0,
                distanceToDestinationMeters = 0,
                etaRemainingSeconds = 0,
            ))
            val status = EcarxDimApi.notifyNavigationEnded(naviRef)
            val stopped = EcarxDimApi.notifyTurnByTurnStopped(naviRef)
            report("DIM lifecycle stop status=$NAVI_STATUS_IDLE aggregate=${outcome(idle)} " +
                "notify=${outcome(stopped)} aggregateDetail=${idle.reason} notifyDetail=${stopped.reason} " +
                "navigationStatus=${outcome(status)} statusDetail=${status.reason}")
            if (context?.let(EcarxOutputSettings::naviCardEvent) == true) {
                EcarxProjectionChannel.setNavigationOngoing(false, "")
            }
            if (context?.let(EcarxOutputSettings::turnArrowExperimental) == true) {
                // Clear through the same family that drew it: the id the stock app reserves for
                // "nothing to show" first, then an empty drawing for a head unit whose only
                // overload is the string one. Whichever overload this head unit lacks just refuses.
                val arrow = EcarxDimApi.updateTurnByTurnArrow(naviRef, EcarxArrowCode.NO_ARROW)
                val svg = EcarxDimApi.updateTurnByTurnArrow(naviRef, "")
                NavigationOutputs.diagnostic("cleanup arrow=${outcome(arrow)} svg=${outcome(svg)}")
            }
            val distance = EcarxDimApi.updateDistanceToNextGuidancePoint(naviRef, 0)
            val road = EcarxDimApi.updateNextGuidancePointName(naviRef, "")
            // Type 3 changes re-emit the SDK's cached active state. Write only after idle.
            val address = if (idle.ok) EcarxDimApi.updateDestinationName(naviRef, "") else null
            lastDestination = if (address?.ok == true) "" else null
            NavigationOutputs.diagnostic("cleanup idle=${outcome(idle)} end=${outcome(status)} stop=${outcome(stopped)} " +
                "distance=${outcome(distance)} road=${outcome(road)} destination=${outcome(address)}")
            val lane = EcarxDimApi.clearLaneInfo(naviRef)
            NavigationOutputs.diagnostic("route=$routeNumber cleanup laneAccepted=${lane.ok}")
            if (!lane.ok || lastLanePushed != null && NavigationOutputs.debugLogsEnabled()) {
                report("DIM lane clear ${lane.reason}")
            }
            if (lane.ok) lastLanePushed = null
        }
        lastPushed = null
        lastArrowChannel = null
        lastNavigationRefreshOutcome = null
        ticksSinceRefresh = 0
        hudActive = false
    }

    private fun endLocked(reason: String) {
        try {
            lastGuidanceEnd = "$reason elapsedMs=${SystemClock.elapsedRealtime()}"
            lanes = null
            lanesByIndex.clear()
            fallbackLaneTurn = null
            stopGuidanceOutputLocked()
            // Hand the cluster's navigation card back. Only after the clear calls above, so the card is
            // blank before it is re-pointed at whatever the head unit wanted to show.
            val app = context
            if (guidanceActive && app != null && EcarxOutputSettings.navigationProjection(app)) {
                EcarxProjectionChannel.setNavigationProjection(false)
            }
            if (guidanceActive) {
                guidanceActive = false
                val released = applyPresentation(false, "guidance ended")
                NavigationOutputs.diagnostic("cleanup release=${outcome(released)}")
                report("DIM navigation end release ${outcome(released)} detail=${released.reason}")
                report("DIM guidance ended $lastGuidanceEnd")
                // Hand the panel back: the presentation stops requesting the cluster stream, so the
                // head unit's own instrument page returns instead of a frozen last map frame.
                clusterPresentationRequested?.invoke(mapIntent)
            }
        } finally {
            hudOutputTouched = false
            context?.let { report(EcarxFactoryNavigationRestore.restoreIfPausedByUs(it)) }
            NavigationOutputs.navigationExitFinished(reason.substringBefore(' '))
        }
    }

    /**
     * One [EcarxDimApi.Guidance] per maneuver. Distance, destination distance, ETA, the road name
     * and the live/idle status are recovered data; `turnId` comes from the recovered code table and
     * `drivingDirection` is the documented placeholder (see the class comment).
     */
    private fun guidanceFor(maneuver: AppleManeuver, etaSeconds: Int): EcarxDimApi.Guidance = EcarxDimApi.Guidance(
        turnId = EcarxArrowCode.arrowId(maneuver.type, maneuver.drivingSide),
        distanceToNextMeters = maneuver.distanceMeters.toLong(),
        distanceToDestinationMeters = maneuver.remainingMeters ?: -1L,
        etaRemainingSeconds = etaSeconds,
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
        append(" hudActive=").append(hudActive)
        append(" autoShowMap=").append(EcarxOutputSettings.autoShowMap(context))
        append(" presentation=").append(clusterVisibleWanted)
        append(" arrowExperimental=").append(EcarxOutputSettings.turnArrowExperimental(context))
        append(" media=").append(EcarxOutputSettings.mediaEnabled(context))
        append(" factoryNavigation=[").append(EcarxFactoryNavigationRestore.state(context)).append(']')
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
            append(" displayIntent=").append(clusterVisibleWanted)
            append(" lastPlatformOption=").append(presentationOption)
            append(" releasePending=").append(EcarxOutputSettings.presentationReleasePending(context))
            append(" startupReleaseActive=").append(releaseStalePresentation)
            append(" startupReleaseOrigin=").append(startupReleaseOrigin)
            append(" startupReleaseAttempts=").append(startupReleaseAttempts)
            append(" projectionAttempts=").append(presentationAttempts)
            // The package the running mirror was opened with; empty means "the whole centre screen",
            // which is the picture the driver rejected as not fitting the cluster.
            append(" projectionPackage=").append(presentedPackage ?: "")
            append(" arrowId=").append(lastPushed?.turnId ?: -1)
            append(" active=").append(guidanceActive)
            append(" laneCount=").append(lastLanePushed?.size ?: 0)
            append(" lastSdkOutput=").append(lastReassertOutcome.ifEmpty { "none" })
            append(" phase=").append(phase).append(" lastPhoneState=").append(route.lastPhoneState)
            append(" stateAgeMs=").append(route.phoneStateAgeMs).append(" visibilityEvents=").append(visibilityCount)
            append(" reassertions=").append(reassertCount).append(" arrivalNotices=0")
            append(" route=[").append(route.diagnosticState()).append("] lastEnd=[").append(lastGuidanceEnd).append(']')
            append(" status=").append(if (guidanceActive) NAVI_STATUS_ACTIVE else NAVI_STATUS_IDLE)
            // The only read-back the API offers. Uninterpreted on purpose — it stays -1 while the
            // cluster mirrors (probe v3/v4), so it is printed for correlation, never acted on.
            dim?.let { append(" showPresentationOption=").append(EcarxDimApi.showPresentationOption(it)) }
        }
        append(EcarxProjectionChannel.state())
    }
}
