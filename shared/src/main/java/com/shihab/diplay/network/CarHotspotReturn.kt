package com.shihab.diplay.network

/**
 * Engine off then the driver door: the head unit turns Bluetooth and its hotspot off together, and
 * waking the screen turns both back on, in no fixed order. After Bluetooth is back the restart must
 * not give up on a hotspot that is still off, or CarPlay stays down until the driver reconnects.
 */
internal object CarHotspotReturn {
    enum class Outcome { ON, UNKNOWN, TIMED_OUT, CANCELLED }

    /** Watches [state] (true on, false off, null unreadable) without switching anything on. */
    fun await(
        state: () -> Boolean?,
        isCurrent: () -> Boolean,
        timeoutMillis: Long,
        nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
        pause: (Long) -> Unit = Thread::sleep,
        log: (String) -> Unit = {},
    ): Outcome {
        val started = nowMillis()
        var waiting = false
        while (true) {
            if (Thread.currentThread().isInterrupted || !isCurrent()) return Outcome.CANCELLED
            when (state()) {
                true -> {
                    if (waiting) log("car hotspot back on waitMs=${nowMillis() - started}")
                    return Outcome.ON
                }
                // An unreadable state must not block a head unit that cannot report it.
                null -> return Outcome.UNKNOWN
                false -> Unit
            }
            if (!waiting) log("car hotspot still off; waiting for the car to turn it back on budgetMs=$timeoutMillis")
            waiting = true
            val remaining = NativeHotspotRecovery.returnWait(started, timeoutMillis, nowMillis())
            if (remaining <= 0) return Outcome.TIMED_OUT
            try {
                pause(remaining)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Outcome.CANCELLED
            }
        }
    }

}

/**
 * Process-wide memory that the car turned its radios off (hotspot lost). The host rebuilds the
 * controller 15 s after the tunnel drops, and that new controller must keep waiting for the car
 * instead of failing on Bluetooth or the hotspot. Cleared once a session is active again or the
 * driver stops CarPlay, and it lapses with the wait budget.
 */
object CarRadioOutage {
    private val state = NativeHotspotRecovery.outageCreate()

    @Synchronized fun mark(nowMillis: Long = android.os.SystemClock.elapsedRealtime()) {
        NativeHotspotRecovery.outageEvent(state, 0, nowMillis, 0)
    }

    @Synchronized fun clear() { NativeHotspotRecovery.outageEvent(state, 1, 0, 0) }

    @Synchronized fun active(nowMillis: Long = android.os.SystemClock.elapsedRealtime(), budgetMillis: Long = BUDGET_MILLIS): Boolean =
        NativeHotspotRecovery.outageEvent(state, 2, nowMillis, budgetMillis)

    /** Matches the controller's 30-minute radio-return wait. */
    const val BUDGET_MILLIS = 30 * 60_000L
}
