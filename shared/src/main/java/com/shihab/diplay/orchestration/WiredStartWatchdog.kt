package com.shihab.diplay.orchestration

/**
 * A time limit for the iPhone's AirPlay session after a wired `carplay-start-session`.
 *
 * The wired control loop has no limit of its own, so an iPhone that accepts the start request but never opens the
 * session kept DiPlay on "connecting" until the cable was replugged (#889, #890). On a working head unit the session
 * is active 2 to 13 s after the request. When the limit passes, [onTimeout] ends the attempt so the usual retry starts
 * a new one, which asks the iPhone to switch modes again, as a replug does.
 *
 * After a Save-and-reconnect the iPhone opened the USB network 15.8 to 30.2 s after the request, four of eight times
 * at 28.9 to 30.2 s (#335), too late for DiPlay's second request ([WiredStartAgain]) to go out within the limit, so
 * the limit is held once while that request still can.
 */
internal class WiredStartWatchdog(
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onTimeout: () -> Unit,
    private val secondRequestCanStillGoOut: () -> Boolean = { false },
    private val onHold: () -> Unit = {},
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    private val core = StartupWatchdogCore(0, timeout = timeoutMillis)
    private var cancelTimer: (() -> Unit)? = null

    @Synchronized fun startSessionSent() {
        val result = core.event(1)
        if (result.has(StartupWatchdogCore.ARM)) arm(result)
    }

    @Synchronized fun startSessionSentAgain() {
        val result = core.event(2)
        if (!result.has(StartupWatchdogCore.ACCEPTED)) return
        cancel()
        arm(core.event(15))
    }

    @Synchronized fun sessionActive() { core.event(3); cancel() }
    @Synchronized fun terminate() { core.event(3); cancel() }

    private fun arm(result: StartupWatchdogCore.Step) {
        val generation = result.generation
        cancelTimer = schedule(result.delay) { expire(generation) }
    }

    private fun expire(armedGeneration: Int) {
        val holding = synchronized(this) {
            val decision = core.event(4, armedGeneration).flags
            if (decision == 0) return
            val result = core.event(5, condition = decision == 2 && secondRequestCanStillGoOut())
            if (result.has(StartupWatchdogCore.HOLD)) {
                arm(result)
                true
            } else {
                cancelTimer = null
                false
            }
        }
        if (holding) onHold() else onTimeout()
    }

    private fun cancel() { cancelTimer?.invoke(); cancelTimer = null }

    companion object {
        /** The same limit as the wireless first connection (WirelessStartupPolicy.FIRST_TCP_MILLIS). */
        const val TIMEOUT_MILLIS = 30_000L

        /** Well past the latest USB network seen (30.2 s, #335) plus [WiredStartAgain.QUIET_MILLIS]. */
        const val HELD_TIMEOUT_MILLIS = 45_000L
    }
}
