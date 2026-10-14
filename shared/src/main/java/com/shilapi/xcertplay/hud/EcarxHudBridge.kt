package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Publishes CarPlay guidance to an ECARX (亿咖通) instrument cluster through the XUI DIM API.
 *
 * The call sequence is the one the shipped ECARX toolkit uses, recovered from
 * `tools-apktool/smali/s1/n.smali` — a Runnable the toolkit posts every 1000 ms:
 * ```
 * navi.updateNavigationInfo(info); navi.updateTurnByTurnArrow(v++);
 * ```
 * so the field order and the 1 s cadence here are not invented.
 *
 * What is proven vs. what needs calibration on the car:
 *  * **Proven.** `updateNavigationInfo` with the 15-getter `INavigationInfo` payload; the getter
 *    set is exhaustive (`s1/r.smali` implements exactly these); `updateDistanceToDestination`,
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
     * `fill` values, the core width and the geometry wrong (see [arrowSubpaths]).
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
    /** [arrowSubpaths] is written in `[-100, 100]`; this maps it onto the 255 canvas. */
    private const val SVG_SCALE = SVG_RADIUS / 100f

    /** Silhouette proportions, in the same `[-100, 100]` space [arrowSubpaths] uses. */
    private const val SHAFT_HALF = 22
    private const val HEAD_HALF = 62
    private const val HEAD_LEN = 80
    /** A U-turn is a band, not a stroke: these are its outer and inner radii. */
    private const val UTURN_OUTER = 62
    private const val UTURN_INNER = 20
    /** Roundabout ring, and the pin head of the destination marker. */
    private const val RING_OUTER = 58
    private const val RING_INNER = 32
    private const val PIN_HEAD = 45

    /** Points per half-circle. Sixteen is smooth at this size and keeps the markup short. */
    private const val ARC_STEPS = 16

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
    private var available = false
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

    private var lastPushed: EcarxDimApi.Guidance? = null
    /** Which arrow channel the last frame left on, so the report prints the changes and not the ticks. */
    private var lastArrowChannel: String? = null
    private var ticksSincePush = 0
    private var guidanceActive = false

    /**
     * A screen appearing or disappearing is the closest thing to "the car was just started" that a
     * third-party app can observe, so it cancels the backoff instead of making the cluster wait
     * for the next keepalive. On this head unit the cluster screen is not in `getDisplays()` at all
     * (probe v4), so this is a hint, never the mechanism — the keepalive is.
     */
    private val displayWatch = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = nudgeProjection("display $displayId added")
        override fun onDisplayRemoved(displayId: Int) = nudgeProjection("display $displayId removed")
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    /** Resolves the DIM handles once per process. Safe to call from any thread; idempotent. */
    fun initialize(appContext: Context) = synchronized(lock) {
        if (context != null) return@synchronized
        val app = appContext.applicationContext
        context = app
        available = EcarxCapabilities.available(app)
        if (!available) {
            report("DIM interface absent, output disabled: ${EcarxCapabilities.describe(app)}")
            return@synchronized
        }
        // Seed the watch from the saved intent before the first attempt, so a head unit that comes
        // back with the engine already running restores the cluster without the user touching it.
        presentationWanted = EcarxOutputSettings.presentationSwitch(app)
        reclaimLocked()
        watchDisplaysLocked(app)
        report(
            "DIM ready navi=${navi != null} protocol=${protocol != null} " +
                "dimCb=${dimCallback != null} naviCb=${naviCallback != null} " +
                "projection=$presentationWanted ${EcarxCapabilities.describe(app)}",
        )
        // Re-assert the persisted OEM-side switches so a restart restores the cluster state.
        // Only the projection switch: setNaviMode is an unverified call (see NAVI_MODE_UNSET) and
        // is left alone until the user actually asks for it.
        EcarxOutputSettings.naviMode(app)
            .takeIf { it >= 0 }
            ?.let { applyNaviModeLocked(it) }
        if (presentationWanted) requestPresentationLocked()
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
     * exactly what happens. Unlike [available], which is a class-presence test and cannot change,
     * these handles can appear later, so the retry is the point.
     */
    private fun reclaimLocked(): Boolean {
        val app = context ?: return false
        val hadNavi = navi != null
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

    fun onFrame(frame: Iap2Frame) = synchronized(lock) {
        if (!available) return@synchronized
        when (route.accept(frame.messageId, frame.payload)) {
            RouteGuidanceChange.GUIDANCE -> pushLocked(force = false)
            RouteGuidanceChange.CLEAR -> if (lastPushed != null) endLocked()
            RouteGuidanceChange.NONE -> Unit
        }
    }

    fun clear() = synchronized(lock) {
        route.clear() // Stop the keepalive from restoring guidance after cleanup.
        if (lastPushed != null) endLocked()
    }

    /** Whether guidance is currently on the cluster. */
    fun isActive(): Boolean = synchronized(lock) { lastPushed != null }

    // ---------------------------------------------------------------- OEM switches

    /**
     * The 仪表投屏 switch. Mirrors the centre screen onto the cluster; both shipped ECARX apps
     * toggle it with `setPresentationToDimSwitch(1, "", "", show)`.
     *
     * Turning it on records the intent and arms the watch. It does **not** report whether the
     * cluster changed, because nothing can: the platform call is `void`, unchecked, and returns the
     * same thing whether or not the cluster is even powered. [EcarxResult.ok] here means "the watch
     * owns delivery now"; the switch reports a failure only when the head unit has no DIM
     * interface at all, which is the one case where retrying is pointless.
     */
    fun applyPresentation(show: Boolean): EcarxDimApi.EcarxResult = synchronized(lock) {
        val app = context ?: return EcarxDimApi.EcarxResult.failed("DIM not initialized")
        EcarxOutputSettings.setPresentationSwitch(app, show)
        presentationWanted = show
        if (!show) {
            presentationAttempts = 0
            presentationCountdown = 0
            return applyPresentationLocked(false)
        }
        if (!available) return EcarxDimApi.EcarxResult.failed("no DIM interface on this head unit")
        requestPresentationLocked()
        EcarxDimApi.EcarxResult.ok("projection watch armed")
    }

    /** One attempt now and a fresh fast phase, so a manual toggle is never throttled by the watch. */
    private fun requestPresentationLocked(): EcarxDimApi.EcarxResult {
        val result = applyPresentationLocked(true)
        presentationAttempts = 1
        presentationCountdown = PRESENTATION_FAST_SECONDS - 1
        report("projection armed; first attempt ${result.reason}")
        return result
    }

    private fun applyPresentationLocked(show: Boolean): EcarxDimApi.EcarxResult {
        val interaction = dim ?: return EcarxDimApi.EcarxResult.failed("DimInteraction missing")
        val result = EcarxDimApi.setPresentationToDimSwitch(interaction, show)
        if (!result.ok) report("presentation switch failed: ${result.reason}")
        else report("presentation switch -> $show")
        return result
    }

    /** `DIMProtocolManager.setNaviMode` — makes the cluster prefer an external navigation source. */
    fun applyNaviMode(mode: Int): EcarxDimApi.EcarxResult = synchronized(lock) {
        val app = context ?: return EcarxDimApi.EcarxResult.failed("DIM not initialized")
        EcarxOutputSettings.setNaviMode(app, mode)
        applyNaviModeLocked(mode)
    }

    private fun applyNaviModeLocked(mode: Int): EcarxDimApi.EcarxResult {
        if (mode < 0) return EcarxDimApi.EcarxResult.failed("navi mode never requested")
        val manager = protocol ?: return EcarxDimApi.EcarxResult.failed("DIMProtocolManager missing")
        val result = EcarxDimApi.setNaviMode(manager, mode)
        if (!result.ok) report("setNaviMode($mode) failed: ${result.reason}")
        else report("cluster navi mode -> $mode")
        return result
    }

    // ---------------------------------------------------------------- push path

    private fun tick() = synchronized(lock) {
        if (!available) return@synchronized
        // The cluster service is not up when the head unit is only on accessory power, so keep
        // asking for the handles as well as for the projection itself.
        if (dim == null || navi == null) reclaimLocked()
        watchProjectionLocked()
        // Guidance can expire without a frame (a list that stays empty), so check every second.
        if (lastPushed != null && route.current() == null) endLocked()
        else if (++ticksSincePush >= KEEPALIVE_TICKS) pushLocked(force = true)
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
            if (lastPushed != null) endLocked()
            return
        }
        val naviRef = navi ?: return
        val maneuver = route.current()
        if (maneuver == null) {
            if (lastPushed != null) endLocked()
            return
        }
        val guidance = guidanceFor(maneuver)
        if (!force && guidance == lastPushed) return

        // Order matches the toolkit: the aggregate payload first, then the discrete fields.
        val result = EcarxDimApi.updateNavigationInfo(naviRef, guidance)
        if (!result.ok) {
            report("updateNavigationInfo failed: ${result.reason}")
            return
        }
        val firstFrame = lastPushed == null
        if (firstFrame) EcarxDimApi.notifyTurnByTurnStarted(naviRef)
        EcarxDimApi.updateDistanceToNextGuidancePoint(naviRef, maneuver.distanceMeters.toLong())
        maneuver.remainingMeters?.let { EcarxDimApi.updateDistanceToDestination(naviRef, it) }
        maneuver.arrivalEpochSeconds?.let { EcarxDimApi.updateEta(naviRef, it) }
        maneuver.road.takeIf { it.isNotEmpty() }
            ?.let { EcarxDimApi.updateNextGuidancePointName(naviRef, it) }
        if (EcarxOutputSettings.turnArrowExperimental(app)) {
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
        if (!guidanceActive) {
            guidanceActive = true
            report(
                "DIM guidance started arrowId=${guidance.turnId} next=${maneuver.distanceMeters}m " +
                    "road=\"${maneuver.road}\"",
            )
        }
    }

    /**
     * Sends the next-turn arrow on the best channel this head unit actually offers, and names the one
     * that took, for the report.
     *
     * Each fallback is tried only after the one above it *failed the lookup*, which is not the same
     * as "the cluster ignored it": these are `void` binder calls and a head unit that lacks the
     * overload reports `method not found`. The head unit's own navigation app uses the `(String)`
     * overload, so SVG is the primary path ([turnArrowSvg]); a locally drawn bitmap is the fallback
     * for a head unit without it, and the recovered code table (see [EcarxArrowCode]) the last resort.
     */
    private fun pushArrowLocked(
        navi: Any,
        maneuver: AppleManeuver,
        guidance: EcarxDimApi.Guidance,
    ): String {
        val svg = turnArrowSvg(maneuver.type, maneuver.drivingSide)
        if (svg != null && EcarxDimApi.updateTurnByTurnArrow(navi, svg).ok) return "svg:${svg.length}B"
        val bitmap = turnArrowBitmap(maneuver.type, maneuver.drivingSide)
        if (bitmap != null && EcarxDimApi.updateTurnByTurnArrow(navi, bitmap).ok) return "bitmap"
        val id = guidance.turnId
        if (id > 0 && EcarxDimApi.updateTurnByTurnArrow(navi, id).ok) return "id:$id"
        return ARROW_OFF
    }

    private fun endLocked() {
        val naviRef = navi
        if (naviRef != null) {
            EcarxDimApi.notifyTurnByTurnStopped(naviRef)
            if (context?.let(EcarxOutputSettings::turnArrowExperimental) == true) {
                // Clear through the same family that drew it: an empty drawing first (the stock app's
                // own "no arrow" payload on the SVG channel), then the id the stock app reserves for
                // "nothing to show". Whichever overload this head unit lacks just refuses.
                EcarxDimApi.updateTurnByTurnArrow(naviRef, "")
                EcarxDimApi.updateTurnByTurnArrow(naviRef, EcarxArrowCode.NO_ARROW)
            }
            EcarxDimApi.updateDistanceToNextGuidancePoint(naviRef, 0)
            EcarxDimApi.updateNextGuidancePointName(naviRef, "")
            EcarxDimApi.updateLaneInfoEmpty(naviRef)
        }
        lastPushed = null
        lastArrowChannel = null
        ticksSincePush = 0
        if (guidanceActive) {
            guidanceActive = false
            report("DIM guidance ended")
        }
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

    /** `getDayNightMode()` is 0/1 in `s1/r`; the head unit's own day/night split drives the choice. */
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
     * geometry is ours. The stock app drives `d` from the navigation protocol's own vector points
     * (`IconRoadInfo.getVectorPoints()`), which a third-party CarPlay feed does not have, so both
     * layers are filled with the same silhouette instead: the thick dark layer then reads as a
     * ~12.6 px outline drawn round the thin white one — the stock icon's two-tone look, from one
     * contour. Returns null for a maneuver we have no drawing for.
     */
    private fun turnArrowSvg(type: Int, drivingSide: Int): String? {
        val kind = arrowKind(type, drivingSide) ?: return null
        val data = svgContour(kind)
        if (data.isEmpty()) return null
        val edge = SVG_PATH.format(data, SVG_EDGE_WIDTH, SVG_EDGE, SVG_EDGE)
        val core = SVG_PATH.format(data, SVG_CORE_WIDTH, SVG_CORE, SVG_CORE)
        return SVG_DOC.format(SVG_SIZE, SVG_SIZE, edge, core)
    }

    /**
     * [arrowSubpaths] as SVG `d` data: one `M…L…` run per subpath.
     *
     * The template's own `Z` closes the *last* subpath, so every subpath except the last is closed
     * explicitly, before the next one starts. Closing after the last one as well would put `ZZ` at
     * the end of the attribute, which the cluster's parser has no reason to be lenient about.
     *
     * Subpaths overlap rather than being merged, which is deliberate: `fill` and `stroke` are the
     * same colour within each layer, so the interior seams a boolean union would remove are already
     * invisible — and a real polygon union is a lot of code for no visible gain.
     *
     * Coordinates are integers all the way through, so nothing here can reach SVG as `1.0E-4`. That
     * was a live failure mode of the previous `StringBuilder.append(Float)` over `Path.approximate`.
     */
    private fun svgContour(kind: ArrowKind): String {
        val out = StringBuilder()
        for (sub in arrowSubpaths(kind)) {
            if (sub.size < 6) continue
            if (out.isNotEmpty()) out.append('Z')
            var i = 0
            while (i + 1 < sub.size) {
                out.append(if (i == 0) 'M' else 'L')
                out.append(px(sub[i])).append(',').append(px(sub[i + 1]))
                i += 2
            }
        }
        return out.toString()
    }

    /** A normalised `[-100, 100]` coordinate as a canvas pixel, rounded to the nearest. */
    private fun px(normalised: Int): Int = (SVG_HALF + normalised * SVG_SCALE + 0.5f).toInt()

    /**
     * The arrow silhouettes: one entry per closed polygon, in a normalised space whose centre is
     * `(0, 0)`, `x` grows right, `y` grows down, and `100` maps to [SVG_RADIUS]. A shape is usually
     * two polygons — a shaft and a head — which the non-zero fill rule unions for us.
     *
     * These are ours, not ECARX's: the stock set is Amap vector data we do not have. Apple's maneuver
     * is grouped into ten shapes the way DiPlay's own dashboard widget groups them, and the angled
     * ones are the straight arrow rotated, so a shape can only ever be wrong in one dimension.
     */
    private fun arrowSubpaths(kind: ArrowKind): List<IntArray> = when (kind) {
        ArrowKind.LEFT -> straightArrow(facingRight = false)
        ArrowKind.RIGHT -> straightArrow(facingRight = true)
        // `y` grows downwards, so a positive angle turns the head downwards on screen. A slight turn
        // points the arrow further "ahead"; a sharp one points it past the plain turn.
        ArrowKind.SLIGHT_LEFT -> rotate(straightArrow(facingRight = false), 45.0)
        ArrowKind.SLIGHT_RIGHT -> rotate(straightArrow(facingRight = true), -45.0)
        ArrowKind.SHARP_LEFT -> rotate(straightArrow(facingRight = false), -45.0)
        ArrowKind.SHARP_RIGHT -> rotate(straightArrow(facingRight = true), 45.0)
        // The U-turn doubles back, so it is an arc and not a rotated straight arrow.
        ArrowKind.U_TURN_LEFT -> uTurn(openLeft = true)
        ArrowKind.U_TURN_RIGHT -> uTurn(openLeft = false)
        ArrowKind.ROUNDABOUT -> listOf(
            circle(0, 0, RING_OUTER, clockwise = true),
            // Opposite winding, so the non-zero rule reads the inner ring as a hole.
            circle(0, 0, RING_INNER, clockwise = false),
            // The entry arrow, seated on the top of the band: the ring would read as a map pin
            // without it, and the head would punch through the hole if it reached any lower.
            intArrayOf(-28, -60, 28, -60, 0, -102),
        )
        ArrowKind.DESTINATION -> listOf(
            circle(0, -30, PIN_HEAD, clockwise = true),
            intArrayOf(-32, -14, 32, -14, 0, 66),
        )
    }

    /**
     * A straight arrow along `x`: the shaft runs the full width and the head sits on one end. The two
     * overlap by a few units so the joint cannot leave a notch in the outline.
     */
    private fun straightArrow(facingRight: Boolean): List<IntArray> {
        val base = 100 - HEAD_LEN
        val parts = listOf(
            intArrayOf(-100, -SHAFT_HALF, base + 10, -SHAFT_HALF, base + 10, SHAFT_HALF, -100, SHAFT_HALF),
            intArrayOf(base, -HEAD_HALF, 100, 0, base, HEAD_HALF),
        )
        return if (facingRight) parts else parts.map { mirrorX(it, -1) }
    }

    /** A half-annulus opening downwards, with the head on whichever end the driver leaves by. */
    private fun uTurn(openLeft: Boolean): List<IntArray> {
        val parts = listOf(
            arcBand(UTURN_OUTER, UTURN_INNER, 0.0, 180.0),
            intArrayOf(-UTURN_OUTER, 0, -UTURN_INNER, 0, -(UTURN_OUTER + UTURN_INNER) / 2, 72),
        )
        return if (openLeft) parts else parts.map { mirrorX(it, -1) }
    }

    /** Rotates every polygon of [points] by [degrees] clockwise on screen. */
    private fun rotate(points: List<IntArray>, degrees: Double): List<IntArray> {
        val a = Math.toRadians(degrees)
        val cos = Math.cos(a)
        val sin = Math.sin(a)
        return points.map { p ->
            val out = IntArray(p.size)
            var i = 0
            while (i + 1 < p.size) {
                val x = p[i].toDouble()
                val y = p[i + 1].toDouble()
                out[i] = Math.round(x * cos - y * sin).toInt()
                out[i + 1] = Math.round(x * sin + y * cos).toInt()
                i += 2
            }
            out
        }
    }

    /** Mirrors `x` about zero when [sign] is negative; returns [points] untouched otherwise. */
    private fun mirrorX(points: IntArray, sign: Int): IntArray {
        if (sign > 0) return points
        val out = IntArray(points.size)
        for (i in points.indices) out[i] = if (i % 2 == 0) -points[i] else points[i]
        return out
    }

    /** A closed half-annulus: the outer arc out, the inner arc back. Angles are degrees, `y` up. */
    private fun arcBand(outer: Int, inner: Int, fromDeg: Double, toDeg: Double): IntArray {
        val points = IntArray((ARC_STEPS + 1) * 4)
        var i = 0
        for (pass in 0..1) {
            val radius = if (pass == 0) outer else inner
            for (step in 0..ARC_STEPS) {
                val index = if (pass == 0) step else ARC_STEPS - step
                val t = Math.toRadians(fromDeg + (toDeg - fromDeg) * index / ARC_STEPS)
                points[i++] = Math.round(radius * Math.cos(t)).toInt()
                points[i++] = -Math.round(radius * Math.sin(t)).toInt()
            }
        }
        return points
    }

    /** A closed [steps]-gon. [clockwise] false reverses the winding, which is how a hole is cut. */
    private fun circle(cx: Int, cy: Int, radius: Int, clockwise: Boolean): IntArray {
        val steps = ARC_STEPS * 2
        val points = IntArray(steps * 2)
        for (step in 0 until steps) {
            val t = 2.0 * Math.PI * step / steps
            val x = Math.round(radius * Math.cos(t)).toInt()
            val y = Math.round(radius * Math.sin(t)).toInt()
            points[step * 2] = cx + if (clockwise) x else -x
            points[step * 2 + 1] = cy + y
        }
        return points
    }

    /**
     * Apple's `RouteGuidanceManeuverType`/`DrivingSide` to one of our arrow shapes, grouped the way
     * DiPlay's own dashboard widget groups them (see `NavigationWidget.arrow`). Null means Apple sent
     * a maneuver we have no drawing for, and the caller falls back.
     */
    private fun arrowKind(type: Int, drivingSide: Int): ArrowKind? = when (type) {
        1, 20 -> ArrowKind.LEFT
        2, 21 -> ArrowKind.RIGHT
        47 -> ArrowKind.SHARP_LEFT
        48 -> ArrowKind.SHARP_RIGHT
        13, 22, 49, 52 -> ArrowKind.SLIGHT_LEFT
        14, 23, 50, 53 -> ArrowKind.SLIGHT_RIGHT
        4, 18, 19, 26 -> if (drivingSide == 1) ArrowKind.U_TURN_RIGHT else ArrowKind.U_TURN_LEFT
        6, 7, in 28..46 -> ArrowKind.ROUNDABOUT
        10, 12, 24, 25, 27 -> ArrowKind.DESTINATION
        else -> null
    }

    /**
     * Draws the next-turn arrow locally and returns it as a bitmap — the fallback for a head unit
     * that has no `updateTurnByTurnArrow(String)` overload. Returns null for an unrecognised
     * maneuver so the caller falls back to the recovered code.
     *
     * It strokes the same [arrowSubpaths] the SVG push fills, so the two channels cannot show
     * different arrows.
     */
    private fun turnArrowBitmap(type: Int, drivingSide: Int): android.graphics.Bitmap? {
        val kind = arrowKind(type, drivingSide) ?: return null
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
        // Normalised 110 rather than 100, so a 12 px stroke cannot touch the edge of the bitmap.
        val scale = size / 2f / 110f
        val path = android.graphics.Path()
        for (sub in arrowSubpaths(kind)) {
            var i = 0
            while (i + 1 < sub.size) {
                val x = size / 2f + sub[i] * scale
                val y = size / 2f + sub[i + 1] * scale
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                i += 2
            }
            path.close()
        }
        canvas.drawPath(path, paint)
        return bitmap
    }

    private enum class ArrowKind {
        LEFT, RIGHT, SHARP_LEFT, SHARP_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT,
        U_TURN_LEFT, U_TURN_RIGHT, ROUNDABOUT, DESTINATION,
    }

    /** One-line state for the settings diagnostics block. */
    fun diagnostics(context: Context): String = buildString {
        append("ecarx ").append(EcarxCapabilities.describe(context))
        append(" enabled=").append(EcarxOutputSettings.enabled(context))
        append(" presentation=").append(EcarxOutputSettings.presentationSwitch(context))
        append(" naviMode=").append(
            EcarxOutputSettings.naviMode(context).takeIf { it >= 0 } ?: "unset",
        )
        append(" arrowExperimental=").append(EcarxOutputSettings.turnArrowExperimental(context))
        append(" media=").append(EcarxOutputSettings.mediaEnabled(context))
        synchronized(lock) {
            append(" wired=").append(available)
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
            append(" arrowId=").append(lastPushed?.turnId ?: -1)
            append(" active=").append(lastPushed != null)
            append(" status=").append(if (lastPushed != null) NAVI_STATUS_ACTIVE else NAVI_STATUS_IDLE)
            // The only read-back the API offers. Uninterpreted on purpose — it stays -1 while the
            // cluster mirrors (probe v3/v4), so it is printed for correlation, never acted on.
            dim?.let { append(" showPresentationOption=").append(EcarxDimApi.showPresentationOption(it)) }
        }
        // Outside the lock above: the media half has its own lock and its own state, and nesting the
        // two here would only buy an ordering rule to remember.
        append(EcarxMediaBridge.state())
    }
}
