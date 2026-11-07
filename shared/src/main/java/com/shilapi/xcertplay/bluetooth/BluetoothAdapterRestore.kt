package com.shilapi.xcertplay.bluetooth

import android.bluetooth.BluetoothAdapter

/** Worker-only confirmation: an accepted enable request is not a restored radio. */
internal object BluetoothAdapterRestore {
    enum class Outcome { ON, REJECTED, TIMED_OUT, CANCELLED }

    /** Only the first 90 seconds of a real boot may observe an OEM-enabled radio from OFF. */
    const val EARLY_BOOT_WINDOW_MILLIS = 90_000L
    const val PASSIVE_BOOT_WAIT_MILLIS = 8_000L

    fun shouldPassivelyObserveBootRadio(
        earlyBoot: Boolean, initialState: Int, ownedPause: Boolean,
    ): Boolean = earlyBoot && !ownedPause && initialState == BluetoothAdapter.STATE_OFF

    fun awaitOn(
        state: () -> Int,
        requestEnable: (() -> Boolean)?,
        isCurrent: () -> Boolean,
        nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
        pause: (Long) -> Unit = Thread::sleep,
        timeoutMillis: Long = 5_000L,
    ): Outcome {
        val started = nowMillis()
        var requested = false
        while (true) {
            if (Thread.currentThread().isInterrupted || !isCurrent()) return Outcome.CANCELLED
            val observed = state()
            if (observed == BluetoothAdapter.STATE_ON) {
                return if (isCurrent()) Outcome.ON else Outcome.CANCELLED
            }
            val remaining = timeoutMillis - (nowMillis() - started)
            if (remaining <= 0) return Outcome.TIMED_OUT
            // Do not issue enable during an in-flight disable or duplicate a platform enable.
            if (observed == BluetoothAdapter.STATE_OFF && !requested && requestEnable != null) {
                if (!isCurrent()) return Outcome.CANCELLED
                if (!requestEnable()) return if (isCurrent()) Outcome.REJECTED else Outcome.CANCELLED
                requested = true
            }
            try {
                pause(minOf(100L, remaining))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Outcome.CANCELLED
            }
        }
    }
}
