package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CarPlayFrameProbeTest {
    private val width = CarPlayFrameProbe.WIDTH
    private val height = CarPlayFrameProbe.HEIGHT

    // The theme case from the car: an 8:3 picture drawn at the top of a 16:9 canvas, the rest black.
    private fun topTwoThirds() = IntArray(width * height) { index -> if (index / width < 24) 0xffc0c0c0.toInt() else 0xff000000.toInt() }

    @Test fun reportsTheDrawnBoxInPercentOfTheCanvas() {
        assertEquals("drawn=66% box=0,0..100,66 mean=128", CarPlayFrameProbe.summary(topTwoThirds(), width, height))
        assertEquals("drawn=100% box=0,0..100,100 mean=255",
            CarPlayFrameProbe.summary(IntArray(width * height) { 0xffffffff.toInt() }, width, height))
    }

    @Test fun anAllBlackFrameHasNoBox() {
        assertEquals("drawn=0% box=none mean=0",
            CarPlayFrameProbe.summary(IntArray(width * height) { 0xff000000.toInt() }, width, height))
    }

    @Test fun theMapKeepsAllRowsThroughReportScrubbing() {
        val line = "20:42:20.000  " + CarPlayFrameProbe.mapLine(topTwoThirds(), width, height)
        assertEquals(line, DiagnosticRedactor.redact(line))
        val rows = line.substringAfter("Frame map main 32x18 ").split('/')
        assertEquals(height / 2, rows.size)
        assertEquals(width / 2 + 2, rows.first().length)
        assertEquals("|" + " ".repeat(width / 2) + "|", rows.last())
        assertFalse(rows.first().trim('|').isBlank())
    }
}
