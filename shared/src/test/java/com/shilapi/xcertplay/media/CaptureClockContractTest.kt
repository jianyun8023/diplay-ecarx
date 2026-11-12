package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.media.reference.KotlinCaptureClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CaptureClockContractTest {
    @Test fun nativeClockPreservesTimestampFallbackAndCaptureWindowCalibration() {
        for (rate in listOf(8_000, 16_000, 24_000, 48_000)) {
            val actual = CaptureClock(rate)
            val expected = KotlinCaptureClock(rate)
            var read = 0L
            repeat(200) { step ->
                val samples = rate / 100 + step % 5
                read += samples
                val now = 1_000_000_000L + step * 10_000_000L
                val stamped = step % 7 != 0
                val timestamp = if (step % 13 == 0) 0L else now - 30_000_000L
                val position = read + (step % 9 - 4) * rate / 1000
                expected.read(samples, now, stamped, timestamp, position)
                actual.read(samples, now, stamped, timestamp, position)
                for (into in listOf(0, samples / 2, samples, samples + 10)) {
                    assertEquals("rate=$rate step=$step into=$into", expected.frameEndNs(into), actual.frameEndNs(into))
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { CaptureClock(0) }
    }
}
