package com.shihab.diplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WheelSiriKeyTest {
    @Test fun doublePressBoundariesAndClockReversalKeepOriginalStateTransitions() {
        val siri = WheelSiriKey()
        assertEquals(WheelSiriKey.DoublePressAction.FIRST, siri.onDoublePress(1_000, 600))
        assertEquals(WheelSiriKey.DoublePressAction.REPEAT, siri.onDoublePress(1_179, 600))
        assertEquals(WheelSiriKey.DoublePressAction.CONFIRMED, siri.onDoublePress(1_359, 600))
        assertEquals(WheelSiriKey.DoublePressAction.FIRST, siri.onDoublePress(2_000, 600))
        assertEquals(WheelSiriKey.DoublePressAction.CONFIRMED, siri.onDoublePress(2_600, 600))
        assertEquals(WheelSiriKey.DoublePressAction.FIRST, siri.onDoublePress(3_000, 600))
        assertEquals(WheelSiriKey.DoublePressAction.FIRST, siri.onDoublePress(3_601, 600))
        assertEquals(WheelSiriKey.DoublePressAction.FIRST, siri.onDoublePress(3_601, 600))
        assertEquals(WheelSiriKey.DoublePressAction.FIRST, siri.onDoublePress(3_500, 600))
        assertEquals(WheelSiriKey.DoublePressAction.CONFIRMED, siri.onDoublePress(3_680, 600))
    }

    @Test fun invalidWindowDoesNotChangeSharedSingleAndDoublePressState() {
        val siri = WheelSiriKey()
        assertTrue(siri.opens(1_000))
        assertFalse(siri.opens(1_100))
        try {
            siri.onDoublePress(1_200, 179)
            fail("expected invalid interval")
        } catch (_: IllegalArgumentException) { }
        assertEquals(WheelSiriKey.DoublePressAction.CONFIRMED, siri.onDoublePress(1_280, 600))
        assertTrue(siri.opens(2_000))
        assertEquals(WheelSiriKey.DoublePressAction.REPEAT, siri.onDoublePress(2_179, 600))
        assertFalse(siri.opens(2_578))
        assertTrue(siri.opens(2_978))
        siri.reset()
        assertTrue(siri.opens(1))
    }

    @Test fun clockArithmeticWrapsLikeJvmLong() {
        val siri = WheelSiriKey()
        assertTrue(siri.opens(Long.MAX_VALUE - 199))
        assertTrue(siri.opens(Long.MIN_VALUE + 200))
        siri.reset()
        assertEquals(WheelSiriKey.DoublePressAction.FIRST, siri.onDoublePress(Long.MAX_VALUE - 89, 600))
        assertEquals(WheelSiriKey.DoublePressAction.REPEAT, siri.onDoublePress(Long.MIN_VALUE + 89, 600))
        assertEquals(WheelSiriKey.DoublePressAction.CONFIRMED, siri.onDoublePress(Long.MIN_VALUE + 269, 600))
    }
    @Test fun pressesCloserThanTheGapBelongToOneHold() {
        val siri = WheelSiriKey()
        assertTrue(siri.opens(1_000))
        assertFalse(siri.opens(1_100))
        // The gap counts from the latest press, so a long hold stays one press.
        assertFalse(siri.opens(1_450))
        assertFalse(siri.opens(1_800))
        assertTrue(siri.opens(1_800 + WheelSiriKey.REPEAT_GAP_MILLIS))
    }

    @Test fun secondDistinctPressInsideTheConfiguredWindowOpensSiri() {
        val siri = WheelSiriKey()
        assertEquals(WheelSiriKey.DoublePressAction.FIRST, siri.onDoublePress(1_000, 600))
        assertEquals(WheelSiriKey.DoublePressAction.CONFIRMED, siri.onDoublePress(1_450, 600))
        // A completed pair never lends its second press to the next pair.
        assertEquals(WheelSiriKey.DoublePressAction.FIRST, siri.onDoublePress(1_700, 600))
        assertEquals(WheelSiriKey.DoublePressAction.CONFIRMED, siri.onDoublePress(2_250, 600))
    }

    @Test fun expiredPairsAndHeldKeyRepeatsDoNotOpenSiri() {
        val siri = WheelSiriKey()
        assertEquals(WheelSiriKey.DoublePressAction.FIRST, siri.onDoublePress(1_000, 500))
        assertEquals(WheelSiriKey.DoublePressAction.FIRST, siri.onDoublePress(1_700, 500))
        for (time in 1_800L..2_200L step 100) {
            assertEquals(WheelSiriKey.DoublePressAction.REPEAT, siri.onDoublePress(time, 500))
        }
        siri.reset()
        assertEquals(WheelSiriKey.DoublePressAction.FIRST, siri.onDoublePress(3_000, 500))
        assertEquals(WheelSiriKey.DoublePressAction.CONFIRMED, siri.onDoublePress(3_300, 500))
    }
}
