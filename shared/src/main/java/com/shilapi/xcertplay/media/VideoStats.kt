package com.shilapi.xcertplay.media

import android.util.Log

/** Five-second video counters that separate network/iPhone gaps from decoder throughput. */
internal class VideoStats(
    private val label: String = "",
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var windowStartNs = nanoTime()
    private var lastArrivalNs = 0L
    /** When the last frame reached the screen; 0 until the first one is rendered. */
    private var lastRenderedNs = 0L
    private var received = 0
    private var rendered = 0
    private var recoveries = 0
    private var bytes = 0L
    private var maxArrivalGapNs = 0L
    private var idleGaps = 0
    private var maxIdleGapNs = 0L
    private var heldDecodeSamples = 0
    private var maxHeldDecodeNs = 0L
    private var touchSamples = 0
    private var touchLatencySumNs = 0L
    private var maxTouchLatencyNs = 0L
    private val decodeMicros = IntArray(1024)
    private var decodeSamples = 0
    private var late = 0
    private var pacingDelayNanos = 0L

    @Synchronized fun onReceived(size: Int) {
        val now = nanoTime()
        val gap = now - lastArrivalNs
        if (lastArrivalNs != 0L && gap < IDLE_GAP_NS) maxArrivalGapNs = maxOf(maxArrivalGapNs, gap)
        if (lastArrivalNs != 0L && gap >= IDLE_GAP_NS) {
            idleGaps++
            maxIdleGapNs = maxOf(maxIdleGapNs, gap)
        }
        lastArrivalNs = now
        // Touches only change the main screen; a second stream must not consume their samples.
        val touchLatency = if (label.isEmpty()) TouchLatencyProbe.onFrame(now) else -1L
        if (touchLatency >= 0) {
            touchSamples++
            touchLatencySumNs += touchLatency
            maxTouchLatencyNs = maxOf(maxTouchLatencyNs, touchLatency)
        }
        received++
        bytes += size
    }

    @Synchronized fun onRendered() {
        rendered++
        lastRenderedNs = nanoTime()
    }

    @Synchronized fun onRecovery() { recoveries++ }

    /** Time from queueing a frame into the decoder to dequeueing its output. */
    @Synchronized fun onDecodeLatency(ns: Long, heldOverGap: Boolean = false) {
        // Preserve throughput percentiles, but expose the tail frames they deliberately exclude.
        if (heldOverGap) {
            heldDecodeSamples++
            maxHeldDecodeNs = maxOf(maxHeldDecodeNs, ns)
            return
        }
        if (decodeSamples < decodeMicros.size) decodeMicros[decodeSamples++] = (ns / 1000).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /** A paced frame left the decoder after its display time and was shown at once. */
    @Synchronized fun onLate() { late++ }

    /** Smooth video's current display delay ([PacingDelay]). */
    @Synchronized fun onPacingDelay(ns: Long) { pacingDelayNanos = ns }

    private fun decodeSummary(): String {
        if (decodeSamples == 0) return ""
        val sorted = decodeMicros.copyOf(decodeSamples).also { it.sort() }
        fun percentileMs(p: Int) = sorted[(decodeSamples - 1) * p / 100] / 1000
        val delay = if (pacingDelayNanos > 0) " delay=%dms".format(pacingDelayNanos / 1_000_000) else ""
        return " decode p50=%dms p90=%dms late=%d".format(percentileMs(50), percentileMs(90), late) + delay
    }

    @Synchronized fun logIfDue(verbose: Boolean = true): String? {
        val now = nanoTime()
        val elapsedNs = now - windowStartNs
        // Debug logging keeps the 5 s window. Production gets one compact line a minute: without it
        // the report can see packets arrive but never what reached the screen.
        if (elapsedNs < if (verbose) WINDOW_NS else SUMMARY_WINDOW_NS) return null
        val seconds = elapsedNs / 1e9
        val idle = received == 0 && rendered == 0 && heldDecodeSamples == 0 && touchSamples == 0
        // A stream that already reached the screen and then went quiet is the interesting case: keep
        // one line a minute so a frozen picture is visible instead of silence.
        if (idle && (verbose || lastRenderedNs == 0L)) { windowStartNs = now; return null }
        val sinceShown = if (lastRenderedNs == 0L) "none" else ((now - lastRenderedNs) / 1_000_000).toString()
        val line = if (verbose) {
            val touchAvgMs = if (touchSamples == 0) -1 else touchLatencySumNs / touchSamples / 1_000_000
            ("video stats$label rx=%.1ffps shown=%.1ffps maxGap=%dms kbps=%d recoveries=%d " +
                "touch2frame avg=%dms max=%dms n=%d touchSendMax=%dms").format(
                received / seconds, rendered / seconds, maxArrivalGapNs / 1_000_000,
                (bytes * 8 / 1000 / seconds).toLong(), recoveries,
                touchAvgMs, maxTouchLatencyNs / 1_000_000, touchSamples, TouchLatencyProbe.maxSendNs / 1_000_000,
            ) + decodeSummary() + " idleGaps=$idleGaps idleGapMax=${maxIdleGapNs / 1_000_000}ms" +
                " heldDecodeN=$heldDecodeSamples heldDecodeMax=${maxHeldDecodeNs / 1_000_000}ms monotonicNs=$now"
        } else {
            ("video stats$label rx=%.1ffps shown=%.1ffps maxGap=%dms kbps=%d recoveries=%d sinceShownMs=$sinceShown").format(
                received / seconds, rendered / seconds, maxArrivalGapNs / 1_000_000,
                (bytes * 8 / 1000 / seconds).toLong(), recoveries,
            ) + decodeSummary() + " idleGaps=$idleGaps idleGapMax=${maxIdleGapNs / 1_000_000}ms monotonicNs=$now"
        }
        TouchLatencyProbe.maxSendNs = 0
        Log.i(TAG, line)
        windowStartNs = now
        received = 0; rendered = 0; recoveries = 0; bytes = 0; maxArrivalGapNs = 0
        touchSamples = 0; touchLatencySumNs = 0; maxTouchLatencyNs = 0
        decodeSamples = 0; late = 0
        idleGaps = 0; maxIdleGapNs = 0; heldDecodeSamples = 0; maxHeldDecodeNs = 0
        return line
    }

    private companion object {
        const val TAG = "DiPlay-VideoStats"
        const val WINDOW_NS = 5_000_000_000L
        const val SUMMARY_WINDOW_NS = 60_000_000_000L // production: one compact line a minute
        const val IDLE_GAP_NS = 2_000_000_000L // report separately: static content can also cause long gaps
    }
}
