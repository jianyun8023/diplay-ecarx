package com.shilapi.xcertplay.orchestration

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Speculative Bluetooth RFCOMM socket connect, started right after the paired target is selected
 * and run in parallel with the wireless network preparation (AirPlay listener, Bonjour, endpoint).
 * In the v124 real-car session C the serial gap between bt/select and the first connect was 235ms
 * while the connect itself took 259ms; running them concurrently overlaps both.
 *
 * Safety contract: only the socket connect is parallelised. The caller still runs
 * validateInfrastructure() — including the Bonjour check — before adopting a pre-connected socket
 * and before any iAP2 CSM or bootstrap traffic, so the Bonjour gate is not relaxed. A failed or
 * stale pre-connect is surfaced to the caller, which rethrows it as the bootstrap attempt's own
 * connect failure so [WirelessBootstrapRecovery] keeps its classification and retry budget.
 *
 * The outcome is handed out exactly once: the first attempt for the original target consumes it,
 * and backoff retries or fallback phones fall back to a serial connect with a fresh socket.
 */
internal class RfcommPreconnect<T : Any>(
    private val targetAddress: String,
    private val isCurrent: () -> Boolean,
    private val createSocket: () -> T,
    /** Connects the socket, returns the connect duration in milliseconds, throws on failure. */
    private val connectSocket: (T) -> Long,
    private val log: (String) -> Unit = {},
) {
    sealed interface Outcome<out T : Any> {
        /** The socket connected before or while the caller was preparing the network side. */
        class Adopted<T : Any>(val socket: T, val connectMillis: Long) : Outcome<T>

        /** The speculative connect failed; the caller rethrows the error as its own failure. */
        class Failed(val error: Throwable) : Outcome<Nothing>

        /** Stale run, consumed outcome, or a different target: the caller connects serially. */
        object Unavailable : Outcome<Nothing>
    }

    private val done = CountDownLatch(1)
    private val consumed = AtomicBoolean(false)

    @Volatile
    private var outcome: Outcome<T> = Outcome.Unavailable

    /** Runs on the pre-connect thread; exposed so tests can drive it deterministically. */
    fun run() {
        try {
            if (!isCurrent()) {
                outcome = Outcome.Unavailable
                return
            }
            val socket = createSocket()
            val connectMillis = connectSocket(socket)
            outcome = Outcome.Adopted(socket, connectMillis)
        } catch (error: Throwable) {
            outcome = Outcome.Failed(error)
        } finally {
            done.countDown()
        }
    }

    fun start(): Thread = Thread(::run, THREAD_NAME).apply {
        isDaemon = true
        start()
    }

    /**
     * Resolves the pre-connect for one bootstrap attempt. Blocks while the connect is still in
     * flight; [awaitMillis] must cover the connect timeout budget so a wedged pre-connect surfaces
     * as an IOException instead of a silent fallback that would open a second socket.
     */
    fun consume(address: String, awaitMillis: Long): Outcome<T> {
        if (!consumed.compareAndSet(false, true)) return Outcome.Unavailable
        if (!address.equals(targetAddress, ignoreCase = true)) {
            log("rfcomm preconnect skipped: attempt target differs from preconnect target")
            return Outcome.Unavailable
        }
        try {
            if (!done.await(awaitMillis, TimeUnit.MILLISECONDS)) {
                throw IOException("Timed out after ${awaitMillis}ms waiting for the RFCOMM preconnect")
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for the RFCOMM preconnect", error)
        }
        return outcome
    }

    private companion object {
        const val THREAD_NAME = "wireless-rfcomm-preconnect"
    }
}
