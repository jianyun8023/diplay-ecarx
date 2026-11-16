package com.shihab.diplay.vehicle

import org.junit.Assert.*
import org.junit.Test

class WheelInputPolicyTest {
    @Test fun pressDecisionsMatchFormerMapWithIndependentKeys() {
        val old = WheelPressReference()
        val tokens = mutableMapOf<Any, Long>()
        fun event(key: Any, down: Boolean, first: Boolean, decision: Boolean) {
            var calls = 0
            val expected = old.filter(key, down, first) { calls++; decision }
            val token = if (down) tokens[key] else tokens.remove(key)
            val actual = when (WheelInputPolicy.press(token, down, first)) {
                WheelInputPolicy.PressDecision.PASS -> false
                WheelInputPolicy.PressDecision.CONSUME -> true
                WheelInputPolicy.PressDecision.DECIDE -> {
                    assertEquals(1, calls)
                    tokens[key] = WheelInputPolicy.commit(decision)
                    decision
                }
            }
            assertEquals(expected, actual)
        }
        event("a", true, false, true)
        event("a", true, true, false)
        event("b", true, true, true)
        event("a", true, true, true)
        event("b", true, false, false)
        event("a", false, false, true)
        event("b", false, false, false)
    }

    @Test fun siriWindowsMatchFormerClockState() {
        val old = WheelSiriReference()
        val actual = WheelSiriState()
        assertEquals(400L, WheelInputPolicy.repeatGapMillis)
        assertEquals(180L, WheelInputPolicy.minDoublePressGapMillis)
        assertEquals(old.opens(Long.MAX_VALUE - 89), actual.opens(Long.MAX_VALUE - 89))
        assertEquals(old.doublePress(Long.MIN_VALUE + 89, 600), actual.doublePress(Long.MIN_VALUE + 89, 600))
        assertEquals(old.doublePress(Long.MIN_VALUE + 269, 600), actual.doublePress(Long.MIN_VALUE + 269, 600))
        for (now in listOf(1_000L, 1_000L, 999L, 1_179L, 1_779L, 2_380L)) {
            assertEquals(old.doublePress(now, 600), actual.doublePress(now, 600))
        }
        old.reset(); actual.reset()
        assertEquals(old.opens(50), actual.opens(50))
        assertEquals(old.opens(449), actual.opens(449))
        assertEquals(old.opens(849), actual.opens(849))
    }
}
