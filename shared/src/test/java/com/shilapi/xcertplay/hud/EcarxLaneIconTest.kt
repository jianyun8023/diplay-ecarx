package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import org.junit.Assert.*
import org.junit.Test

class EcarxLaneIconTest {
    @Test fun knownDirectionsAndRecommendedVariantsMatchOemIds() {
        assertEquals(1, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 0, listOf(-90), null)))
        assertEquals(0, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 0, listOf(0), null)))
        assertEquals(15, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 2, listOf(0), 90)))
        assertEquals(2, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 0, listOf(-90, 0), null)))
        assertEquals(18, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 2, emptyList(), 90)))
    }

    @Test fun unknownDirectionsAndStatusesStayUnmapped() {
        assertNull(EcarxLaneIcon.forLane(LaneGuidanceLane(0, 0, emptyList(), null)))
        assertNull(EcarxLaneIcon.forLane(LaneGuidanceLane(0, 0, listOf(1000), null)))
        assertNull(EcarxLaneIcon.forLane(LaneGuidanceLane(0, 1, listOf(0), null)))
        assertEquals(2, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 0, listOf(-90, -90, 0), 90)))
    }

}
