package com.shihab.diplay.airplay

import java.math.BigInteger
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class NtpTimingTest {
    @Test fun unixEpochPacketsAndPeerAddressPolicyAreUnchanged() {
        val clock = NtpTiming(0, 0)
        val request = clock.request(0)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 210.toByte(), 0, 7), request.copyOf(4))
        assertEquals(0x83aa7e8000000000uL.toLong(), ByteBuffer.wrap(request).getLong(24))
        val reply = clock.reply(request, 0, 1_000_000_000)
        assertArrayEquals(request.copyOfRange(24, 32), reply.copyOfRange(8, 16))
        assertEquals(1, clock.action(request, false))
        assertEquals(2, clock.action(reply, true))
        assertEquals(0, clock.action(reply, false))
        assertEquals(0, clock.action(reply.copyOf(31), true))
        assertEquals(2, clock.action(reply + byteArrayOf(1, 2), true))
    }

    @Test fun extremeClocksSignedNtpAndEraRolloverMatchTheOriginal() {
        for (wall in listOf(Long.MIN_VALUE, -1L, 0L, 1L, 1_700_000_000_123L, 2_085_978_496_000L, Long.MAX_VALUE)) {
            for (start in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)) {
                val clock = NtpTiming(start, wall)
                val original = KotlinNtpTiming(start, wall)
                for (now in listOf(Long.MIN_VALUE, start - 1, start, start + 1, Long.MAX_VALUE)) {
                    assertEquals("wall=$wall start=$start now=$now", original.synced(now), clock.synced(now))
                    assertArrayEquals(original.request(now), clock.request(now))
                    val request = clock.request(now)
                    assertArrayEquals(original.reply(request, now, now + 1), clock.reply(request, now, now + 1))
                    val response = answer(request, 5_000_000_000, 1_000_000)
                    original.sample(response, now + 1_000_000)
                    clock.sample(response, now + 1_000_000)
                    assertEquals(original.synced(now), clock.synced(now))
                }
            }
        }
    }

    @Test fun fullStepSlewWindowAndPendingHistoryMatchTheOriginal() {
        val clock = NtpTiming(0, 1_700_000_000_123)
        val original = KotlinNtpTiming(0, 1_700_000_000_123)
        val skews = listOf(5_000_000_000L, 62_500_000L, -62_500_000L, 128_000_000L,
            -128_000_000L, 128_000_001L, -300_000_000_000L, 0L)
        val roundTrips = listOf(1_000_000_000L, 2_000_000_000L, 1_000_000L, 0L, 1L)
        repeat(160) { index ->
            val now = index * 10_000_000_000L
            val expectedRequest = original.request(now)
            val request = clock.request(now)
            assertArrayEquals(expectedRequest, request)
            val rtt = roundTrips[(index / 2) % roundTrips.size]
            val response = answer(request, skews[(index / 2) % skews.size], rtt)
            original.sample(response, now + rtt)
            clock.sample(response, now + rtt)
            // A replay has consumed its pending timestamp even when RTT was rejected.
            original.sample(response, now + rtt)
            clock.sample(response, now + rtt)
            assertEquals("sample=$index", original.synced(now + rtt), clock.synced(now + rtt))
        }
    }

    @Test fun replacedNegativeAndReplayedAnswersNeverBecomeAnExtraSample() {
        val clock = NtpTiming(0, 0)
        val original = KotlinNtpTiming(0, 0)
        val stale = clock.request(0)
        original.request(0)
        val current = clock.request(1_000_000_000)
        original.request(1_000_000_000)
        val negative = answer(current, 0, 0).also { packet ->
            val bytes = ByteBuffer.wrap(packet)
            bytes.putLong(24, bytes.getLong(16) + (10L shl 32))
        }
        for (response in listOf(answer(stale, 5_000_000_000, 0), negative, answer(current, 5_000_000_000, 0))) {
            original.sample(response, 2_000_000_000)
            clock.sample(response, 2_000_000_000)
            assertEquals(original.synced(2_000_000_000), clock.synced(2_000_000_000))
        }
        assertEquals(BigInteger.valueOf(2_208_988_802).shiftLeft(32), clock.synced(2_000_000_000))
    }

    @Test fun closeRetainsTheClockAndAllowsAListenerToBeReopened() {
        val clock = NtpClock()
        try {
            val before = clock.syncedNtp()
            clock.close()
            val after = clock.syncedNtp()
            assertTrue(after >= before)
            assertTrue(after.subtract(before) < BigInteger.ONE.shiftLeft(32))
            assertTrue(clock.listen() > 0)
            clock.close()
            assertTrue(clock.listen() > 0)
        } finally { clock.close() }
    }

    private fun answer(request: ByteArray, skewNanos: Long, rttNanos: Long): ByteArray {
        val t1 = BigInteger(1, request.copyOfRange(24, 32))
        val receive = t1.add(BigInteger.valueOf(skewNanos + rttNanos / 2).shiftLeft(32)
            .divide(BigInteger.valueOf(1_000_000_000)))
        return ByteArray(32).also { packet ->
            packet[0] = 0x80.toByte(); packet[1] = 211.toByte(); packet[3] = 7
            val buffer = ByteBuffer.wrap(packet)
            buffer.putLong(8, t1.toLong()); buffer.putLong(16, receive.toLong()); buffer.putLong(24, receive.toLong())
        }
    }
}
