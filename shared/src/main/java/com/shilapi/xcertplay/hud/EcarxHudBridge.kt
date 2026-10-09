package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.res.Configuration
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
 *    set is exhaustive (`s1/r.smali` implements exactly these 15); `updateDistanceToDestination`,
 *    `updateDistanceToNextGuidancePoint`, `updateETA`, `updateNextGuidancePointName`,
 *    `updateLaneInfo`, `notifyTurnByTurnStarted/Stopped` take the arguments used below.
 *  * **Needs calibration.** `getNavigationTurnId()` is a bare `int` with no lookup table in either
 *    shipped app — the toolkit fills it from a counter that also feeds `updateTurnByTurnArrow`,
 *    i.e. a demo ramp, not a real code table. The same holds for `getDrivingDirection()`. So the
 *    arrow is only pushed behind [EcarxOutputSettings.turnArrowExperimental], and the two
 *    unverified fields carry documented placeholders instead of a guessed enum.
 *
 * Every ECARX call is reflection-only (see [EcarxDimApi]): a direct reference would turn a
 * missing system class into a `NoClassDefFoundError` on every non-ECARX head unit.
 */
internal object EcarxHudBridge {
    private const val TAG = "DiPlay-ECARX-HUD"

    /** Re-send even when nothing changed, so the cluster never quietly drops the card. */
    private const val KEEPALIVE_TICKS = 5

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

    private val lock = Any()
    private val route = BydHudRouteState()
    private var context: Context? = null
    private var available = false
    private var dim: Any? = null
    private var navi: Any? = null
    private var protocol: Any? = null
    private var tickerStarted = false

    /** Bumped whenever the maneuver changes; the closest honest analogue of the toolkit's counter. */
    private var turnId = 0
    private var lastManeuverKey: String? = null
    private var lastPushed: EcarxDimApi.Guidance? = null
    private var ticksSincePush = 0
    private var guidanceActive = false

