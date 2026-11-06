package com.shilapi.xcertplay.iap2.session

import com.shilapi.xcertplay.iap2.trace.Iap2TraceDirection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class Iap2FrameTraceLimiterTest {
    private val rx = Iap2TraceDirection.RX
    private val tx = Iap2TraceDirection.TX

    @Test fun budgetsAreIndependentForTxAndRxAndEndpoints() {
        val limiter = Iap2FrameTraceLimiter()
        repeat(3) {
            assertEquals(0L, limiter.select(rx, 0xfffb, it.toLong(), false)!!.suppressed)
        }
        assertNull(limiter.select(rx, 0xfffb, 4, false))
        assertNotNull(limiter.select(tx, 0xfffb, 4, false))
        assertNotNull(limiter.select(rx, 0x5201, 4, false))
        assertNull(limiter.select(rx, 0xfffb, 5, false))
        val heartbeat = limiter.select(rx, 0xfffb, 60_000_000_002L, false)!!
        assertFalse(heartbeat.detail)
        assertEquals(2L, heartbeat.suppressed)
        assertNull(limiter.select(rx, 0xfffb, 60_000_000_003L, false))
    }

    @Test fun debugBypassesThrottlingAndDoesNotCountAlreadyTracedFramesAsSuppressed() {
        val limiter = Iap2FrameTraceLimiter()
        repeat(3) { limiter.select(rx, 0xfffb, it.toLong(), false) }
        repeat(9) { assertNull(limiter.select(rx, 0xfffb, 4L, false)) }
        repeat(10) {
            val decision = limiter.select(rx, 0xfffb, 5L, true)!!
            assertTrue(decision.detail)
            assertEquals(0L, decision.suppressed)
        }
        assertNull(limiter.select(rx, 0xfffb, 6L, false))
        val heartbeat = limiter.select(rx, 0xfffb, 60_000_000_006L, false)!!
        assertEquals(1L, heartbeat.suppressed)
    }

    @Test fun concurrentSuppressionCannotLoseCountsOnHeartbeat() {
        val limiter = Iap2FrameTraceLimiter()
        repeat(3) { limiter.select(rx, 0x5201, 0, false) }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..500).map {
                pool.submit<Boolean> {
                    start.await()
                    limiter.select(rx, 0x5201, 1, false) == null
                }
            }
            start.countDown()
            assertTrue(tasks.all { it.get(5, TimeUnit.SECONDS) })
            assertEquals(500L, limiter.select(rx, 0x5201, 60_000_000_001L, false)!!.suppressed)
        } finally {
            pool.shutdownNow()
        }
    }
}
