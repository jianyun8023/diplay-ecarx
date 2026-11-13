package com.shihab.diplay.airplay

/**
 * Measures the RTP clock the iPhone uses on one audio stream from its own downlink packets, so a
 * report shows which clock a microphone uplink on that stream must count in.
 */
internal class RtpClockProbe(
    private val windowNs: Long = 3_000_000_000L,
    /**
     * Packets of a stream's first moments are skipped: they arrive at an uneven pace (v146 measured a
     * 48 kHz music stream at 40 kHz and a 24 kHz Siri stream at 26.6 kHz over its first 2 s).
     */
    private val warmupNs: Long = 1_000_000_000L,
) {
    private var streamStartNs = 0L
    private var firstSample = 0L
    private var firstNs = 0L
    private var seen = false
    private var started = false
    private var done = false

    /** Returns the measured clock in Hz once, after [warmupNs] and then [windowNs] of packets; null otherwise. */
    @Synchronized
    fun observe(sample: Int, nowNs: Long): Int? {
        if (done) return null
        val unsigned = sample.toLong() and 0xffff_ffffL
        if (!seen) {
            seen = true
            streamStartNs = nowNs
        }
        if (!started) {
            if (nowNs - streamStartNs < warmupNs) return null
            started = true
            firstSample = unsigned
            firstNs = nowNs
            return null
        }
        val elapsedNs = nowNs - firstNs
        if (elapsedNs < windowNs) return null
        done = true
        val ticks = (unsigned - firstSample) and 0xffff_ffffL
        return Math.round(ticks * 1_000_000_000.0 / elapsedNs).toInt()
    }
}
