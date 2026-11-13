package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.NativeMedia

/** Serializes the opaque Rust value only; no platform callback runs under this monitor. */
internal class StartupWatchdogCore(kind: Int, now: Long = 0, timeout: Long = 0) {
    private val state = run {
        requireNative()
        NativeStartupWatchdog.create(kind, now, timeout)
    }
    @get:Synchronized val finished: Boolean get() = NativeStartupWatchdog.finished(state)
    @Synchronized fun event(event: Int, value: Int = 0, time: Long = 0, condition: Boolean = false): Step =
        Step(NativeStartupWatchdog.event(state, event, value, time, condition))

    class Step(private val values: LongArray) {
        val flags: Int get() = values[0].toInt()
        val delay: Long get() = values[1]
        val generation: Int get() = values[2].toInt()
        val elapsed: Long get() = values[3]
        val reached: Int get() = values[4].toInt()
        val missing: Int get() = values[5].toInt()
        val baseNanos: Long get() = values[6]
        fun has(flag: Int): Boolean = flags and flag != 0
    }
    companion object {
        const val CANCEL = 1
        const val ARM = 2
        const val TIMEOUT = 4
        const val HOLD = 8
        const val PROGRESS = 16
        const val DUPLICATE = 32
        const val ACCEPTED = 64
        const val LOG_SUCCESS = 128
        const val FIRST_ACCEPT = 512
        private fun requireNative() { check(NativeMedia.available) { "Native startup watchdog core is unavailable" } }
        fun budget(missing: Int): Long { requireNative(); return NativeStartupWatchdog.budget(missing) }
        fun elapsed(start: Long, now: Long): Long = NativeStartupWatchdog.elapsed(start, now)
        fun remaining(timeout: Long, start: Long, now: Long): Long = NativeStartupWatchdog.remaining(timeout, start, now)
        fun bluetoothStall(reached: Int): Boolean { requireNative(); return NativeStartupWatchdog.bluetoothStall(reached) }
        fun startAgain(now: Long, start: Long, opened: Long?, active: Boolean, connectionAttempt: Boolean): Int {
            requireNative()
            return NativeStartupWatchdog.startAgain(now, start, opened ?: 0, opened != null, active, connectionAttempt)
        }
    }
}

internal object NativeStartupWatchdog {
    @JvmStatic external fun create(kind: Int, now: Long, timeout: Long): LongArray
    @JvmStatic external fun finished(state: LongArray): Boolean
    @JvmStatic external fun event(state: LongArray, event: Int, value: Int, time: Long, condition: Boolean): LongArray
    @JvmStatic external fun budget(missing: Int): Long
    @JvmStatic external fun elapsed(start: Long, now: Long): Long
    @JvmStatic external fun remaining(timeout: Long, start: Long, now: Long): Long
    @JvmStatic external fun bluetoothStall(reached: Int): Boolean
    @JvmStatic external fun startAgain(now: Long, start: Long, opened: Long, hasOpened: Boolean, active: Boolean, connectionAttempt: Boolean): Int
}
