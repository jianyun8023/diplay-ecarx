package com.shilapi.xcertplay.hud

/**
 * The next maneuver exactly as Apple sent it, for the CarPlay outputs that render it.
 *
 * `type` and `drivingSide` are Apple's own RouteGuidanceManeuverType / DrivingSide values, kept
 * unconverted on purpose: each output maps them to its own icon vocabulary, and a vendor that has
 * no recovered code table must not pretend otherwise.
 */
internal data class AppleManeuver(
    val distanceMeters: Int,
    val type: Int,
    val drivingSide: Int,
    val road: String = "",
    val remainingSeconds: Long? = null,
    val remainingMeters: Long? = null,
    val arrivalEpochSeconds: Long? = null,
    /** What 0x05 actually carried, before any epoch conversion. Reported, never displayed. */
    val etaRawSeconds: Long? = null,
)

internal enum class RouteGuidanceChange {
    NONE,
    GUIDANCE,
    CLEAR,
}

/**
 * Rust owns the iAP2 route-guidance state (0x5201/0x5202); Kotlin supplies clocks/session input.
 *
 * This is vendor-neutral: it turns Apple's frames into [AppleManeuver], and it is shared by
 * the ECARX cluster output and by the widget-facing glance so both agree on what the next turn is.
 */
internal class RouteGuidanceState(
    private val nanoTime: () -> Long = System::nanoTime,
    staleRouteNs: Long = 30_000_000_000L,
    emptyListHideNs: Long = 3_000_000_000L,
    keepAcrossNoRoute: Boolean = false,
    private val liveSession: () -> Boolean = { false },
    private val wallClockSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : AutoCloseable {
    private val fallback = if (com.shilapi.xcertplay.airplay.NativeMedia.available) null else
        KotlinRouteGuidanceState({ nanoTime() }, { wallClockSeconds() }, staleRouteNs,
            emptyListHideNs, keepAcrossNoRoute, { liveSession() })
    // The two output singletons retain their owner for the process. Temporary owners call close().
    private var handle = if (fallback == null)
        NativeRouteGuidance.create(staleRouteNs, emptyListHideNs, keepAcrossNoRoute).also { check(it != 0L) }
        else 0L
    private var closed = false

    val activeIndex: Int @Synchronized get() = fallback?.activeIndex ?: query(0).toInt()
    val announced: Boolean @Synchronized get() = fallback?.announced ?: (query(1) != 0L)
    val inputState: Int? @Synchronized get() = if (fallback != null) fallback.inputState else query(2).takeIf { it >= 0 }?.toInt()
    val lastPhoneState: Int? @Synchronized get() = if (fallback != null) fallback.lastPhoneState else query(3).takeIf { it >= 0 }?.toInt()
    val laneGuidanceIndex: Int? @Synchronized get() = if (fallback != null) fallback.laneGuidanceIndex else query(4).takeIf { it >= 0 }?.toInt()
    val laneGuidanceShowing: Boolean? @Synchronized get() = if (fallback != null) fallback.laneGuidanceShowing else query(5).takeIf { it >= 0 }?.let { it != 0L }
    val arrivalEpochSeconds: Long? @Synchronized get() = if (fallback != null) fallback.arrivalEpochSeconds else
        if (query(8) != 0L) query(6) else null
    val remainingMeters: Long? @Synchronized get() = if (fallback != null) fallback.remainingMeters else query(7).takeIf { it >= 0 }
    val phoneStateAgeMs: Long? @Synchronized get() = if (fallback != null) fallback.phoneStateAgeMs else
        if (lastPhoneState != null) NativeRouteGuidance.phoneStateAge(owner(), nanoTime()) else null
    val expired: Boolean @Synchronized get() = fallback?.expired ?: NativeRouteGuidance.expired(owner(), nanoTime(), liveSession())
    val destinationName: String @Synchronized get() = fallback?.destinationName ?: NativeRouteGuidance.text(owner(), 0, 0, false)
    val diagnosticInput: String @Synchronized get() = fallback?.diagnosticInput ?: NativeRouteGuidance.text(owner(), 1, 0, false)

    @Synchronized fun accept(messageId: Int, payload: ByteArray): RouteGuidanceChange =
        fallback?.accept(messageId, payload) ?: RouteGuidanceChange.entries[
            NativeRouteGuidance.accept(owner(), messageId, payload, nanoTime(), wallClockSeconds(), liveSession())]

    @Synchronized fun current(): AppleManeuver? = if (fallback != null) fallback.current() else
        NativeRouteGuidance.current(owner(), nanoTime(), liveSession(), false)

    @Synchronized fun currentForDim(): AppleManeuver? = if (fallback != null) fallback.currentForDim() else
        NativeRouteGuidance.current(owner(), nanoTime(), liveSession(), true)

    @Synchronized fun clear(reason: String = "clear"): Boolean = fallback?.clear(reason) ?:
        NativeRouteGuidance.clear(owner(), reason)

    @Synchronized fun diagnosticState(): String = fallback?.diagnosticState() ?:
        NativeRouteGuidance.text(owner(), 2, nanoTime(), liveSession())

    private fun query(field: Int): Long = NativeRouteGuidance.query(owner(), field)
    private fun owner(): Long { check(!closed); return handle }

    @Synchronized override fun close() {
        if (closed) return
        if (fallback != null) fallback.clear() else NativeRouteGuidance.destroy(handle)
        handle = 0L
        closed = true
    }

    companion object {
        const val ROUTE_GUIDANCE_UPDATE = 0x5201
        const val ROUTE_GUIDANCE_MANEUVER_UPDATE = 0x5202
    }
}
