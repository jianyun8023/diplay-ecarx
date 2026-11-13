package com.shihab.diplay.hud

import com.shihab.diplay.iap2.message.LaneGuidanceLane
import org.junit.Assert.*
import org.junit.Test

class EcarxLaneIconTest {
    @Test fun knownDirectionsAndRecommendedVariantsMatchOemIds() {
        assertEquals(1, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 0, listOf(-90), null)))
        assertEquals(0, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 0, listOf(0), null)))
        assertEquals(33, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 2, listOf(0), 90)))
        assertEquals(30, EcarxLaneIcon.forLane(LaneGuidanceLane(2, 2, listOf(-90), 0)))
        assertEquals(31, EcarxLaneIcon.forLane(LaneGuidanceLane(2, 1, listOf(0), -90)))
        assertEquals(2, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 0, listOf(-90, 0), null)))
        assertEquals(18, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 2, emptyList(), 90)))
    }

    @Test fun unknownDirectionsAndStatusesStayUnmapped() {
        assertNull(EcarxLaneIcon.forLane(LaneGuidanceLane(0, 0, emptyList(), null)))
        assertNull(EcarxLaneIcon.forLane(LaneGuidanceLane(0, 0, listOf(1000), null)))
        assertNull(EcarxLaneIcon.forLane(LaneGuidanceLane(0, 3, listOf(0), null)))
        assertEquals(2, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 0, listOf(-90, -90, 0), 90)))
    }

    @Test fun compoundLeftUTurnKeepsEveryDirectionAndHighlightsTheSelectedBranch() {
        val leftAndUTurn = listOf(-180, -90)
        val threeDirections = listOf(0, -180, -90)
        // Apple sent the straight branch only as the highlight on junction index 5.
        assertEquals(71, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 2, leftAndUTurn, 0)))
        assertEquals(11, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 0, leftAndUTurn, null)))
        assertEquals(26, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 2, leftAndUTurn, null)))
        assertEquals(43, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 2, leftAndUTurn, -90)))
        assertEquals(44, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 2, leftAndUTurn, -180)))
        assertEquals(49, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 0, threeDirections, null)))
        assertEquals(56, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 2, threeDirections, null)))
        assertEquals(72, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 1, threeDirections, -90)))
        assertEquals(73, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 2, threeDirections, -180)))
    }

    @Test fun recordedBaiduStripKeepsBothUnhighlightedLanes() {
        val lanes = listOf(
            LaneGuidanceLane(0, 2, emptyList(), -90),
            LaneGuidanceLane(1, 1, listOf(0), null),
            LaneGuidanceLane(2, 1, listOf(90, 0), null),
        )
        assertEquals(listOf(16, 0, 4), lanes.map(EcarxLaneIcon::forLane))
        assertEquals(5, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 0, listOf(-180), null)))
    }

}
