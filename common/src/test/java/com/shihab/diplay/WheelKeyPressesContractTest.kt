package com.shihab.diplay

import com.shihab.diplay.WheelZoomKeys.Action
import org.junit.Assert.*
import org.junit.Test

class WheelKeyPressesContractTest {
    @Test fun interleavedKeysKeepBothPassAndConsumeUntilMatchingUp() {
        val presses = WheelKeyPresses()
        val a = String(charArrayOf('a'))
        val equalA = String(charArrayOf('a'))
        val b = Any()
        var calls = 0
        fun event(key: Any?, down: Boolean, first: Boolean, action: Action): Action =
            presses.filter(key, down, first) { calls++; action }
        assertEquals(Action.PASS, event(null, true, true, Action.ZOOM_IN))
        assertEquals(Action.PASS, event(a, true, false, Action.ZOOM_IN))
        assertEquals(0, calls)
        assertEquals(Action.PASS, event(a, true, true, Action.PASS))
        assertEquals(Action.MODE_ON, event(b, true, true, Action.MODE_ON))
        assertEquals(Action.PASS, event(equalA, true, true, Action.ZOOM_IN))
        assertEquals(Action.CONSUME, event(b, true, false, Action.ZOOM_OUT))
        assertFalse(presses.hasConsumedPress(equalA))
        assertTrue(presses.hasConsumedPress(b))
        assertEquals(Action.PASS, event(equalA, false, false, Action.ZOOM_IN))
        assertEquals(Action.CONSUME, event(b, false, true, Action.PASS))
        assertEquals(Action.PASS, event(b, false, true, Action.ZOOM_IN))
        assertEquals(2, calls)
        assertEquals(Action.ZOOM_IN, event(a, true, true, Action.ZOOM_IN))
        assertEquals(3, calls)
    }

    @Test fun callbackFailureDoesNotCommitAndOuterDecisionCommitsAfterReentry() {
        val presses = WheelKeyPresses()
        val key = Any()
        try {
            presses.filter(key, true, true) { throw IllegalStateException("failed") }
            fail("expected callback failure")
        } catch (_: IllegalStateException) { }
        assertFalse(presses.hasConsumedPress(key))
        var calls = 0
        assertEquals(Action.PASS, presses.filter(key, true, true) {
            calls++
            assertEquals(Action.ZOOM_IN, presses.filter(key, true, true) { calls++; Action.ZOOM_IN })
            Action.PASS
        })
        assertEquals(Action.PASS, presses.filter(key, true, true) { calls++; Action.ZOOM_OUT })
        assertEquals(2, calls)
    }
}
