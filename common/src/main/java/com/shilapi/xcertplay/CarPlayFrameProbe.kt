package com.shilapi.xcertplay

import java.util.Locale

/**
 * Debug evidence of where the iPhone really draws on the main canvas. Screen colour and bit rate do not
 * tell a black area from a still picture; the drawn box does. Lines use no hex digits or letters, so
 * report scrubbing never removes them.
 */
internal object CarPlayFrameProbe {
    const val WIDTH = 64
    const val HEIGHT = 36
    private const val DRAWN_LUMA = 16
    private const val SHADES = " .:-=+*#%@"

    private fun luma(pixel: Int): Int =
        (((pixel shr 16) and 0xff) * 299 + ((pixel shr 8) and 0xff) * 587 + (pixel and 0xff) * 114) / 1000

    /** `drawn=…% box=x0,y0..x1,y1 mean=…`, box in percent of the canvas; `box=none` when all is black. */
    fun summary(pixels: IntArray, width: Int, height: Int): String {
        require(width > 0 && height > 0 && pixels.size == width * height)
        var drawn = 0
        var total = 0L
        var left = width; var top = height; var right = -1; var bottom = -1
        for (y in 0 until height) for (x in 0 until width) {
            val value = luma(pixels[y * width + x])
            total += value
            if (value > DRAWN_LUMA) {
                drawn++
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        val box = if (right < 0) "none" else
            "${left * 100 / width},${top * 100 / height}..${(right + 1) * 100 / width},${(bottom + 1) * 100 / height}"
        return String.format(Locale.ROOT, "drawn=%d%% box=%s mean=%d", drawn * 100 / pixels.size, box,
            total / pixels.size)
    }

    /** Brightness map at half the probe size, one row per line, darkest as a space between bars. */
    fun map(pixels: IntArray, width: Int, height: Int): List<String> {
        require(width > 1 && height > 1 && pixels.size == width * height)
        return (0 until height / 2).map { row ->
            buildString {
                append('|')
                for (column in 0 until width / 2) {
                    var sum = 0
                    for (dy in 0..1) for (dx in 0..1) sum += luma(pixels[(row * 2 + dy) * width + column * 2 + dx])
                    append(SHADES[(sum / 4) * SHADES.length / 256])
                }
                append('|')
            }
        }
    }
}
