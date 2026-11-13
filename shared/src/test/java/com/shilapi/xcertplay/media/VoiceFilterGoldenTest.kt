package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** The same vector as `pcm.rs`'s golden test, so the Kotlin and Rust call filters are held to one output. */
class VoiceFilterGoldenTest {
    @Test
    fun theHighPassMatchesTheGoldenVector() {
        val input = shortArrayOf(-3000, -942, 1101, 3115, 5085, 7000, 8845, 3608, 5281, 6852, 8314, 9662, 10889, 11993, 5972, 6827, 7559, 8173, 8673, 9067, 9363, 2571, 2702, 2767, 2781, 2755, 2706, 2646, -4407, -4442, -4444, -4397, -4290, -4109, -3843, -10483, -10019, -9443, -8751, -7937)
        val expected = shortArrayOf(-2707, -294, 1665, 3207, 4361, 5164, 5647, -475, 763, 1644, 2209, 2498, 2547, 2395, -4240, -3395, -2786, -2374, -2124, -2001, -1976, -8336, -7126, -6073, -5145, -4321, -3575, -2892, -8568, -6664, -4917, -3308, -1826, -458, 802, -4353, -1990, 146, 2064, 3775)
        val pcm = ByteArray(input.size * 2)
        input.forEachIndexed { i, s -> pcm[2 * i] = s.toByte(); pcm[2 * i + 1] = (s.toInt() shr 8).toByte() }
        VoiceFilter(16_000, 1).process(pcm, 0, pcm.size)
        val output = ShortArray(input.size) { i -> ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xff)).toShort() }
        assertArrayEquals(expected, output)
    }
}
