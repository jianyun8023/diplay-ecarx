package com.shilapi.xcertplay

/**
 * Writes the per-stream setup lines of a CarPlay session once and counts their repeats.
 *
 * Every navigation prompt opens a new audio stream, and each one logged the same SETUP, UDP buffer,
 * decoder, focus and clock lines again: about 12 lines per prompt, 28% of the v129 session log
 * (2026-11-07, 190 prompts in 85 minutes). A line is written when its content, without the
 * per-stream ports and connection IDs, has not been written yet in this session; a changed value
 * (another codec, a refused focus request, a drifting clock) is written in full. The counts of
 * what was left out follow in one summary line every [summaryIntervalMillis] and at [flush].
 *
 * Debug logs bypass this so a developer still gets every line.
 */
internal class SessionLogRepeatLimiter(
    private val now: () -> Long,
    private val summaryIntervalMillis: Long = SUMMARY_INTERVAL_MILLIS,
) {
    private class Rule(val label: String, val prefix: String, val key: (String) -> String)

    private val written = HashSet<String>()
    private val suppressed = LinkedHashMap<String, Int>()
    private var lastSummaryAt = now()

    /** Returns the lines to write, in order: possibly a due summary, then [message] unless it repeats. */
    @Synchronized
    fun filter(message: String): List<String> {
        val rule = RULES.firstOrNull { message.startsWith(it.prefix) } ?: return listOf(message)
        val key = rule.label + "|" + rule.key(message)
        if (written.add(key)) return listOf(message)
        suppressed[rule.label] = (suppressed[rule.label] ?: 0) + 1
        val summary = if (now() - lastSummaryAt >= summaryIntervalMillis) summaryLine() else null
        return listOfNotNull(summary)
    }

    /** The counts not yet reported, for the end of the session log. */
    @Synchronized
    fun flush(): String? = summaryLine()

    private fun summaryLine(): String? {
        lastSummaryAt = now()
        if (suppressed.isEmpty()) return null
        val line = "Log repeats not written (same as earlier in this session): " +
            suppressed.entries.joinToString(", ") { "${it.key}×${it.value}" }
        suppressed.clear()
        return line
    }

    internal companion object {
        const val SUMMARY_INTERVAL_MILLIS = 10 * 60_000L

        private val PER_STREAM_IDS = Regex("(dataPort|controlPort|streamConnectionID|streamID|id)=\\d+")
        private fun withoutIds(line: String) = line.replace(PER_STREAM_IDS, "$1=#")

        /** Clocks a few tenths of a percent apart are the same clock; a larger drift is written again. */
        private val MEASURED_HZ = Regex("measuredHz=(\\d+)")
        private fun rtpClockKey(line: String) = MEASURED_HZ.replace(line) {
            "measuredHz~" + Math.round(it.groupValues[1].toDouble() / 100.0) * 100
        }

        /** Playout feedback is written again when the lead changes by more than 50 ms. */
        private val FEEDBACK_VOLATILE = Regex("(id|reported|received)=-?\\d+")
        private val AHEAD = Regex("aheadMs=(-?\\d+)")
        private fun feedbackKey(line: String) = AHEAD.replace(FEEDBACK_VOLATILE.replace(line, "$1=#")) {
            "aheadMs~" + Math.floorDiv(it.groupValues[1].toInt(), 50) * 50
        }

        private val RULES = listOf(
            Rule("SETUP keys=[streams]", "airplay SETUP keys=[streams]") { it },
            Rule("SETUP response", "airplay SETUP response streams=") { withoutIds(it) },
            Rule("audio stream accepted", "airplay audio stream type=") { withoutIds(it) },
            Rule("UDP receive buffer", "Audio UDP receive buffer ") { it },
            Rule("Audio starting", "Audio: starting ") { it },
            Rule("Audio ready", "Audio: ready ") { it },
            Rule("Audio decoded format", "Audio: decoded format ") { it },
            Rule("rtp clock", "Audio: rtp clock ") { rtpClockKey(it) },
            Rule("feedback", "Audio: feedback ") { feedbackKey(it) },
            Rule("prompt focus requested", "Audio: navigation prompt focus requested ") { it },
            Rule("prompt focus released", "Audio: navigation prompt focus released ") { it },
            Rule("focus regained", "Audio: focus change=1 ") { it },
        )
    }
}
