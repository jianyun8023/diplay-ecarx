package com.shihab.diplay.airplay

import java.math.BigInteger
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NativeNtpTimingDeviceTest {
    @Before fun nativeIsLoaded() { assertTrue(NativeMedia.loadError.toString(), NativeMedia.available) }

    @Test fun packetsAndTwoWayClockUpdatesUseTheNativeOwner() {
        val token = NativeNtpTiming.create(0, 0)
        repeat(2) { index ->
            val now = index * 10_000_000_000L
            val request = NativeNtpTiming.request(token, now)
            val t1 = ByteBuffer.wrap(request).getLong(24)
            val response = ByteArray(32).also {
                it[0] = 0x80.toByte(); it[1] = 211.toByte(); it[3] = 7
                ByteBuffer.wrap(it).putLong(8, t1).putLong(16, t1 + (5L shl 32) + (1L shl 31))
                    .putLong(24, t1 + (5L shl 32) + (1L shl 31))
            }
            assertEquals(0, NativeNtpTiming.action(response, false))
            assertEquals(2, NativeNtpTiming.action(response, true))
            NativeNtpTiming.sample(token, response, now + 1_000_000_000)
        }
        assertEquals(BigInteger.valueOf(2_208_988_805).shiftLeft(32), BigInteger(NativeNtpTiming.synced(token, 0)))
        val request = NativeNtpTiming.request(token, 0)
        val reply = NativeNtpTiming.reply(token, request, 0, 1_000_000_000)
        assertEquals(1, NativeNtpTiming.action(request, false))
        assertArrayEquals(request.copyOfRange(24, 32), reply.copyOfRange(8, 16))
        assertEquals(0, NativeNtpTiming.action(reply.copyOf(31), true))
    }

    @Test fun clocksRetainIndependentFullWidthSignedValues() {
        val first = NativeNtpTiming.create(Long.MIN_VALUE, 0)
        val second = NativeNtpTiming.create(Long.MAX_VALUE, 1_000)
        val firstTime = BigInteger(NativeNtpTiming.synced(first, Long.MIN_VALUE))
        val secondTime = BigInteger(NativeNtpTiming.synced(second, Long.MAX_VALUE))
        assertEquals(BigInteger.valueOf(2_208_988_800).shiftLeft(32), firstTime)
        assertEquals(BigInteger.valueOf(2_208_988_801).shiftLeft(32), secondTime)
        NativeNtpTiming.request(first, 0)
        assertEquals(secondTime, BigInteger(NativeNtpTiming.synced(second, Long.MAX_VALUE)))
    }

    @Test fun malformedTokensThrowWithoutPoisoningTheNextJniCall() {
        val valid = NativeNtpTiming.create(0, 0)
        val packet = NativeNtpTiming.request(valid, 0)
        for (size in listOf(0, 1, 16, 18)) {
            val invalid = LongArray(size)
            assertThrows(IllegalArgumentException::class.java) { NativeNtpTiming.synced(invalid, 0) }
            assertThrows(IllegalArgumentException::class.java) { NativeNtpTiming.request(invalid, 0) }
            assertThrows(IllegalArgumentException::class.java) { NativeNtpTiming.reply(invalid, packet, 0, 0) }
            assertThrows(IllegalArgumentException::class.java) { NativeNtpTiming.sample(invalid, packet, 0) }
        }
        assertEquals(32, NativeNtpTiming.request(valid, 0).size)
    }
}
