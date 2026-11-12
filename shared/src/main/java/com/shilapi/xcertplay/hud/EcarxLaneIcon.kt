package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.airplay.NativeMedia
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane

/**
 * IDs observed in the ECARX lane assets and the linked Geely lane mapping. Rust owns the pure
 * lookup; the HUD/cluster adapters stay Kotlin. The Kotlin copy below runs only when the native
 * library cannot load.
 */
internal object EcarxLaneIcon {
    fun forLane(lane: LaneGuidanceLane): Int? =
        if (NativeMedia.available) {
            NativeLaneIcon.forLane(
                lane.status, lane.angles.toIntArray(), lane.highlightAngle ?: 0, lane.highlightAngle != null,
            ).takeIf { it >= 0 }
        } else KotlinLaneIcon.forLane(lane)
}

/** Library-unavailable fallback; the native path never creates this state. */
internal object KotlinLaneIcon {
    fun forLane(lane: LaneGuidanceLane): Int? {
        if (lane.status !in 0..2) return null
        val highlight = lane.highlightAngle.takeIf { lane.status != 0 }
        val directions = if (lane.angles.isEmpty()) setOfNotNull(lane.highlightAngle)
            else (lane.angles + listOfNotNull(highlight)).toSet()
        val base = when (directions) {
            setOf(0) -> 0
            setOf(-90) -> 1
            setOf(-45), setOf(-90, 0) -> 2
            setOf(90) -> 3
            setOf(45), setOf(0, 90) -> 4
            setOf(-180) -> 5
            setOf(-90, 90) -> 6
            setOf(-90, 0, 90) -> 7
            setOf(-180, -90) -> 11
            // This OEM family has its own recommended ID (56), outside the usual +15 range.
            setOf(-180, -90, 0) -> return when (highlight) {
                0 -> 71
                -90 -> 72
                -180 -> 73
                else -> if (lane.recommended) 56 else 49
            }
            else -> return null
        }
        // The two-tone OEM icons retain the other paths while lighting only the selected one.
        if (directions.size > 1 && highlight != null) return when (base) {
            2 -> if (highlight == 0) 30 else 31
            4 -> if (highlight == 0) 32 else 33
            6 -> if (highlight == -90) 34 else 35
            7 -> when (highlight) {
                0 -> 36
                -90 -> 37
                else -> 38
            }
            11 -> if (highlight == -90) 43 else 44
            else -> null
        }
        return base + if (highlight != null || lane.recommended) 15 else 0
    }
}

internal object NativeLaneIcon {
    /** -1 for no icon; [hasHighlight] false makes [highlightAngle] irrelevant. */
    @JvmStatic external fun forLane(status: Int, angles: IntArray, highlightAngle: Int, hasHighlight: Boolean): Int
}
