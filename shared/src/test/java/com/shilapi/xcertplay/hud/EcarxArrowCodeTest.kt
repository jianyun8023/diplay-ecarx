package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class EcarxArrowCodeTest {
    @Test fun appleManeuversAndRecoveredTableProduceTheSameClusterCodes() {
        assertEquals(109, EcarxArrowCode.arrowId(0, 0))
        assertEquals(102, EcarxArrowCode.arrowId(1, 0))
        assertEquals(112, EcarxArrowCode.arrowId(7, 0))
        assertEquals(104, EcarxArrowCode.arrowId(8, 1))
        assertEquals(105, EcarxArrowCode.arrowId(8, 0))
        assertEquals(0, EcarxArrowCode.arrowId(99, 0))
    }

    @Test fun roundaboutPrefixAndReverseLookupMatchTheRecoveredTable() {
        assertEquals(123, EcarxArrowCode.amapToEcarx(11, 3))
        assertEquals(133, EcarxArrowCode.amapToEcarx(17, 3))
        assertEquals(112, EcarxArrowCode.amapToEcarx(12, 0))
        assertEquals(12, EcarxArrowCode.ecarxToAmap(112))
        assertEquals(18, EcarxArrowCode.ecarxToAmap(118))
        assertEquals(-1, EcarxArrowCode.ecarxToAmap(200))
    }
}
