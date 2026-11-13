package com.shihab.diplay.orchestration

import com.shihab.diplay.mfi.Iap2MfiAuthenticationException
import com.shihab.diplay.mfi.MfiAuthenticationFailedException
import com.shihab.diplay.network.WirelessStartupException
import com.shihab.diplay.network.WirelessStartupFailure
import com.shihab.diplay.orchestration.WirelessBootstrapRecovery.Attempt
import com.shihab.diplay.orchestration.WirelessBootstrapRecovery.Stage
import com.shihab.diplay.transport.Iap2IdentificationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException

class WirelessBootstrapRecoveryTest {
    @Test fun connectRetriesSameTargetOnceWithFreshAttempt() {
        val fixture = Fixture()
        fixture.run { _, attempt ->
            assertTrue(attempt.isCurrent)
            attempt.checkCurrent()
            assertEquals(Stage.CONNECT, attempt.stage)
            if (attempt.number == 1) throw IOException("RFCOMM connect failed")
            assertNotSame(fixture.attempts[0], attempt)
            assertStale(fixture.attempts[0])
        }

        assertEquals(listOf("phone-a", "phone-a"), fixture.targets)
        assertEquals(listOf(1, 2), fixture.attempts.map { it.number })
        assertEquals(List(4) { 100L }, fixture.pauses)
        assertEquals(0, fixture.fallbackTargets.size)
        fixture.attempts.forEach(::assertStale)
    }

    @Test fun connectBudgetIsSharedWithFallbackAndNeverReturnsToOriginalTarget() {
        val fixture = Fixture()
        val failure = IOException("connect failed")
        fixture.onFallback = { if (it == "phone-a") "phone-b" else "phone-a" }

        assertSame(failure, assertThrows(IOException::class.java) {
            fixture.run { _, _ -> throw failure }
        })

        assertEquals(listOf("phone-a", "phone-a", "phone-b"), fixture.targets)
        assertEquals(listOf("phone-a"), fixture.fallbackTargets)
        assertEquals(listOf(1, 2, 3), fixture.attempts.map { it.number })
        assertEquals(WirelessBootstrapRecovery.CONNECT_BACKOFF_MILLIS, fixture.pauses.sum())
        fixture.attempts.forEach(::assertStale)
    }

    @Test fun unavailableFallbackPropagatesOriginalFailureWithoutAnotherAttempt() {
        val fixture = Fixture()
        val failure = IOException("no alternative phone")
        fixture.onFallback = { null }

        assertSame(failure, assertThrows(IOException::class.java) {
            fixture.run { _, _ -> throw failure }
        })

        assertEquals(listOf("phone-a", "phone-a"), fixture.targets)
        assertEquals(listOf("phone-a"), fixture.fallbackTargets)
    }

    @Test fun csmAndBootstrapEachRecoverOnceWithoutFallback() {
        for (stage in listOf(Stage.CSM, Stage.BOOTSTRAP)) {
            val fixture = Fixture()
            fixture.run { _, attempt ->
                if (attempt.number == 1) {
                    attempt.stage = stage
                    throw IOException("bootstrap link closed")
                }
                assertStale(fixture.attempts.first())
            }

            assertEquals(stage.name, listOf("phone-a", "phone-a"), fixture.targets)
            assertEquals(List(8) { 100L }, fixture.pauses)
            assertTrue(fixture.fallbackTargets.isEmpty())
            assertNotSame(fixture.attempts[0], fixture.attempts[1])
        }
    }

    @Test fun csmAndBootstrapShareOneRecoveryBudgetInEitherOrder() {
        for (stages in listOf(listOf(Stage.CSM, Stage.BOOTSTRAP), listOf(Stage.BOOTSTRAP, Stage.CSM))) {
            val fixture = Fixture()
            val failure = IOException("bootstrap failed again")
            assertSame(failure, assertThrows(IOException::class.java) {
                fixture.run { _, attempt ->
                    attempt.stage = stages[attempt.number - 1]
                    throw failure
                }
            })

            assertEquals(listOf("phone-a", "phone-a"), fixture.targets)
            assertEquals(WirelessBootstrapRecovery.BOOTSTRAP_BACKOFF_MILLIS, fixture.pauses.sum())
            assertTrue(fixture.fallbackTargets.isEmpty())
        }
    }

