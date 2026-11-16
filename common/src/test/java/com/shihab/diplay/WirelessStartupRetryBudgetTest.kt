package com.shihab.diplay

import org.junit.Assert.*
import org.junit.Test
import com.shihab.diplay.orchestration.HostRetryReference

class WirelessStartupRetryBudgetTest {
    @Test fun nativeStateMatchesFormerHostRulesAcrossMixedEvents() {
        val actual = WirelessStartupRetryBudget()
        val old = HostRetryReference()
        assertEquals(60_000L, actual.stableSessionMillis)
        for ((startup, tunnel) in listOf(true to false, false to true, false to false,
            true to false, true to false, true to false, true to false, true to false)) {
            assertEquals(old.schedule(startup, tunnel), actual.schedule(startup, tunnel))
            assertEquals(old.attempts, actual.attempts)
            assertEquals(old.retries, actual.retries)
        }
        val session = Any()
        assertEquals(old.firstFrame(session, Long.MAX_VALUE - 30_000), actual.firstFrame(session, Long.MAX_VALUE - 30_000))
        assertEquals(old.resetIfStable(session, Long.MIN_VALUE + 29_999), actual.resetIfStable(session, Long.MIN_VALUE + 29_999))
        assertEquals(old.resetIfStable(session, Long.MIN_VALUE + 30_000), actual.resetIfStable(session, Long.MIN_VALUE + 30_000))
        assertEquals(old.resetIfStable(session, Long.MIN_VALUE + 30_000), actual.resetIfStable(session, Long.MIN_VALUE + 30_000))
        assertEquals(old.retries, actual.retries)
        old.resetAttempts(); actual.sessionActive()
        old.resetStartup(); actual.manualRetry()
        assertEquals(old.attempts, actual.attempts)
        assertEquals(old.retries, actual.retries)
    }
    @Test fun stopsAfterFiveRetriesWithExistingBackoff() {
        val budget = WirelessStartupRetryBudget()
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L), List(5) { budget.schedule(startup = true) })
        assertNull(budget.schedule(startup = true))
        assertEquals(5, budget.retries)
        budget.manualRetry()
        assertEquals(2_000L, budget.schedule(startup = true))
    }

    @Test fun onlyContinuousVideoSessionResetsBudget() {
        val budget = WirelessStartupRetryBudget()
        budget.schedule(startup = true)
        val one = Any()
        assertFalse(budget.resetIfStable(one, 120_000))
        assertTrue(budget.firstFrame(one, 100))
        assertFalse(budget.firstFrame(one, 200))
        assertFalse(budget.resetIfStable(one, 60_099))
        budget.disconnected()
        assertFalse(budget.resetIfStable(one, 60_100))
        val two = Any()
        assertTrue(budget.firstFrame(two, 70_000))
        assertFalse(budget.resetIfStable(one, 130_000))
        assertTrue(budget.resetIfStable(two, 130_000))
        assertEquals(0, budget.retries)
    }
}
