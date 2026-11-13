package com.shihab.diplay.transport

import com.shihab.diplay.airplay.NativeMedia

/** Rust-owned bounded header counters; the GC token remains queryable after the USB owner closes. */
internal class NcmTrafficCounter(private val nanoTime: () -> Long = System::nanoTime) {
    private val state = run { NativeMedia.requireAvailable(); NativeNcm.valueCreate(1) }
    @Synchronized fun received(frame: ByteArray) {
        val port = NativeNcm.trafficHeader(state, frame)
        if (port >= 0) NativeNcm.trafficTime(state, port, nanoTime())
    }
    @Synchronized fun connectionAttemptSince(port: Int, sinceNanos: Long): Boolean = NativeNcm.trafficAttempt(state, port, sinceNanos)
    @Synchronized fun sent() = NativeNcm.trafficSent(state)
    @Synchronized fun summary(airPlayPort: Int?): String = NativeNcm.trafficSummary(state, airPlayPort ?: -1)
}