    @Test fun connectAndBootstrapHaveSeparateBudgetsButNeitherResetsOnFallback() {
        val fixture = Fixture()
        val failure = IOException("link closed")
        val stages = listOf(Stage.CONNECT, Stage.CSM, Stage.CONNECT, Stage.BOOTSTRAP)

        assertSame(failure, assertThrows(IOException::class.java) {
            fixture.run { _, attempt ->
                attempt.stage = stages[attempt.number - 1]
                throw failure
            }
        })

        assertEquals(listOf("phone-a", "phone-a", "phone-a", "phone-b"), fixture.targets)
        assertEquals(listOf("phone-a"), fixture.fallbackTargets)
        assertEquals(
            WirelessBootstrapRecovery.CONNECT_BACKOFF_MILLIS + WirelessBootstrapRecovery.BOOTSTRAP_BACKOFF_MILLIS,
            fixture.pauses.sum(),
        )
    }

    @Test fun controlFailureNeverRetriesOrFallsBack() {
        assertNoRecovery(IOException("control failed"), Stage.CONTROL)
    }

    @Test fun securityAndWrappedSecurityFailuresNeverRetry() {
        val denied = SecurityException("BLUETOOTH_CONNECT denied")
        assertNoRecovery(denied)
        assertNoRecovery(IOException("socket failed", denied))
        assertNoRecovery(IOException("outer", IOException("inner", SecurityException("permission revoked"))))
    }

    @Test fun radioStartupFailuresNeverRetryLocallyEvenWhenStartupPolicyAllowsRetry() {
        for (reason in WirelessStartupFailure.values()) {
            val failure = WirelessStartupException(reason, "radio or hotspot unavailable")
            assertNoRecovery(failure)
            assertNoRecovery(IOException("wrapped startup failure", failure))
        }
    }

    @Test fun missingSelectedOrPairedPhoneNeverRetries() {
        for (reason in listOf(ConnectionFailureCause.CHOSEN_IPHONE_NOT_PAIRED, ConnectionFailureCause.NO_PAIRED_IPHONE)) {
            val failure = ConnectionFailureException(reason, "iPhone no longer exists")
            assertNoRecovery(failure)
            assertNoRecovery(IOException("wrapped missing target", failure))
        }
    }

    @Test fun identificationRejectionAndExplicitMfiRejectionNeverRetry() {
        val rejected = Iap2IdentificationException.Rejected(setOf(1, 2))
        assertNoRecovery(rejected, Stage.BOOTSTRAP)
        assertNoRecovery(IOException("wrapped identification rejection", rejected), Stage.BOOTSTRAP)
        assertNoRecovery(Iap2MfiAuthenticationException("iPhone rejected authentication"), Stage.BOOTSTRAP)
        assertNoRecovery(MfiAuthenticationFailedException(1), Stage.BOOTSTRAP)
        assertNoRecovery(IOException("wrapped chip rejection", MfiAuthenticationFailedException(1)), Stage.BOOTSTRAP)
    }

    @Test fun mfiTimeoutRetriesBootstrapWithFreshAttempt() {
        val fixture = Fixture()
        val timeout = Iap2MfiAuthenticationException("timed out waiting for authentication", retryable = true)
        assertTrue(WirelessBootstrapRecovery.retryable(timeout))

        fixture.run { _, attempt ->
            attempt.stage = Stage.BOOTSTRAP
            if (attempt.number == 1) throw timeout
            assertStale(fixture.attempts.first())
        }

        assertEquals(listOf("phone-a", "phone-a"), fixture.targets)
        assertEquals(WirelessBootstrapRecovery.BOOTSTRAP_BACKOFF_MILLIS, fixture.pauses.sum())
        assertTrue(fixture.fallbackTargets.isEmpty())
    }

    @Test fun wrappedMfiTimeoutStillRetriesBootstrapOnce() {
        val fixture = Fixture()
        val wrapped = IOException(
            "bootstrap stream closed",
            Iap2MfiAuthenticationException("timed out waiting for authentication", retryable = true),
        )
        assertTrue(WirelessBootstrapRecovery.retryable(wrapped))

        fixture.run { _, attempt ->
            attempt.stage = Stage.BOOTSTRAP
            if (attempt.number == 1) throw wrapped
            assertStale(fixture.attempts.first())
        }

        assertEquals(2, fixture.attempts.size)
        assertEquals(WirelessBootstrapRecovery.BOOTSTRAP_BACKOFF_MILLIS, fixture.pauses.sum())
    }

