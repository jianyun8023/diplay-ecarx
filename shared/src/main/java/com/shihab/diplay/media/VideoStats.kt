package com.shihab.diplay.media

import android.util.Log

/** Five-second video counters that separate network/iPhone gaps from decoder throughput. */
internal class VideoStats(
    private val label: String = "",
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var windowStartNs = nanoTime()
    private var lastArrivalNs = 0L
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

    @Synchronized fun onRendered() { rendered++ }

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

    /** The counters a native decode loop kept over the window ([NativeVideoPipe.drainStats]). */
    @Synchronized fun addNative(
        values: LongArray, decode: IntArray, decodeCount: Int,
    ) {
        rendered += values[0].toInt()
        recoveries += values[1].toInt()
        late += values[2].toInt()
        if (values[3] > 0) pacingDelayNanos = values[3]
        heldDecodeSamples += values[4].toInt()
        maxHeldDecodeNs = maxOf(maxHeldDecodeNs, values[5])
        received += values[6].toInt()
        bytes += values[7]
        maxArrivalGapNs = maxOf(maxArrivalGapNs, values[8])
        idleGaps += values[9].toInt()
        maxIdleGapNs = maxOf(maxIdleGapNs, values[10])
        touchSamples += values[11].toInt()
        touchLatencySumNs += values[12]
        maxTouchLatencyNs = maxOf(maxTouchLatencyNs, values[13])
        for (i in 0 until decodeCount) if (decodeSamples < decodeMicros.size) decodeMicros[decodeSamples++] = decode[i]
    }

    /** A paced frame left the decoder after its display time and was shown at once. */
    @Synchronized fun onLate() { late++ }

    /** Smooth video's current display delay. */
    @Synchronized fun onPacingDelay(ns: Long) { pacingDelayNanos = ns }

    private fun decodeSummary(): String {
        if (decodeSamples == 0) return ""
        val sorted = decodeMicros.copyOf(decodeSamples).also { it.sort() }
        fun percentileMs(p: Int) = sorted[(decodeSamples - 1) * p / 100] / 1000
        val delay = if (pacingDelayNanos > 0) " delay=%dms".format(pacingDelayNanos / 1_000_000) else ""
        return " decode p50=%dms p90=%dms late=%d".format(percentileMs(50), percentileMs(90), late) + delay
    }

    @Synchronized fun logIfDue(): String? {
        val now = nanoTime()
        val elapsedNs = now - windowStartNs
        if (elapsedNs < WINDOW_NS) return null
        val seconds = elapsedNs / 1e9
        if (received == 0 && rendered == 0 && heldDecodeSamples == 0 && touchSamples == 0) { windowStartNs = now; return null }
        val touchAvgMs = if (touchSamples == 0) -1 else touchLatencySumNs / touchSamples / 1_000_000
        val line = ("video stats$label rx=%.1ffps shown=%.1ffps maxGap=%dms kbps=%d recoveries=%d " +
            "touch2frame avg=%dms max=%dms n=%d touchSendMax=%dms").format(
            received / seconds, rendered / seconds, maxArrivalGapNs / 1_000_000,
            (bytes * 8 / 1000 / seconds).toLong(), recoveries,
            touchAvgMs, maxTouchLatencyNs / 1_000_000, touchSamples, TouchLatencyProbe.maxSendNs / 1_000_000,
        ) + decodeSummary() + " idleGaps=$idleGaps idleGapMax=${maxIdleGapNs / 1_000_000}ms" +
            " heldDecodeN=$heldDecodeSamples heldDecodeMax=${maxHeldDecodeNs / 1_000_000}ms monotonicNs=$now"
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
        const val IDLE_GAP_NS = 2_000_000_000L // report separately: static content can also cause long gaps
    }
}
