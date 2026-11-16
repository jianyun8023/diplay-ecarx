package com.shihab.diplay.media

import org.junit.Assert.*
import org.junit.Test

class AutoPlayRecoveryTest {
    @Test fun nativeActionsMatchFormerHostRules() {
        val actual = AutoPlayRecovery()
        val old = AutoPlayRecoveryReference()
        assertEquals(3_000L, actual.initialDelayMillis)
        assertEquals(2_000L, actual.retryDelayMillis)
        assertEquals(5, actual.maxChecks)
        for (same in listOf(false, true)) assertEquals(old.duplicate(same), actual.duplicate(same))
        for (on in listOf(true, true, false, true, true, true, true, false)) {
            assertEquals(old.check(on), actual.check(on))
            assertEquals(old.checks, actual.checks)
        }
        old.reset(); actual.reset()
        assertEquals(old.checks, actual.checks)
        assertEquals(old.check(true), actual.check(true))
    }
}