    @Test fun wrappedMfiRejectionNeverRetries() {
        assertNoRecovery(
            IOException("bootstrap stream closed", Iap2MfiAuthenticationException("iPhone sent AuthenticationFailed")),
            Stage.BOOTSTRAP,
        )
    }

    @Test fun retryableMfiFlagCannotOverridePermissionFailureInCauseChain() {
        assertNoRecovery(
            Iap2MfiAuthenticationException("authentication unavailable", SecurityException("denied"), retryable = true),
            Stage.BOOTSTRAP,
        )
    }

    @Test fun cancellationInterruptionAndProgrammingFailuresNeverRetry() {
        for (failure in listOf(
            CancellationException("cancelled"),
            InterruptedException("interrupted"),
            InterruptedIOException("interrupted I/O"),
            IllegalArgumentException("invalid target"),
            IllegalStateException("closed infrastructure"),
        )) {
            assertNoRecovery(failure)
            assertNoRecovery(IOException("wrapped non-retryable failure", failure))
        }
    }

    @Test fun closedRunOrChangedGenerationPreventsFirstAttempt() {
        for (close in listOf(true, false)) {
            val fixture = Fixture()
            if (close) fixture.closed = true else fixture.generation++

            assertThrows(CancellationException::class.java) { fixture.run { _, _ -> fail("stale run connected") } }

            assertTrue(fixture.attempts.isEmpty())
            assertEquals(0, fixture.infrastructureChecks)
            assertTrue(fixture.pauses.isEmpty())
        }
    }

    @Test fun closingOrReplacingGenerationDuringFailureDoesNotRetry() {
        for (close in listOf(true, false)) {
            val fixture = Fixture()
            val failure = IOException("socket closed")
            assertSame(failure, assertThrows(IOException::class.java) {
                fixture.run { _, _ ->
                    if (close) fixture.closed = true else fixture.generation++
                    throw failure
                }
            })

            assertEquals(1, fixture.attempts.size)
            assertTrue(fixture.pauses.isEmpty())
            assertTrue(fixture.fallbackTargets.isEmpty())
            assertStale(fixture.attempts.single())
        }
    }

    @Test fun closingOrReplacingGenerationDuringBackoffCancelsBeforeAnotherAttempt() {
        for (close in listOf(true, false)) {
            for (cancelOnLastSlice in listOf(false, true)) {
                val fixture = Fixture()
                fixture.onPause = {
                    assertStale(fixture.attempts.single())
                    if (!cancelOnLastSlice || fixture.pauses.sum() == WirelessBootstrapRecovery.CONNECT_BACKOFF_MILLIS) {
                        if (close) fixture.closed = true else fixture.generation++
                    }
                }

                assertThrows(CancellationException::class.java) {
                    fixture.run { _, _ -> throw IOException("connect failed") }
                }

                assertEquals(if (cancelOnLastSlice) 4 else 1, fixture.pauses.size)
                assertEquals(1, fixture.attempts.size)
                assertTrue(fixture.fallbackTargets.isEmpty())
            }
        }
    }

    @Test fun interruptionDuringBackoffPropagatesWithoutNewAttempt() {
        val fixture = Fixture()
        val interrupted = InterruptedException("shutdown interrupted sleep")
        fixture.onPause = { throw interrupted }

        assertSame(interrupted, assertThrows(InterruptedException::class.java) {
            fixture.run { _, _ -> throw IOException("connect failed") }
        })

        assertEquals(listOf(100L), fixture.pauses)
        assertEquals(1, fixture.attempts.size)
        assertStale(fixture.attempts.single())
    }

    @Test fun infrastructureAndTargetAreValidatedBeforeEachAttemptAndAfterBackoff() {
        val fixture = Fixture()
        fixture.run { _, attempt -> if (attempt.number == 1) throw IOException("connect failed") }

        assertEquals(
            listOf(
                "infrastructure", "target:phone-a", "attempt:phone-a:1",
                "infrastructure", "target:phone-a",
                "pause:100", "pause:100", "pause:100", "pause:100",
                "infrastructure", "target:phone-a", "attempt:phone-a:2",
            ),
            fixture.events,
        )
    }

