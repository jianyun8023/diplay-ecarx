package com.shilapi.xcertplay.network

/**
 * A car-hotspot start that runs before any controller owns the hotspot, such as the boot
 * receiver's. The hotspot control allows one start at a time and answers BUSY to a second, so a
 * controller waits here for the head start instead, and then only confirms the AP. Everything
 * before the hotspot in the wireless start (Bluetooth readiness, MFi, the VPN bind) still runs
 * meanwhile.
 */
object HotspotHeadStart {
    private val lock = Object()
    private var running = false

    /** False when a head start is already running. */
    fun begin(): Boolean = synchronized(lock) {
        if (running) false else { running = true; true }
    }

    fun finish() = synchronized(lock) {
        running = false
        lock.notifyAll()
    }

    fun isRunning(): Boolean = synchronized(lock) { running }

    /** Waits until the head start ends; false when [isCancelled] or [timeoutMillis] ended the wait first. */
    fun await(timeoutMillis: Long, isCancelled: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        synchronized(lock) {
            while (running) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000L
                if (remaining <= 0 || isCancelled()) return false
                // Short slices keep a cancelled wireless run responsive.
                lock.wait(minOf(remaining, 100L))
            }
            return true
        }
    }
}
