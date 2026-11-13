package com.shihab.diplay.hud

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
    init { com.shihab.diplay.airplay.NativeMedia.requireAvailable() }
    // The two output singletons retain their owner for the process. Temporary owners call close().
    private var handle = NativeRouteGuidance.create(staleRouteNs, emptyListHideNs, keepAcrossNoRoute)
        .also { check(it != 0L) }
    private var closed = false

    val activeIndex: Int @Synchronized get() = query(0).toInt()
    val announced: Boolean @Synchronized get() = (query(1) != 0L)
    val inputState: Int? @Synchronized get() = query(2).takeIf { it >= 0 }?.toInt()
    val lastPhoneState: Int? @Synchronized get() = query(3).takeIf { it >= 0 }?.toInt()
    val laneGuidanceIndex: Int? @Synchronized get() = query(4).takeIf { it >= 0 }?.toInt()
    val laneGuidanceShowing: Boolean? @Synchronized get() = query(5).takeIf { it >= 0 }?.let { it != 0L }
    val arrivalEpochSeconds: Long? @Synchronized get() = if (query(8) != 0L) query(6) else null
    val remainingMeters: Long? @Synchronized get() = query(7).takeIf { it >= 0 }
    val phoneStateAgeMs: Long? @Synchronized get() = if (lastPhoneState != null) NativeRouteGuidance.phoneStateAge(owner(), nanoTime()) else null
    val expired: Boolean @Synchronized get() = NativeRouteGuidance.expired(owner(), nanoTime(), liveSession())
    val destinationName: String @Synchronized get() = NativeRouteGuidance.text(owner(), 0, 0, false)
    val diagnosticInput: String @Synchronized get() = NativeRouteGuidance.text(owner(), 1, 0, false)

    @Synchronized fun accept(messageId: Int, payload: ByteArray): RouteGuidanceChange =
        RouteGuidanceChange.entries[
            NativeRouteGuidance.accept(owner(), messageId, payload, nanoTime(), wallClockSeconds(), liveSession())]

    @Synchronized fun current(): AppleManeuver? = NativeRouteGuidance.current(owner(), nanoTime(), liveSession(), false)

    @Synchronized fun currentForDim(): AppleManeuver? = NativeRouteGuidance.current(owner(), nanoTime(), liveSession(), true)

    @Synchronized fun clear(reason: String = "clear"): Boolean = NativeRouteGuidance.clear(owner(), reason)

    @Synchronized fun diagnosticState(): String = NativeRouteGuidance.text(owner(), 2, nanoTime(), liveSession())

    private fun query(field: Int): Long = NativeRouteGuidance.query(owner(), field)
    private fun owner(): Long { check(!closed); return handle }

    @Synchronized override fun close() {
        if (closed) return
        NativeRouteGuidance.destroy(handle)
        handle = 0L
        closed = true
    }

    companion object {
        const val ROUTE_GUIDANCE_UPDATE = 0x5201
        const val ROUTE_GUIDANCE_MANEUVER_UPDATE = 0x5202
    }
}