    @Test fun invalidInfrastructureBeforeFirstAttemptNeverCreatesAttempt() {
        val fixture = Fixture()
        val failure = SecurityException("radio permission missing")
        fixture.onValidateInfrastructure = { throw failure }

        assertSame(failure, assertThrows(SecurityException::class.java) { fixture.run { _, _ -> fail("connected") } })

        assertEquals(listOf("infrastructure"), fixture.events)
        assertTrue(fixture.attempts.isEmpty())
    }

    @Test fun invalidInfrastructureAfterFailurePreventsBackoffAndNewAttempt() {
        val fixture = Fixture()
        val failure = IllegalStateException("retained Wi-Fi service lost")
        fixture.onValidateInfrastructure = { if (fixture.infrastructureChecks == 2) throw failure }

        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            fixture.run { _, _ -> throw IOException("connect failed") }
        })

        assertEquals(2, fixture.infrastructureChecks)
        assertEquals(1, fixture.attempts.size)
        assertTrue(fixture.pauses.isEmpty())
        assertTrue(fixture.fallbackTargets.isEmpty())
        assertStale(fixture.attempts.single())
    }

    @Test fun infrastructureLostDuringEitherBackoffIsCheckedBeforeCreatingNextAttempt() {
        for (stage in listOf(Stage.CONNECT, Stage.CSM, Stage.BOOTSTRAP)) {
            val fixture = Fixture()
            val failure = SecurityException("radio disabled during backoff")
            var infrastructureValid = true
            fixture.onValidateInfrastructure = { if (!infrastructureValid) throw failure }
            fixture.onPause = { infrastructureValid = false }

            assertSame(failure, assertThrows(SecurityException::class.java) {
                fixture.run { _, attempt ->
                    attempt.stage = stage
                    throw IOException("transport failed")
                }
            })

            assertEquals(3, fixture.infrastructureChecks)
            assertEquals(1, fixture.attempts.size)
            assertEquals("infrastructure", fixture.events.last())
            assertTrue(fixture.fallbackTargets.isEmpty())
            assertStale(fixture.attempts.single())
        }
    }

    @Test fun fallbackTargetIsValidatedBeforeItsAttemptWithoutAdditionalBackoff() {
        val fixture = Fixture()
        val failure = ConnectionFailureException(ConnectionFailureCause.CHOSEN_IPHONE_NOT_PAIRED, "fallback unpaired")
        fixture.onValidateTarget = { if (it == "phone-b") throw failure }

        assertSame(failure, assertThrows(ConnectionFailureException::class.java) {
            fixture.run { _, _ -> throw IOException("connect failed") }
        })

        assertEquals(listOf("phone-a", "phone-a"), fixture.targets)
        assertEquals(listOf("phone-a"), fixture.fallbackTargets)
        assertEquals(listOf("infrastructure", "target:phone-b"), fixture.events.takeLast(2))
        assertEquals(WirelessBootstrapRecovery.CONNECT_BACKOFF_MILLIS, fixture.pauses.sum())
    }

    @Test fun infrastructureIsRevalidatedEvenWhenFallbackHasNoBackoff() {
        val fixture = Fixture()
        val failure = IllegalStateException("Wi-Fi service lost while choosing fallback")
        var infrastructureValid = true
        fixture.onValidateInfrastructure = { if (!infrastructureValid) throw failure }
        fixture.onFallback = {
            infrastructureValid = false
            "phone-b"
        }

        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            fixture.run { _, _ -> throw IOException("connect failed") }
        })

        assertEquals(listOf("phone-a", "phone-a"), fixture.targets)
        assertEquals(listOf("phone-a"), fixture.fallbackTargets)
        assertEquals(5, fixture.infrastructureChecks)
        assertEquals("infrastructure", fixture.events.last())
        assertEquals(WirelessBootstrapRecovery.CONNECT_BACKOFF_MILLIS, fixture.pauses.sum())
    }

    @Test fun generationChangedByValidationPreventsAttemptCreation() {
        val fixture = Fixture()
        fixture.onValidateTarget = { fixture.generation++ }

        assertThrows(CancellationException::class.java) { fixture.run { _, _ -> fail("stale attempt created") } }

        assertEquals(listOf("infrastructure", "target:phone-a"), fixture.events)
        assertTrue(fixture.attempts.isEmpty())
    }

    @Test fun handoffAlreadyStartedPreventsFirstAttempt() {
        val fixture = Fixture()
        fixture.handoff = true

        assertThrows(CancellationException::class.java) { fixture.run { _, _ -> fail("handoff reconnected") } }

        assertTrue(fixture.attempts.isEmpty())
        assertEquals(0, fixture.infrastructureChecks)
    }

    @Test fun handoffStartedDuringAttemptDoesNotRetryOrFallback() {
        for (stage in Stage.values()) {
            val fixture = Fixture()
            val failure = IOException("Bluetooth closed for handoff")
            assertSame(failure, assertThrows(IOException::class.java) {
                fixture.run { _, attempt ->
                    attempt.stage = stage
                    fixture.handoff = true
                    throw failure
                }
            })

            assertEquals(1, fixture.attempts.size)
            assertTrue(fixture.pauses.isEmpty())
            assertTrue(fixture.fallbackTargets.isEmpty())
            assertStale(fixture.attempts.single())
        }
    }

    @Test fun handoffDuringBackoffCancelsBeforeAnotherAttempt() {
        for (startOnLastSlice in listOf(false, true)) {
            val fixture = Fixture()
            fixture.onPause = {
                if (!startOnLastSlice || fixture.pauses.sum() == WirelessBootstrapRecovery.CONNECT_BACKOFF_MILLIS) {
                    fixture.handoff = true
                }
            }

            assertThrows(CancellationException::class.java) {
                fixture.run { _, _ -> throw IOException("connect failed") }
            }

            assertEquals(if (startOnLastSlice) 4 else 1, fixture.pauses.size)
            assertEquals(1, fixture.attempts.size)
            assertTrue(fixture.fallbackTargets.isEmpty())
        }
    }

    @Test fun attemptReflectsGenerationAndExplicitInvalidationCannotBeReversed() {
        var current = true
        val attempt = Attempt({ current }, 7)
        assertEquals(7, attempt.number)
        assertTrue(attempt.isCurrent)
        current = false
        assertStale(attempt)
        current = true
        attempt.checkCurrent()
        attempt.invalidate()
        attempt.invalidate()
        assertStale(attempt)
    }

    @Test fun currentPredicateRemainsOutsideTheAttemptMonitorAndShortCircuitsWhenInvalid() {
        lateinit var attempt: Attempt
        var calls = 0
        attempt = Attempt({
            assertFalse(Thread.holdsLock(attempt))
            calls++
            true
        }, 1)
        assertTrue(attempt.isCurrent)
        attempt.invalidate()
        assertFalse(attempt.isCurrent)
        assertEquals(1, calls)
    }

    @Test fun handoffBeginningDuringValidationPreventsSocketCreation() {
        val fixture = Fixture()
        fixture.onValidateTarget = { fixture.handoff = true }
        assertThrows(CancellationException::class.java) { fixture.run { _, _ -> fail("must not connect") } }
        assertTrue(fixture.attempts.isEmpty())
        assertTrue(fixture.pauses.isEmpty())
    }

    @Test fun fatalFailurePropagatesUnchangedAndInvalidatesAttempt() {
        val fixture = Fixture()
        val fatal = AssertionError("fatal link failure")
        assertSame(fatal, assertThrows(AssertionError::class.java) { fixture.run { _, _ -> throw fatal } })
        assertStale(fixture.attempts.single())
        assertTrue(fixture.pauses.isEmpty())
        assertTrue(fixture.fallbackTargets.isEmpty())
        assertFalse(WirelessBootstrapRecovery.retryable(IOException("wrapped fatal", fatal)))
    }

    @Test fun watchdogTimeoutRetainsStageAndAllowsOneLocalRecovery() {
        val fixture = Fixture()
        fixture.run { _, attempt ->
            attempt.stage = Stage.BOOTSTRAP
            if (attempt.number == 1) {
                assertTrue(attempt.timeout(stall()))
                assertFalse(attempt.isCurrent)
                val closed = CancellationException("watchdog closed old stream")
                val failure = attempt.failure(closed) as WirelessBootstrapRecovery.BootstrapFailure
                assertEquals(ConnectionFailureCause.WIRELESS_PHONE_NOT_RESPONDING, failure.reason)
                assertSame(closed, failure.suppressed.single())
                assertTrue(WirelessBootstrapRecovery.retryable(failure))
                assertFalse(WirelessBootstrapRecovery.requiresUserAction(failure))
                throw failure
            }
            assertFalse(fixture.attempts.first().isCurrent)
        }
        assertEquals(2, fixture.attempts.size)
        assertEquals(800L, fixture.pauses.sum())
        assertTrue(fixture.fallbackTargets.isEmpty())
    }

    @Test fun watchdogTimeoutBudgetIsNotResetByAReplacementAttempt() {
        val fixture = Fixture()
        val error = assertThrows(WirelessBootstrapRecovery.BootstrapFailure::class.java) {
            fixture.run { _, attempt ->
                attempt.stage = Stage.BOOTSTRAP
                assertTrue(attempt.timeout(stall()))
                throw attempt.failure(IOException("channel closed"))
            }
        }
        assertEquals(ConnectionFailureCause.WIRELESS_PHONE_NOT_RESPONDING, error.reason)
        assertEquals(2, fixture.attempts.size)
        assertEquals(800L, fixture.pauses.sum())
    }

    @Test fun invalidatedOrStaleAttemptCannotAcceptALateWatchdogTimeout() {
        val attempt = Attempt({ true }, 1)
        attempt.invalidate()
        assertFalse(attempt.timeout(stall()))
        val original = IOException("original close")
        assertSame(original, attempt.failure(original))
        assertFalse(Attempt({ false }, 2).timeout(stall()))
    }

    @Test fun watchdogTimeoutCannotReplaceTerminalFailuresOrInterruptions() {
        val failures: List<Exception> = listOf(
            SecurityException("Bluetooth permission revoked"),
            IOException("wrapped permission failure", SecurityException("Bluetooth permission revoked")),
            Iap2IdentificationException.Rejected(setOf(1, 2)),
            IOException("wrapped protocol rejection", Iap2IdentificationException.Rejected(setOf(1, 2))),
            Iap2MfiAuthenticationException("iPhone sent AuthenticationFailed"),
            IOException("wrapped MFi refusal", Iap2MfiAuthenticationException("iPhone sent AuthenticationFailed")),
            MfiAuthenticationFailedException(1),
            InterruptedIOException("owner closed the connection"),
            IllegalStateException("invalid transport state"),
        )
        for (failure in failures) {
            val attempt = Attempt({ true }, 1).apply { stage = Stage.BOOTSTRAP }
            assertTrue(attempt.timeout(stall()))
            assertSame("Watchdog must preserve terminal ${failure.javaClass.simpleName}",
                failure, attempt.failure(failure))
            assertFalse("Terminal ${failure.javaClass.simpleName} must not retry", WirelessBootstrapRecovery.retryable(failure))
        }
    }

    @Test fun watchdogCancellationStillMapsToTypedLocalRecovery() {
        val attempt = Attempt({ true }, 1).apply { stage = Stage.BOOTSTRAP }
        assertTrue(attempt.timeout(stall()))
        val channelClosed = CancellationException("watchdog closed stale channel")
        val failure = attempt.failure(channelClosed) as WirelessBootstrapRecovery.BootstrapFailure
        assertEquals(ConnectionFailureCause.WIRELESS_PHONE_NOT_RESPONDING, failure.reason)
        assertSame(channelClosed, failure.suppressed.single())
        assertTrue(WirelessBootstrapRecovery.retryable(failure))
    }

    @Test fun watchdogTimeoutCannotReplaceAFatalError() {
        val attempt = Attempt({ true }, 1)
        assertTrue(attempt.timeout(stall()))
        val fatal = AssertionError("fatal transport failure")
        assertSame(fatal, attempt.failure(fatal))
    }

    @Test fun failureWrapsAWrappedMfiTimeoutWithTypedReason() {
        val attempt = Attempt({ true }, 1).apply { this.stage = Stage.BOOTSTRAP }
        val wrapped = IOException(
            "stream closed",
            Iap2MfiAuthenticationException("MFi timeout", retryable = true),
        )
        val failure = attempt.failure(wrapped) as WirelessBootstrapRecovery.BootstrapFailure
        assertEquals(ConnectionFailureCause.WIRELESS_PHONE_NOT_RESPONDING, failure.reason)
        assertSame(wrapped, failure.cause)
        assertTrue(WirelessBootstrapRecovery.retryable(failure))
    }

    @Test fun protocolTimeoutsKeepTypedUpstreamCausesWithoutRetryingExplicitRejection() {
        for (stage in listOf(Stage.CSM, Stage.BOOTSTRAP)) {
            val attempt = Attempt({ true }, 1).apply { this.stage = stage }
            val original = if (stage == Stage.CSM) IOException("CSM ready timed out") else
                Iap2MfiAuthenticationException("MFi timeout", retryable = true)
            val failure = attempt.failure(original) as WirelessBootstrapRecovery.BootstrapFailure
            assertEquals(if (stage == Stage.CSM) ConnectionFailureCause.WIRELESS_BLUETOOTH_LINK_FAILED else
                ConnectionFailureCause.WIRELESS_PHONE_NOT_RESPONDING, failure.reason)
            assertSame(original, failure.cause)
            assertTrue(WirelessBootstrapRecovery.retryable(failure))
            val rejected = Iap2MfiAuthenticationException("AuthenticationFailed")
            assertSame(rejected, attempt.failure(rejected))
        }
    }

    private fun stall() = Iap2BootstrapStall(
        com.shihab.diplay.transport.Iap2BootstrapPhase.MFi_AUTHENTICATED,
        com.shihab.diplay.transport.Iap2BootstrapPhase.WIRELESS_CARPLAY_AVAILABLE,
        65_000L,
    )

    private fun assertNoRecovery(failure: Exception, stage: Stage = Stage.CONNECT) {
        val fixture = Fixture()
        if (stage != Stage.CONTROL) {
            assertFalse(failure.toString(), WirelessBootstrapRecovery.retryable(failure))
        }
        assertSame(failure, assertThrows(Exception::class.java) {
            fixture.run { _, attempt ->
                attempt.stage = stage
                throw failure
            }
        })
        assertEquals(failure.toString(), 1, fixture.attempts.size)
        assertTrue(fixture.pauses.isEmpty())
        assertTrue(fixture.fallbackTargets.isEmpty())
        assertStale(fixture.attempts.single())
    }

    private fun assertStale(attempt: Attempt) {
        assertFalse(attempt.isCurrent)
        assertThrows(CancellationException::class.java) { attempt.checkCurrent() }
    }

    private class Fixture {
        var closed = false
        var generation = 1
        var handoff = false
        var infrastructureChecks = 0
        val attempts = mutableListOf<Attempt>()
        val targets = mutableListOf<String>()
        val fallbackTargets = mutableListOf<String>()
        val pauses = mutableListOf<Long>()
        val events = mutableListOf<String>()
        var onValidateInfrastructure: () -> Unit = {}
        var onValidateTarget: (String) -> Unit = {}
        var onPause: (Long) -> Unit = {}
        var onFallback: (String) -> String? = { "phone-b" }
        private val recovery = WirelessBootstrapRecovery(
            isCurrent = { !closed && generation == 1 },
            validateInfrastructure = {
                events += "infrastructure"
                infrastructureChecks++
                onValidateInfrastructure()
            },
            handoffStarted = { handoff },
            pause = { delay ->
                events += "pause:$delay"
                pauses += delay
                onPause(delay)
            },
        )

        fun run(block: (String, Attempt) -> Unit) {
            recovery.run(
                target = "phone-a",
                validateTarget = { target ->
                    events += "target:$target"
                    onValidateTarget(target)
                },
                fallback = { target ->
                    fallbackTargets += target
                    onFallback(target)
                },
                connectAndBootstrap = { target, attempt ->
                    events += "attempt:$target:${attempt.number}"
                    targets += target
                    attempts += attempt
                    block(target, attempt)
                },
            )
        }
    }
}
