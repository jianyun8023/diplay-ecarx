package com.shihab.diplay.hud

import com.shihab.diplay.airplay.NativeMedia
import com.shihab.diplay.iap2.message.LaneGuidanceLane

/**
 * IDs observed in the ECARX lane assets and the linked Geely lane mapping. Rust owns the pure
 * lookup; the HUD/cluster adapters stay Kotlin.
 */
internal object EcarxLaneIcon {
    init { NativeMedia.requireAvailable() }
    fun forLane(lane: LaneGuidanceLane): Int? =
        NativeLaneIcon.forLane(
            lane.status, lane.angles.toIntArray(), lane.highlightAngle ?: 0, lane.highlightAngle != null,
        ).takeIf { it >= 0 }
}

internal object NativeLaneIcon {
    /** -1 for no icon; [hasHighlight] false makes [highlightAngle] irrelevant. */
    @JvmStatic external fun forLane(status: Int, angles: IntArray, highlightAngle: Int, hasHighlight: Boolean): Int
}
