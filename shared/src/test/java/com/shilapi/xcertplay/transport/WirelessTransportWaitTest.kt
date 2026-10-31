package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wait that ends a wireless attempt the iPhone never advanced.
 *
 * A phone that answers WirelessCarPlayUpdate with "unavailable" authenticates over Bluetooth and
 * then asks for nothing, which used to leave the screen saying it was connecting for the whole
 * five-minute control-loop budget.
 */
class WirelessTransportWaitTest {
    @Test
    fun disabledWaitNeverExpires() {
        val clock = Clock()
        val wait = WirelessTransportWait(Iap2WirelessControlClient.NO_TRANSPORT_WAIT_MILLIS, clock::now)

        clock.advanceMillis(24 * 60 * 60 * 1_000L)

        assertFalse(wait.expired(startRequestsSent = 0, transportNotified = false))
    }

    @Test
    fun expiresOnceTheWindowPassesWithoutATransportRequest() {
        val clock = Clock()
        val wait = WirelessTransportWait(60_000L, clock::now)

        clock.advanceMillis(59_999L)
        assertFalse(wait.expired(startRequestsSent = 0, transportNotified = false))

        clock.advanceMillis(1L)
        assertTrue(wait.expired(startRequestsSent = 0, transportNotified = false))
    }

    @Test
    fun progressPastTheWaitKeepsTheSessionAlive() {
        val clock = Clock()
        val wait = WirelessTransportWait(60_000L, clock::now)

        clock.advanceMillis(120_000L)

        assertFalse(wait.expired(startRequestsSent = 1, transportNotified = false))
        assertFalse(wait.expired(startRequestsSent = 0, transportNotified = true))
    }

    @Test
    fun theBlockingReadIsShortenedToTheWaitThatIsLeft() {
        val clock = Clock()
        val wait = WirelessTransportWait(60_000L, clock::now)

        // The control loop would otherwise block for up to five minutes in one read.
        assertEquals(60_000L, wait.boundPollTimeout(300_000L, 0, false))

        clock.advanceMillis(45_000L)
        assertEquals(15_000L, wait.boundPollTimeout(300_000L, 0, false))
        assertTrue(wait.boundPollTimeout(300_000L, 0, false) <= 300_000L)

        clock.advanceMillis(15_000L)
        assertEquals(1L, wait.boundPollTimeout(300_000L, 0, false))
    }

    @Test
    fun progressAndADisabledWaitKeepTheReadAsItWas() {
        val clock = Clock()
        val enabled = WirelessTransportWait(60_000L, clock::now)

        assertEquals(300_000L, enabled.boundPollTimeout(300_000L, 1, false))
        assertEquals(300_000L, enabled.boundPollTimeout(300_000L, 0, true))

        val disabled = WirelessTransportWait(Iap2WirelessControlClient.NO_TRANSPORT_WAIT_MILLIS, clock::now)
        clock.advanceMillis(600_000L)
        assertEquals(300_000L, disabled.boundPollTimeout(300_000L, 0, false))
    }

    private class Clock {
        private var nanos = 1_000_000_000L
        fun now(): Long = nanos
        fun advanceMillis(millis: Long) {
            nanos += millis * 1_000_000L
        }
    }
}
