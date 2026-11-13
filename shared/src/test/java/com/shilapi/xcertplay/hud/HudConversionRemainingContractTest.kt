package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Contracts from the original Kotlin tables, including the empty-angle status-0 distinction. */
class HudConversionRemainingContractTest {
    @Test fun anUnhighlightedLaneStillUsesItsOnlyHighlightFieldAsAnAbsentDirectionList() {
        for ((angle, icon) in listOf(0 to 0, -90 to 1, -45 to 2, 90 to 3, 45 to 4, -180 to 5)) {
            assertEquals(icon, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 0, emptyList(), angle)))
        }
        assertNull(EcarxLaneIcon.forLane(LaneGuidanceLane(0, 0, emptyList(), null)))
        assertNull(EcarxLaneIcon.forLane(LaneGuidanceLane(0, 0, emptyList(), 1000)))
    }

    @Test fun statusZeroIgnoresHighlightWhenActualDirectionsArePresent() {
        assertEquals(1, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 0, listOf(-90), 90)))
        assertEquals(35, EcarxLaneIcon.forLane(LaneGuidanceLane(0, 2, listOf(-90), 90)))
        assertNull(EcarxLaneIcon.forLane(LaneGuidanceLane(0, 3, emptyList(), -90)))
    }

    @Test fun roundaboutBoundariesAndUnknownDrivingSidesKeepTheirExistingCodes() {
        assertEquals(111, EcarxArrowCode.amapToEcarx(11, 0))
        assertEquals(121, EcarxArrowCode.amapToEcarx(11, 1))
        assertEquals(130, EcarxArrowCode.amapToEcarx(11, 10))
        assertEquals(111, EcarxArrowCode.amapToEcarx(11, 11))
        assertEquals(12, EcarxArrowCode.ecarxToAmap(112))
        assertEquals(18, EcarxArrowCode.ecarxToAmap(118))
        for (maneuver in listOf(8, 9)) {
            assertEquals(104, EcarxArrowCode.arrowId(maneuver, 1))
            for (side in listOf(Int.MIN_VALUE, -1, 0, 2, Int.MAX_VALUE)) {
                assertEquals(105, EcarxArrowCode.arrowId(maneuver, side))
            }
        }
    }
}
