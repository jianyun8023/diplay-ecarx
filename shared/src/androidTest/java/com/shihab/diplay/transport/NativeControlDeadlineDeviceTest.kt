package com.shihab.diplay.transport

import com.shihab.diplay.airplay.NativeMedia
import org.junit.Assert.*
import org.junit.Test

class NativeControlDeadlineDeviceTest {
    @Test fun nativeOwnerMatchesTheOriginalDeadlineAcrossEveryLongBoundaryAndAuthentication() {
        assertTrue(NativeMedia.available)
        val limits = listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L, 100L, 60_000L,
            86_400_000L, Long.MAX_VALUE / 1_000_000, Long.MAX_VALUE / 1_000_000 + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        for (start in listOf(Long.MIN_VALUE, -1_000_000_000L, 0L, Long.MAX_VALUE - 10)) {
            for (timeout in limits) {
                var now = start
                val actual = Iap2ControlDeadline(timeout) { now }
                val expected = KotlinIap2ControlDeadline(timeout) { now }
                val times = listOf(start, start - 1, start + 1, start + 99_999_999L,
                    start + 100_000_000L, start + 59_999_999_999L, start + 60_000_000_000L,
                    start + 25 * 60 * 60 * 1_000_000_000L, Long.MIN_VALUE, Long.MAX_VALUE)
                for (authenticated in listOf(false, true)) {
                    if (authenticated) { actual.authenticated(); expected.authenticated() }
                    for (at in times) {
                        now = at
                        assertEquals("timeout=$timeout start=$start now=$now authenticated=$authenticated",
                            expected.remainingMillis(), actual.remainingMillis())
                    }
                }
            }
        }
    }

    @Test fun unlimitedAuthenticationSkipsTheClockAndClockReentryKeepsTheOriginalSnapshot() {
        assertTrue(NativeMedia.available)
        var samples = 0
        val drive = Iap2ControlDeadline(Long.MAX_VALUE) { samples++; 0 }
        assertEquals(1, samples)
        drive.authenticated(); drive.authenticated()
        repeat(3) { assertEquals(30_000L, drive.remainingMillis()) }
        assertEquals(1, samples)
        var now = 0L
        var authenticate = false
        lateinit var reentrant: Iap2ControlDeadline
        reentrant = Iap2ControlDeadline(Long.MAX_VALUE) {
            if (authenticate) reentrant.authenticated()
            now
        }
        now = 60_000_000_000L
        authenticate = true
        assertEquals(0L, reentrant.remainingMillis())
        assertEquals(30_000L, reentrant.remainingMillis())
    }

    @Test fun nativeTokensAreImmutableIndependentValuesAndMalformedShapesThrowWithoutPoisoningJni() {
        assertTrue(NativeMedia.available)
        val original = NativeControlDeadline.create(Long.MAX_VALUE, Long.MIN_VALUE)
        val before = original.copyOf()
        val authenticated = NativeControlDeadline.authenticated(original)
        assertArrayEquals(before, original)
        assertTrue(NativeControlDeadline.needsClock(original))
        assertFalse(NativeControlDeadline.needsClock(authenticated))
        assertEquals(0L, NativeControlDeadline.remainingMillis(original, Long.MIN_VALUE + 60_000_000_000L))
        assertEquals(30_000L, NativeControlDeadline.remainingMillis(authenticated, Long.MAX_VALUE))
        val finite = NativeControlDeadline.create(100, 0)
        NativeControlDeadline.authenticated(finite)
        assertEquals(0L, NativeControlDeadline.remainingMillis(finite, 100_000_000))
        for (invalid in listOf(longArrayOf(1), longArrayOf(1, 2, 3))) {
            assertThrows(IllegalArgumentException::class.java) { NativeControlDeadline.needsClock(invalid) }
            assertThrows(IllegalArgumentException::class.java) { NativeControlDeadline.authenticated(invalid) }
            assertThrows(IllegalArgumentException::class.java) { NativeControlDeadline.remainingMillis(invalid, 0) }
            assertEquals(100L, NativeControlDeadline.remainingMillis(finite, 0))
        }
    }
}
