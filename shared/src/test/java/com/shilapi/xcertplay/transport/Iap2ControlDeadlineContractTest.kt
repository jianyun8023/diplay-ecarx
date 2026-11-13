package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

/** Preserve the existing JVM Long arithmetic, including unusual timeout inputs. */
class Iap2ControlDeadlineContractTest {
    @Test fun positiveFractionalMillisecondsRoundUpUntilTheExactDeadline() {
        var now = 0L
        val deadline = Iap2ControlDeadline(3) { now }
        now = 1; assertEquals(3L, deadline.remainingMillis())
        now = 1_000_001; assertEquals(2L, deadline.remainingMillis())
        now = 2_999_999; assertEquals(1L, deadline.remainingMillis())
        now = 3_000_000; assertEquals(0L, deadline.remainingMillis())
    }

    @Test fun aNegativeClockAndRollbackKeepTheElapsedClampAtZero() {
        var now = -2_000_000L
        val deadline = Iap2ControlDeadline(4) { now }
        now = -1_000_000; assertEquals(3L, deadline.remainingMillis())
        now = -3_000_000; assertEquals(4L, deadline.remainingMillis())
        now = -2_000_001; assertEquals(4L, deadline.remainingMillis())
    }

    @Test fun monotonicLongRolloverUsesWrappingSubtraction() {
        var now = Long.MAX_VALUE - 500_000
        val deadline = Iap2ControlDeadline(1) { now }
        now = Long.MIN_VALUE + 499_998; assertEquals(1L, deadline.remainingMillis())
        now++; assertEquals(0L, deadline.remainingMillis())
        // A difference that wraps negative is treated as a clock rollback.
        now = -1
        val hugeJump = Iap2ControlDeadline(100) { now }
        now = Long.MAX_VALUE; assertEquals(100L, hugeJump.remainingMillis())
    }

    @Test fun finiteAuthenticationDoesNotReadTheClockOrResetTheWindow() {
        var now = 10L
        var reads = 0
        val deadline = Iap2ControlDeadline(100) { reads++; now }
        assertEquals(1, reads)
        now += 49_000_001
        deadline.authenticated(); assertEquals(1, reads)
        assertEquals(51L, deadline.remainingMillis())
        now = 100_000_009; assertEquals(1L, deadline.remainingMillis())
        now++; assertEquals(0L, deadline.remainingMillis())
        deadline.authenticated(); assertEquals(0L, deadline.remainingMillis())
    }

    @Test fun unlimitedAuthenticationStopsReadingTheClockEvenAfterExpiry() {
        var now = 0L
        var reads = 0
        var forbidReads = false
        val deadline = Iap2ControlDeadline(Long.MAX_VALUE) { check(!forbidReads); reads++; now }
        now = 60_000_000_000L; assertEquals(0L, deadline.remainingMillis())
        assertEquals(2, reads)
        deadline.authenticated(); forbidReads = true
        now = Long.MIN_VALUE; assertEquals(30_000L, deadline.remainingMillis())
        deadline.authenticated()
        now = Long.MAX_VALUE; assertEquals(30_000L, deadline.remainingMillis())
        assertEquals(2, reads)
    }

    @Test fun negativeTimeoutsRetainMultiplicationAndRemainingSubtractionWrap() {
        var now = 0L
        val wrappedPositive = Iap2ControlDeadline(Long.MIN_VALUE + 1) { now }
        assertEquals(1L, wrappedPositive.remainingMillis())
        now = 1_000_000; assertEquals(0L, wrappedPositive.remainingMillis())
        now = 0
        val negative = Iap2ControlDeadline(-2) { now }
        assertEquals(0L, negative.remainingMillis())
        now = Long.MAX_VALUE; assertEquals(30_000L, negative.remainingMillis())
    }

    @Test fun overflowingFiniteBudgetsPreserveMultiplicationAndCeilingAdditionWrap() {
        val multiplyOverflow = Iap2ControlDeadline(Long.MAX_VALUE - 1) { 0L }
        assertEquals(0L, multiplyOverflow.remainingMillis())
        val ceilingOverflow = Iap2ControlDeadline(9_223_372_036_854L) { 0L }
        assertEquals(-9_223_372_036_854L, ceilingOverflow.remainingMillis())
    }
}
