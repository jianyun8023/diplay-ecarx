package com.shihab.diplay.orchestration

import java.util.concurrent.CancellationException

/** Budgets belong to one wireless run, not to each phone or socket. No Wi-Fi resources live here. */
internal class WirelessBootstrapRecovery(
    private val isCurrent: () -> Boolean,
    private val validateInfrastructure: () -> Unit,
    private val handoffStarted: () -> Boolean,
    private val pause: (Long) -> Unit = Thread::sleep,
    private val log: (String) -> Unit = {},
) {
    enum class Stage { CONNECT, CSM, BOOTSTRAP, CONTROL }

    /** A typed #38 cause that is still eligible for #36's bounded local recovery. */
    class BootstrapFailure(reason: ConnectionFailureCause, message: String, cause: Throwable? = null) :
        ConnectionFailureException(reason, message, cause)

    class Attempt(private val current: () -> Boolean, val number: Int) {
        private var token = WirelessRecoveryCore.attemptCreate()
        // Presentation metadata for the typed platform exception; validity/stage/timeout live in the token.
        private var stall: Iap2BootstrapStall? = null
        var stage: Stage
            @Synchronized get() = Stage.entries[WirelessRecoveryCore.attemptQuery(token, 1)]
            @Synchronized set(value) { token = WirelessRecoveryCore.attemptUpdate(token, value.ordinal + 2) }
        val isCurrent: Boolean get() = synchronized(this) {
            WirelessRecoveryCore.attemptQuery(token, 0) != 0
        } && current()
        fun checkCurrent() {
            if (!isCurrent) throw failure(CancellationException("Stale wireless bootstrap attempt"))
        }
        @Synchronized fun invalidate() { token = WirelessRecoveryCore.attemptUpdate(token, 0) }

        /** Called under the controller's owner lock, before closing this attempt's transport. */
        @Synchronized fun timeout(value: Iap2BootstrapStall): Boolean {
            if (!isCurrent) return false
            token = WirelessRecoveryCore.attemptUpdate(token, 1, value.reached.ordinal)
            stall = value
            return true
        }

        /** Closing a timed-out channel often surfaces as cancellation/EOF; retain the real stage. */
        fun failure(error: Throwable): Throwable {
            val facts = if (error is Error) WirelessFailureFacts.TOP_ERROR else WirelessFailureFacts.from(error)
            val decision: Int
            val timeout: Iap2BootstrapStall?
            synchronized(this) {
                decision = WirelessRecoveryCore.attemptFailure(token, facts)
                timeout = stall
            }
            return when (decision) {
                1, 4 -> requireNotNull(timeout).let { timeout ->
                    BootstrapFailure(
                        if (decision == 4) ConnectionFailureCause.WIRELESS_BLUETOOTH_LINK_FAILED
                        else ConnectionFailureCause.WIRELESS_PHONE_NOT_RESPONDING,
                        timeout.message(),
                    ).apply { addSuppressed(error) }
                }
                2, 3 -> BootstrapFailure(
                    if (decision == 2) ConnectionFailureCause.WIRELESS_BLUETOOTH_LINK_FAILED
                    else ConnectionFailureCause.WIRELESS_PHONE_NOT_RESPONDING,
                    error.message ?: "Wireless iAP2 bootstrap failed at ${if (decision == 2) Stage.CSM else Stage.BOOTSTRAP}", error,
                )
                else -> error
            }
        }
    }

    fun <T> run(
        target: T,
        validateTarget: (T) -> Unit,
        fallback: (T) -> T?,
        // Must release this attempt's CSM/stream/socket before propagating a failure.
        connectAndBootstrap: (T, Attempt) -> Unit,
    ) {
        var selected = target
        var budget = WirelessRecoveryCore.budgetCreate()
        fun checkReady() {
            if (!isCurrent()) throw CancellationException("Stale wireless run")
            if (handoffStarted()) throw CancellationException("Wireless handoff already started")
            validateInfrastructure()
            validateTarget(selected)
            if (!isCurrent() || handoffStarted()) throw CancellationException("Wireless retry cancelled")
        }
        while (true) {
            checkReady()
            budget = WirelessRecoveryCore.budgetAdvance(budget, -1)
            val attempt = Attempt(isCurrent, WirelessRecoveryCore.budgetQuery(budget, 0))
            try {
                connectAndBootstrap(selected, attempt)
                return
            } catch (error: Exception) {
                attempt.invalidate()
                if (!isCurrent() || handoffStarted() || !retryable(error)) throw error
                // Recheck radio, permissions, pairing and retained Wi-Fi services *before* retrying.
                checkReady()
                budget = WirelessRecoveryCore.budgetAdvance(budget, attempt.stage.ordinal)
                when (WirelessRecoveryCore.budgetQuery(budget, 1)) {
                    0 -> throw error
                    2 -> selected = fallback(selected) ?: throw error
                }
                val delay = WirelessRecoveryCore.budgetQuery(budget, 3).toLong()
                log("local recovery stage=${attempt.stage} attempt=${attempt.number} delayMs=$delay " +
                    "fallback=${WirelessRecoveryCore.budgetQuery(budget, 2) != 0}")
                // Short cancellable slices, including an infrastructure check after the backoff.
                while (true) {
                    val slice = WirelessRecoveryCore.budgetQuery(budget, 4).toLong()
                    if (slice <= 0) break
                    if (!isCurrent() || handoffStarted()) throw CancellationException("Wireless retry cancelled")
                    pause(slice)
                    budget = WirelessRecoveryCore.budgetAdvance(budget, -2)
                }
            } finally {
                attempt.invalidate()
            }
        }
    }

    companion object {
        /**
         * Kept above the watchdog's phase budget so the watchdog owns the timeout and turns a stall
         * into one typed local recovery. This value only unwedges a blocking wait if the watchdog
         * cannot close the transport first.
         */
        const val PHASE_SAFETY_MARGIN_MILLIS = 5_000L

        /** CSM wait safety net; the phase budget is [Iap2BootstrapPolicy.RFCOMM_TO_CSM_MILLIS]. */
        const val CSM_TIMEOUT_MILLIS = Iap2BootstrapPolicy.RFCOMM_TO_CSM_MILLIS + PHASE_SAFETY_MARGIN_MILLIS

        /**
         * Handshake safety net above [Iap2BootstrapPolicy.CSM_TO_MFI_MILLIS]. The watchdog fires
         * first and the stall still maps to one local bootstrap recovery.
         */
        const val HANDSHAKE_TIMEOUT_MILLIS = Iap2BootstrapPolicy.CSM_TO_MFI_MILLIS + PHASE_SAFETY_MARGIN_MILLIS

        const val CONNECT_BACKOFF_MILLIS = 400L
        const val BOOTSTRAP_BACKOFF_MILLIS = 800L

        /** Do not let the host turn a terminal prerequisite/rejection into a full-stack retry loop. */
        internal fun requiresUserAction(error: Throwable): Boolean =
            WirelessRecoveryCore.classify(WirelessFailureFacts.from(error)) and 1 != 0

        internal fun retryable(error: Throwable): Boolean =
            WirelessRecoveryCore.classify(WirelessFailureFacts.from(error)) and 2 != 0
    }
}
