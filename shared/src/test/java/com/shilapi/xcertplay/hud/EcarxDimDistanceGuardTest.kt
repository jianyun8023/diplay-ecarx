package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payload parity with the DIM service and the correction policy, without opening a socket. */
class EcarxDimDistanceGuardTest {
    @Test fun convertDistanceMatchesTheServiceJsonHelper() {
        assertEquals("0 m", EcarxDimDistanceGuard.convertDistance(0))
        assertEquals("350 m", EcarxDimDistanceGuard.convertDistance(350))
        assertEquals("999 m", EcarxDimDistanceGuard.convertDistance(999))
        assertEquals("1 km", EcarxDimDistanceGuard.convertDistance(1000))
        assertEquals("1.2 km", EcarxDimDistanceGuard.convertDistance(1200))
        assertEquals("2.3 km", EcarxDimDistanceGuard.convertDistance(2345))
        assertEquals("9.9 km", EcarxDimDistanceGuard.convertDistance(9876))
        assertEquals("10 km", EcarxDimDistanceGuard.convertDistance(10000))
        assertEquals("12 km", EcarxDimDistanceGuard.convertDistance(12345))
    }

    @Test fun nextTurnPayloadUsesDistanceThenAddress() {
        assertEquals(
            "{\"distance\":\"350 m\",\"address\":\"滨海路\"}",
            EcarxDimDistanceGuard.turnByTurnPayload(350, "滨海路"),
        )
    }

    @Test fun nextTurnPayloadClampsAddressTo32CharactersAndEscapesQuotes() {
        assertEquals("{\"distance\":\"1.2 km\",\"address\":\"${"a".repeat(32)}\"}",
            EcarxDimDistanceGuard.turnByTurnPayload(1200, "a".repeat(40)))
        assertEquals("{\"distance\":\"5 m\",\"address\":\"a\\\"b\"}",
            EcarxDimDistanceGuard.turnByTurnPayload(5, "a\"b"))
    }

    @Test fun distancePayloadIsJustTheFormattedDistance() {
        assertEquals("{\"distance\":\"9.9 km\"}", EcarxDimDistanceGuard.distancePayload(9876))
    }

    @Test fun correctOnlyWhenForeignAndPastTheRateLimit() {
        // our own echo / already correct -> never correct
        assertFalse(EcarxDimDistanceGuard.shouldCorrect("350 m", "350 m", nowMs = 1_000, lastCorrectionMs = 0))
        // no desired value -> nothing to assert
        assertFalse(EcarxDimDistanceGuard.shouldCorrect(null, "1 m", nowMs = 1_000, lastCorrectionMs = 0))
        // foreign value but inside the 120 ms window -> throttled
        assertFalse(EcarxDimDistanceGuard.shouldCorrect("350 m", "1 m", nowMs = 100, lastCorrectionMs = 0))
        // foreign value, window elapsed -> correct
        assertTrue(EcarxDimDistanceGuard.shouldCorrect("350 m", "1 m", nowMs = 200, lastCorrectionMs = 0))
    }
}
