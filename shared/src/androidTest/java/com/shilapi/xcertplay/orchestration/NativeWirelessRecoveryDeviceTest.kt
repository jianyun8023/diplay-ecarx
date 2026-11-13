package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.NativeMedia
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationException
import com.shilapi.xcertplay.mfi.MfiAuthenticationFailedException
import com.shilapi.xcertplay.transport.Iap2BootstrapPhase
import com.shilapi.xcertplay.transport.Iap2IdentificationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException

class NativeWirelessRecoveryDeviceTest {
    @Test fun nativeRunUsesEachBudgetOnceAcrossFallbackWithoutRetainingOldAttempts() {
        assertTrue(NativeMedia.available)
        val targets = mutableListOf<String>()
        val attempts = mutableListOf<WirelessBootstrapRecovery.Attempt>()
        val pauses = mutableListOf<Long>()
        var fallbacks = 0
        val stages = listOf(WirelessBootstrapRecovery.Stage.CONNECT, WirelessBootstrapRecovery.Stage.CSM,
            WirelessBootstrapRecovery.Stage.CONNECT, WirelessBootstrapRecovery.Stage.BOOTSTRAP)
        val failure = IOException("connection lost")
        val recovery = WirelessBootstrapRecovery({ true }, {}, { false }, pause = { pauses += it })
        assertSame(failure, assertThrows(IOException::class.java) {
            recovery.run("a", {}, { fallbacks++; "b" }) { target, attempt ->
                targets += target
                attempts += attempt
                assertTrue(attempt.isCurrent)
                attempts.dropLast(1).forEach { assertFalse(it.isCurrent) }
                attempt.stage = stages[attempt.number - 1]
                throw failure
            }
        })
        assertEquals(listOf("a", "a", "a", "b"), targets)
        assertEquals(listOf(1, 2, 3, 4), attempts.map { it.number })
        assertEquals(1, fallbacks)
        assertEquals(List(12) { 100L }, pauses)
        attempts.forEach { assertFalse(it.isCurrent) }
    }

    @Test fun nativeWatchdogFailuresKeepTypedReasonsCauseSuppressionAndTerminalPrecedence() {
        assertTrue(NativeMedia.available)
        for (reached in listOf(Iap2BootstrapPhase.RFCOMM_CONNECTED, Iap2BootstrapPhase.MFi_AUTHENTICATED)) {
            val stall = Iap2BootstrapStall(reached, Iap2BootstrapPhase.START_SESSION_SENT, 65_000L)
            val attempt = WirelessBootstrapRecovery.Attempt({ true }, 1).apply {
                stage = WirelessBootstrapRecovery.Stage.BOOTSTRAP
            }
            assertTrue(attempt.timeout(stall))
            assertFalse(attempt.timeout(stall))
            val cancelled = CancellationException("watchdog closed the channel")
            val typed = attempt.failure(cancelled) as WirelessBootstrapRecovery.BootstrapFailure
            assertEquals(stall.cause, typed.reason)
            assertEquals(stall.message(), typed.message)
            assertSame(cancelled, typed.suppressed.single())
            assertNull(typed.cause)
            assertSame(typed, attempt.failure(typed))
            assertTrue(WirelessBootstrapRecovery.retryable(typed))
            for (terminal in listOf(SecurityException("denied"),
                IOException("wrapped", Iap2IdentificationException.Rejected(setOf(1))),
                MfiAuthenticationFailedException(1), Iap2MfiAuthenticationException("rejected"),
                InterruptedIOException("interrupted"), IllegalStateException("bad state"), AssertionError("fatal"))) {
                assertSame(terminal, attempt.failure(terminal))
            }
            assertFalse(attempt.isCurrent)
        }
        val attempt = WirelessBootstrapRecovery.Attempt({ true }, 1).apply { stage = WirelessBootstrapRecovery.Stage.CSM }
        val eof = IOException("CSM closed")
        val typed = attempt.failure(eof) as WirelessBootstrapRecovery.BootstrapFailure
        assertSame(eof, typed.cause)
        assertEquals(ConnectionFailureCause.WIRELESS_BLUETOOTH_LINK_FAILED, typed.reason)
        attempt.invalidate()
        assertFalse(attempt.timeout(Iap2BootstrapStall(Iap2BootstrapPhase.RFCOMM_CONNECTED,
            Iap2BootstrapPhase.CSM_ESTABLISHED, 45_000L)))
    }

    @Test fun nativeCauseFactsAndTokenTransitionsMatchTheUnloadedLibraryFallback() {
        assertTrue(NativeMedia.available)
        val errors = listOf(IOException("EOF"), CancellationException("cancelled"),
            Iap2MfiAuthenticationException("timeout", retryable = true),
            IOException("wrapped", Iap2MfiAuthenticationException("rejected")),
            Iap2MfiAuthenticationException("timeout", SecurityException("denied"), retryable = true),
            IOException("fatal", AssertionError("fatal")), InterruptedIOException("interrupt"),
            ConnectionFailureException(ConnectionFailureCause.IPHONE_NOT_CHOSEN, "choose a phone"))
        for (error in errors) {
            val facts = WirelessFailureFacts.from(error)
            assertEquals(KotlinWirelessRecoveryCore.classify(facts), NativeWirelessRecovery.classify(facts))
            for (token in 0L..31L) {
                assertEquals(KotlinWirelessRecoveryCore.attemptFailure(token, facts), NativeWirelessRecovery.attemptFailure(token, facts))
            }
        }
        for (token in 0L..31L) for (event in 0..5) for (phase in Iap2BootstrapPhase.entries.indices) {
            assertEquals(KotlinWirelessRecoveryCore.attemptUpdate(token, event, phase),
                NativeWirelessRecovery.attemptUpdate(token, event, phase))
        }
        for (sequence in listOf(listOf(0, 0, 0, 0), listOf(0, 1, 0, 2), listOf(2, 1), listOf(3))) {
            var native = NativeWirelessRecovery.budgetCreate()
            var fallback = 0L
            for (stage in sequence) {
                native = NativeWirelessRecovery.budgetAdvance(native, -1)
                fallback = KotlinWirelessRecoveryCore.budgetAdvance(fallback, -1)
                native = NativeWirelessRecovery.budgetAdvance(native, stage)
                fallback = KotlinWirelessRecoveryCore.budgetAdvance(fallback, stage)
                for (field in 0..3) assertEquals(KotlinWirelessRecoveryCore.budgetQuery(fallback, field),
                    NativeWirelessRecovery.budgetQuery(native, field))
                if (NativeWirelessRecovery.budgetQuery(native, 1) == 0) break
            }
        }
    }
}