    /** Resolves the DIM handles once per process. Safe to call from any thread; idempotent. */
    fun initialize(appContext: Context) = synchronized(lock) {
        if (context != null) return@synchronized
        val app = appContext.applicationContext
        context = app
        available = EcarxCapabilities.available(app)
        if (!available) {
            Log.i(TAG, "DIM interface absent, output disabled: ${EcarxCapabilities.describe(app)}")
            return@synchronized
        }
        val interaction = EcarxDimApi.createDimInteraction(app)
        if (interaction == null) {
            available = false
            Log.w(TAG, "DimInteraction.create() returned null; DIM output inactive")
            return@synchronized
        }
        dim = interaction
        navi = EcarxDimApi.naviInteraction(interaction)
        protocol = EcarxDimApi.dimProtocolManager(app)
        Log.i(
            TAG,
            "DIM ready navi=${navi != null} protocol=${protocol != null} " +
                "${EcarxCapabilities.describe(app)}",
        )
        // Re-assert the persisted OEM-side switches so a restart restores the cluster state.
        applyNaviModeLocked(EcarxOutputSettings.naviMode(app))
        if (EcarxOutputSettings.presentationSwitch(app)) applyPresentationLocked(true)
        if (!tickerStarted) {
            tickerStarted = true
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "diplay-ecarx-hud").apply { isDaemon = true }
            }.scheduleAtFixedRate(::tick, 1, 1, TimeUnit.SECONDS)
        }
    }

    fun onFrame(frame: Iap2Frame) = synchronized(lock) {
        if (!available) return@synchronized
        when (route.accept(frame.messageId, frame.payload)) {
            BydHudRouteChange.GUIDANCE -> pushLocked(force = false)
            BydHudRouteChange.CLEAR -> if (lastPushed != null) endLocked()
            BydHudRouteChange.NONE -> Unit
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
     * toggle it with `setPresentationToDimSwitch(1, "", "", show)`. Applies at once and persists.
     */
    fun applyPresentation(show: Boolean): EcarxDimApi.EcarxResult = synchronized(lock) {
        val app = context ?: return EcarxDimApi.EcarxResult.failed("DIM not initialized")
        EcarxOutputSettings.setPresentationSwitch(app, show)
        applyPresentationLocked(show)
    }

    private fun applyPresentationLocked(show: Boolean): EcarxDimApi.EcarxResult {
        val interaction = dim ?: return EcarxDimApi.EcarxResult.failed("DimInteraction missing")
        val result = EcarxDimApi.setPresentationToDimSwitch(interaction, show)
        if (!result.ok) Log.w(TAG, "presentation switch failed: ${result.reason}")
        else Log.i(TAG, "presentation switch -> $show")
        return result
    }

    /** `DIMProtocolManager.setNaviMode` — makes the cluster prefer an external navigation source. */
    fun applyNaviMode(mode: Int): EcarxDimApi.EcarxResult = synchronized(lock) {
        val app = context ?: return EcarxDimApi.EcarxResult.failed("DIM not initialized")
        EcarxOutputSettings.setNaviMode(app, mode)
        applyNaviModeLocked(mode)
    }

    private fun applyNaviModeLocked(mode: Int): EcarxDimApi.EcarxResult {
        val manager = protocol ?: return EcarxDimApi.EcarxResult.failed("DIMProtocolManager missing")
        val result = EcarxDimApi.setNaviMode(manager, mode)
        if (!result.ok) Log.w(TAG, "setNaviMode($mode) failed: ${result.reason}")
        else Log.i(TAG, "cluster navi mode -> $mode")
        return result
    }

    // ---------------------------------------------------------------- push path

    private fun tick() = synchronized(lock) {
        if (!available) return@synchronized
        // Guidance can expire without a frame (a list that stays empty), so check every second.
        if (lastPushed != null && route.currentApple() == null) endLocked()
        else if (++ticksSincePush >= KEEPALIVE_TICKS) pushLocked(force = true)
    }

    private fun pushLocked(force: Boolean) {
        val app = context ?: return
        if (!EcarxOutputSettings.enabled(app)) {
            if (lastPushed != null) endLocked()
            return
        }
        val naviRef = navi ?: return
        val maneuver = route.currentApple()
        if (maneuver == null) {
            if (lastPushed != null) endLocked()
            return
        }
        val guidance = guidanceFor(maneuver)
        if (!force && guidance == lastPushed) return

        // Order matches the toolkit: the aggregate payload first, then the discrete fields.
        val result = EcarxDimApi.updateNavigationInfo(naviRef, guidance)
        if (!result.ok) {
            Log.w(TAG, "updateNavigationInfo failed: ${result.reason}")
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
            // Same value the toolkit pushed (its counter), not a manufactured code table.
            EcarxDimApi.updateTurnByTurnArrow(naviRef, guidance.turnId)
        }

        lastPushed = guidance
        ticksSincePush = 0
        if (!guidanceActive) {
            guidanceActive = true
            Log.i(TAG, "DIM guidance started turn=${guidance.turnId} road=\"${guidance.nextGuidancePointName}\"")
        }
    }

    private fun endLocked() {
        val naviRef = navi
        if (naviRef != null) {
            EcarxDimApi.notifyTurnByTurnStopped(naviRef)
            if (context?.let(EcarxOutputSettings::turnArrowExperimental) == true) {
                EcarxDimApi.updateTurnByTurnArrow(naviRef, 0)
            }
            EcarxDimApi.updateDistanceToNextGuidancePoint(naviRef, 0)
            EcarxDimApi.updateNextGuidancePointName(naviRef, "")
            EcarxDimApi.updateLaneInfoEmpty(naviRef)
        }
        lastPushed = null
        lastManeuverKey = null
        ticksSincePush = 0
        if (guidanceActive) {
            guidanceActive = false
            Log.i(TAG, "DIM guidance ended")
        }
    }

    /**
     * One [EcarxDimApi.Guidance] per maneuver. Only distance, destination distance, ETA, the road
     * name and the live/idle status are recovered data; `turnId` and `drivingDirection` are
     * documented placeholders (see the class comment).
     */
    private fun guidanceFor(maneuver: BydAppleManeuver): EcarxDimApi.Guidance {
        val key = "${maneuver.type}|${maneuver.drivingSide}|${maneuver.road}"
        if (key != lastManeuverKey) {
            lastManeuverKey = key
            turnId += 1
        }
        return EcarxDimApi.Guidance(
            turnId = turnId,
            distanceToNextMeters = maneuver.distanceMeters.toLong(),
            distanceToDestinationMeters = maneuver.remainingMeters ?: -1L,
            etaEpochSeconds = maneuver.arrivalEpochSeconds ?: -1L,
            drivingDirection = DRIVING_DIRECTION_UNKNOWN,
            status = NAVI_STATUS_ACTIVE,
            muteState = 0,
            dayNightMode = dayNightMode(),
            nextGuidancePointName = maneuver.road,
        )
    }

    /** `getDayNightMode()` is 0/1 in `s1/r`; the head unit's own day/night split drives the choice. */
    private fun dayNightMode(): Int {
        val app = context ?: return 0
        val night = app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) 1 else 0
    }

    /** One-line state for the settings diagnostics block. */
    fun diagnostics(context: Context): String = buildString {
        append("ecarx ").append(EcarxCapabilities.describe(context))
        append(" enabled=").append(EcarxOutputSettings.enabled(context))
        append(" presentation=").append(EcarxOutputSettings.presentationSwitch(context))
        append(" naviMode=").append(EcarxOutputSettings.naviMode(context))
        append(" arrowExperimental=").append(EcarxOutputSettings.turnArrowExperimental(context))
        synchronized(lock) {
            append(" wired=").append(available)
            append(" dim=").append(dim != null)
            append(" navi=").append(navi != null)
            append(" protocol=").append(protocol != null)
            append(" turnId=").append(turnId)
            append(" active=").append(lastPushed != null)
            append(" status=").append(if (lastPushed != null) NAVI_STATUS_ACTIVE else NAVI_STATUS_IDLE)
        }
    }
}
