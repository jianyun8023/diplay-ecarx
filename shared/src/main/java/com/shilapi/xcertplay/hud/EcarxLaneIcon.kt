package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane

/** IDs observed in the ECARX lane assets and the linked Geely lane mapping. */
internal object EcarxLaneIcon {
    fun forLane(lane: LaneGuidanceLane): Int? {
        if (lane.status != 0 && lane.status != 2) return null
        val directions = (if (lane.angles.isNotEmpty()) lane.angles else listOfNotNull(lane.highlightAngle)).toSet()
        val base = when (directions) {
            setOf(0) -> 0
            setOf(-90) -> 1
            setOf(-45), setOf(-90, 0) -> 2
            setOf(90) -> 3
            setOf(45), setOf(0, 90) -> 4
            setOf(-90, 90) -> 6
            setOf(-90, 0, 90) -> 7
            else -> return null
        }
        return base + if (lane.recommended) 15 else 0
    }
}
