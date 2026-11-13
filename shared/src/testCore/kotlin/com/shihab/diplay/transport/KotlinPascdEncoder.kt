@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.shihab.diplay.transport

import java.util.Locale

internal object KotlinPascdEncoder {
    /** Original byte encoder, compiled only into tests. */
    fun encode(reading: VehicleSpeedReading): String? {
        val samples = reading.samples.takeIf { it.isNotEmpty() } ?: return null
        val start = samples.first().elapsedMillis
        val body = buildString {
            append("PASCD,").append(format("%.3f", start / 1000.0)).append(",C,")
            append(reading.gear.letter).append(",0,").append(samples.size)
            for (sample in samples) {
                append(',').append(format("%.2f", (sample.elapsedMillis - start) / 1000.0))
                append(',').append(format("%.3f", sample.metersPerSecond.coerceAtLeast(0.0)))
            }
        }
        var checksum = 0
        for (character in body) checksum = checksum xor character.code
        return "$$body*${format("%02X", checksum)}\r\n"
    }

    private fun format(format: String, vararg arguments: Any): String = String.format(Locale.US, format, *arguments)
}
