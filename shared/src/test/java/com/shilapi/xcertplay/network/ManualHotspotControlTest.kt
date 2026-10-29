package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class ManualHotspotControlTest {
    private class Fake : ManualHotspotControl.Platform {
        override var sdk = 28
        var permission = true
        var current = ManualHotspotControl.State.OFF
        var wifiCurrent = ManualHotspotControl.State.OFF
        var wifiPermission = true
        var wifiWrites = 0
        var wifiEffect: (Boolean, (Int) -> Unit) -> Unit = { _, _ -> }
        var writes = 0
        var time = 0L
        var effect: (Boolean, (Int) -> Unit) -> Unit = { _, _ -> }
        var tick: () -> Unit = {}
        override fun permitted() = permission
        override fun state() = current
        override fun wifiPermitted() = wifiPermission
        override fun wifiState() = wifiCurrent
        override fun requestWifi(enabled: Boolean, response: (Int) -> Unit) { wifiWrites++; wifiEffect(enabled, response) }
        override fun now() = time
        override fun pause() { time += 100; tick() }
        override fun request(enabled: Boolean, response: (Int) -> Unit) { writes++; effect(enabled, response) }
    }
    private fun Fake.request(enabled: Boolean = true, cancelled: () -> Boolean = { false }) =
        ManualHotspotControl(this).request(enabled, cancelled, 300)

    @Test fun decodesEveryStateWithoutTreatingTransitionOrFailureAsOff() {
        val expected = listOf(ManualHotspotControl.State.STOPPING, ManualHotspotControl.State.OFF,
            ManualHotspotControl.State.STARTING, ManualHotspotControl.State.ON, ManualHotspotControl.State.FAILED)
        expected.forEachIndexed { index, state -> assertEquals(state, ManualHotspotControl.decode(index + 10)) }
        assertEquals(ManualHotspotControl.State.UNKNOWN, ManualHotspotControl.decode(null))
        assertEquals(ManualHotspotControl.State.UNKNOWN, ManualHotspotControl.decode(99))
    }
    @Test fun observationAndCapabilityNeverWrite() {
        val fake = Fake(); val control = ManualHotspotControl(fake)
        repeat(3) { control.observe(); control.capability() }
        assertEquals(0, fake.writes)
    }
    @Test fun unsupportedVersionsAndMissingPermissionNeverWrite() {
        for (sdk in listOf(24, 27, 29, 30, 37)) {
            val fake = Fake().apply { this.sdk = sdk }
            assertEquals(ManualHotspotControl.Result.UNSUPPORTED, fake.request()); assertEquals(0, fake.writes)
        }
        val fake = Fake().apply { permission = false }
        assertEquals(ManualHotspotControl.Result.PERMISSION_REQUIRED, fake.request()); assertEquals(0, fake.writes)
    }
    @Test fun unknownTransitionalAndFailedStateNeverWrite() {
        for ((state, result) in listOf(
            ManualHotspotControl.State.UNKNOWN to ManualHotspotControl.Result.UNSUPPORTED,
            ManualHotspotControl.State.STARTING to ManualHotspotControl.Result.TIMED_OUT,
            ManualHotspotControl.State.STOPPING to ManualHotspotControl.Result.TIMED_OUT,
            ManualHotspotControl.State.FAILED to ManualHotspotControl.Result.FAILED)) {
            val fake = Fake().apply { current = state }
            assertEquals(result, fake.request()); assertEquals(0, fake.writes)
        }
    }
    @Test fun acceptedStartIsNotSuccessUntilObservedOn() {
        val fake = Fake().apply { effect = { _, callback -> callback(0) } }
        assertEquals(ManualHotspotControl.Result.TIMED_OUT, fake.request()); assertEquals(1, fake.writes)
        fake.tick = { fake.current = ManualHotspotControl.State.ON }
        assertEquals(ManualHotspotControl.Result.ON, fake.request()); assertEquals(2, fake.writes)
    }
    @Test fun stopRequiresOffNotStoppingAndNeverRestartsOnTimeout() {
        val fake = Fake().apply { current = ManualHotspotControl.State.ON
            effect = { enabled, _ -> assertFalse(enabled); current = ManualHotspotControl.State.STOPPING } }
        assertEquals(ManualHotspotControl.Result.TIMED_OUT, fake.request(false)); assertEquals(1, fake.writes)
        fake.current = ManualHotspotControl.State.ON
        fake.tick = { fake.current = ManualHotspotControl.State.OFF }
        assertEquals(ManualHotspotControl.Result.OFF, fake.request(false))
    }
    @Test fun targetAlreadyObservedIsIdempotent() {
        val fake = Fake()
        assertEquals(ManualHotspotControl.Result.OFF, fake.request(false)); assertEquals(0, fake.writes)
    }
    @Test fun systemRejectionDoesNotRetryOrUseAnotherBackend() {
        for (error in listOf(SecurityException(), InvocationTargetException(SecurityException()))) {
            val fake = Fake().apply { effect = { _, _ -> throw error } }
            assertEquals(ManualHotspotControl.Result.PERMISSION_REQUIRED, fake.request()); assertEquals(1, fake.writes)
        }
        val fake = Fake().apply { effect = { _, _ -> throw NoSuchMethodException() } }
        assertEquals(ManualHotspotControl.Result.UNSUPPORTED, fake.request()); assertEquals(1, fake.writes)
    }
    @Test fun callbackFailureDoesNotReportSuccessOrRetry() {
        val fake = Fake().apply { effect = { _, response -> response(5) } }
        assertEquals(ManualHotspotControl.Result.FAILED, fake.request()); assertEquals(1, fake.writes)
    }
    @Test fun cancelledBeforeWriteDoesNothingAndAfterWriteDoesNotUndo() {
        val before = Fake()
        assertEquals(ManualHotspotControl.Result.CANCELLED, before.request(cancelled = { true })); assertEquals(0, before.writes)
        val after = Fake(); var cancel = false
        after.effect = { _, _ -> cancel = true }
        assertEquals(ManualHotspotControl.Result.CANCELLED, after.request(cancelled = { cancel })); assertEquals(1, after.writes)
        assertFalse(ManualHotspotControl.isBusy())
    }
    @Test fun busyRejectsDuplicateRequestsAndConnectionOwnerTransfer() {
        val fake = Fake(); var connection = false
        fake.effect = { _, _ ->
            assertTrue(ManualHotspotControl.isBusy())
            assertEquals(ManualHotspotControl.Result.BUSY, Fake().request())
            assertFalse(ManualHotspotControl.runIfIdle { connection = true })
        }
        fake.request()
        assertFalse(connection)
        assertTrue(ManualHotspotControl.runIfIdle { connection = true }); assertTrue(connection)
    }
    @Test fun lateCallbackCannotCompleteANewerRequest() {
        var callback: ((Int) -> Unit)? = null
        val fake = Fake().apply { effect = { _, response -> callback = response } }
        assertEquals(ManualHotspotControl.Result.TIMED_OUT, fake.request())
        val stale = callback!!
        fake.effect = { _, _ -> stale(0) }
        assertEquals(ManualHotspotControl.Result.TIMED_OUT, fake.request()); assertEquals(2, fake.writes)
    }

    @Test fun diagnosticsDistinguishCallbackRejectionAndPermissionFailureWithoutExceptionMessages() {
        val reports = mutableListOf<String>()
        val fake = Fake().apply { effect = { _, response -> response(5) } }
        val control = ManualHotspotControl(fake, reports::add)
        assertEquals(ManualHotspotControl.Result.FAILED, control.request(true, { false }, 300))
        assertTrue(reports.contains("phase=preflight enabled=true sdk=28 capability=AVAILABLE"))
        assertTrue(reports.contains("phase=observed enabled=true before=OFF"))
        assertTrue(reports.contains("phase=callback enabled=true code=5"))
        fake.effect = { _, _ -> throw InvocationTargetException(SecurityException("private ROM text")) }
        assertEquals(ManualHotspotControl.Result.PERMISSION_REQUIRED, control.request(true, { false }, 300))
        assertTrue(reports.contains("phase=failure enabled=true error=SecurityException"))
        assertFalse(reports.any { it.contains("private ROM text") })
    }

    @Test fun unavailableDiagnosticsDoNotChangeThePlatformRequestOrItsResult() {
        val fake = Fake().apply { effect = { _, response -> response(0); current = ManualHotspotControl.State.ON } }
        val control = ManualHotspotControl(fake) { throw IllegalStateException("diagnostic unavailable") }
        assertEquals(ManualHotspotControl.Result.ON, control.request(true, { false }, 300))
        assertEquals(1, fake.writes)
        assertFalse(ManualHotspotControl.isBusy())
    }

    @Test fun wifiControlWorksEvenWhenHotspotHasFailedOrIsTransitioning() {
        for (hotspotState in listOf(ManualHotspotControl.State.FAILED, ManualHotspotControl.State.STARTING)) {
            val fake = Fake().apply {
                current = hotspotState
                permission = false // WRITE_SETTINGS belongs to tethering, not Wi-Fi.
                wifiEffect = { enabled, response ->
                    assertTrue(enabled)
                    response(0)
                    wifiCurrent = ManualHotspotControl.State.ON
                }
            }
            assertEquals(ManualHotspotControl.Result.ON,
                ManualHotspotControl(fake).request(true, { false }, 300, wifi = true))
            assertEquals(1, fake.wifiWrites)
            assertEquals(0, fake.writes)
        }
    }

    @Test fun wifiRequestUsesObservedStateAndNeverRetriesARejectedOrTimedOutWrite() {
        val fake = Fake().apply { wifiEffect = { _, response -> response(0) } }
        val control = ManualHotspotControl(fake)
        assertEquals(ManualHotspotControl.Result.TIMED_OUT, control.request(true, { false }, 300, wifi = true))
        assertEquals(1, fake.wifiWrites)
        fake.wifiEffect = { _, response -> response(1) }
        assertEquals(ManualHotspotControl.Result.FAILED, control.request(true, { false }, 300, wifi = true))
        assertEquals(2, fake.wifiWrites)
        assertEquals(ManualHotspotControl.State.OFF, control.observeWifi())
        fake.wifiPermission = false
        assertEquals(ManualHotspotControl.Result.PERMISSION_REQUIRED, control.request(true, { false }, 300, wifi = true))
        assertEquals(2, fake.wifiWrites)
    }

    @Test fun wifiSharesConnectionGateAndCancelsWithoutUndoingASentRequest() {
        val fake = Fake()
        var cancel = false
        fake.wifiEffect = { _, _ ->
            assertFalse(ManualHotspotControl.runIfIdle { fail("Connection must not start") })
            assertEquals(ManualHotspotControl.Result.BUSY, Fake().request())
            cancel = true
        }
        assertEquals(ManualHotspotControl.Result.CANCELLED,
            ManualHotspotControl(fake).request(true, { cancel }, 300, wifi = true))
        assertEquals(1, fake.wifiWrites)
        assertEquals(0, fake.writes)
        assertFalse(ManualHotspotControl.isBusy())
    }
    @Test fun recoveryReadsWifiAndOnlyTurnsItOffFirstWhenItIsOn() {
        for (wifiInitial in listOf(ManualHotspotControl.State.ON, ManualHotspotControl.State.OFF)) {
            val actions = mutableListOf<String>()
            val fake = Fake().apply {
                current = ManualHotspotControl.State.FAILED
                wifiCurrent = wifiInitial
                effect = { enabled, response ->
                    actions += "hotspot=$enabled"
                    assertTrue(ManualHotspotControl.isBusy())
                    assertFalse(ManualHotspotControl.runIfIdle { fail("connection must stay blocked throughout recovery") })
                    response(0)
                    // Both stop and start return before the latched FAILED state changes.
                    if (enabled) tick = { current = ManualHotspotControl.State.ON }
                }
                wifiEffect = { enabled, response ->
                    actions += "wifi=$enabled"
                    response(0)
                    wifiCurrent = if (enabled) ManualHotspotControl.State.ON else ManualHotspotControl.State.OFF
                }
            }
            assertEquals(ManualHotspotControl.Result.ON,
                ManualHotspotControl(fake).request(true, { false }, 300, recover = true))
            val expected = mutableListOf("hotspot=false")
            if (wifiInitial == ManualHotspotControl.State.ON) expected += "wifi=false"
            expected += listOf("wifi=true", "wifi=false", "hotspot=true")
            assertEquals(expected, actions)
            assertFalse(ManualHotspotControl.isBusy())
        }
    }

    @Test fun recoveryWaitsForWifiTransitionAndRefusesUnknownStateOrMissingPermissionBeforeWriting() {
        val fake = Fake().apply {
            current = ManualHotspotControl.State.FAILED
            wifiCurrent = ManualHotspotControl.State.STARTING
            tick = { wifiCurrent = ManualHotspotControl.State.OFF }
            effect = { enabled, _ -> if (enabled) current = ManualHotspotControl.State.ON }
            wifiEffect = { enabled, _ -> wifiCurrent = if (enabled) ManualHotspotControl.State.ON else ManualHotspotControl.State.OFF }
        }
        assertEquals(ManualHotspotControl.Result.ON,
            ManualHotspotControl(fake).request(true, { false }, 300, recover = true))
        assertTrue(fake.time >= 100)
        for ((state, permitted, expected) in listOf(
            Triple(ManualHotspotControl.State.UNKNOWN, true, ManualHotspotControl.Result.UNSUPPORTED),
            Triple(ManualHotspotControl.State.OFF, false, ManualHotspotControl.Result.PERMISSION_REQUIRED))) {
            val blocked = Fake().apply { wifiCurrent = state; wifiPermission = permitted }
            assertEquals(expected, ManualHotspotControl(blocked).request(true, { false }, 300, recover = true))
            assertEquals(0, blocked.writes + blocked.wifiWrites)
        }
    }

    @Test fun recoveryStopsAtTimeoutAndDoesNotStartHotspotOrRetryWifi() {
        val fake = Fake().apply {
            current = ManualHotspotControl.State.FAILED
            wifiCurrent = ManualHotspotControl.State.ON
            effect = { enabled, _ -> assertFalse(enabled) }
            wifiEffect = { enabled, response -> assertFalse(enabled); response(0) }
        }
        assertEquals(ManualHotspotControl.Result.TIMED_OUT,
            ManualHotspotControl(fake).request(true, { false }, 300, recover = true))
        assertEquals(1, fake.writes)
        assertEquals(1, fake.wifiWrites)
        assertFalse(ManualHotspotControl.isBusy())
    }

    @Test fun recoveryCancellationBetweenStepsNeverStartsHotspotOrUndoesTheWifiWrite() {
        var cancelled = false
        val fake = Fake().apply {
            current = ManualHotspotControl.State.FAILED
            effect = { enabled, _ -> assertFalse(enabled) }
            wifiEffect = { enabled, _ -> assertTrue(enabled); wifiCurrent = ManualHotspotControl.State.ON; cancelled = true }
        }
        assertEquals(ManualHotspotControl.Result.CANCELLED,
            ManualHotspotControl(fake).request(true, { cancelled }, 300, recover = true))
        assertEquals(1, fake.writes)
        assertEquals(1, fake.wifiWrites)
        assertFalse(ManualHotspotControl.isBusy())
    }

    @Test fun sharedStartupTriesNormallyThenRecoversOnceAndKeepsHealthyHotspotsUntouched() {
        val actions = mutableListOf<String>()
        var starts = 0
        val fake = Fake().apply {
            effect = { enabled, callback ->
                actions += "hotspot=$enabled"
                if (enabled && starts++ == 0) {
                    current = ManualHotspotControl.State.FAILED
                    callback(5)
                } else if (enabled) current = ManualHotspotControl.State.ON
            }
            wifiEffect = { enabled, _ ->
                actions += "wifi=$enabled"
                wifiCurrent = if (enabled) ManualHotspotControl.State.ON else ManualHotspotControl.State.OFF
            }
        }
        val control = ManualHotspotControl(fake)
        assertEquals(ManualHotspotControl.Result.ON, control.ensureHotspot({ false }, 300))
        assertEquals(listOf("hotspot=true", "hotspot=false", "wifi=true", "wifi=false", "hotspot=true"), actions)
        assertEquals(ManualHotspotControl.Result.ON, control.ensureHotspot({ false }, 300))
        assertEquals(5, actions.size)
    }

    @Test fun sharedStartupNeverRecoversPermissionErrorsOrRepeatsARecoveryFailure() {
        val denied = Fake().apply { effect = { _, _ -> throw SecurityException() } }
        assertEquals(ManualHotspotControl.Result.PERMISSION_REQUIRED,
            ManualHotspotControl(denied).ensureHotspot({ false }, 300))
        assertEquals(0, denied.wifiWrites)
        val failed = Fake().apply {
            effect = { enabled, callback -> if (enabled) callback(1) }
            wifiEffect = { enabled, _ -> wifiCurrent = if (enabled) ManualHotspotControl.State.ON else ManualHotspotControl.State.OFF }
        }
        assertEquals(ManualHotspotControl.Result.FAILED,
            ManualHotspotControl(failed).ensureHotspot({ false }, 300))
        assertEquals(2, failed.writes)
        assertEquals(2, failed.wifiWrites)
        val expired = Fake()
        assertEquals(ManualHotspotControl.Result.TIMED_OUT,
            ManualHotspotControl(expired).ensureHotspot({ false }, 0))
        assertEquals(0, expired.writes + expired.wifiWrites)
    }

    @Test fun sharedStartupAndWifiRecoverySpendOneDeadlineInsteadOfRestartingEachStep() {
        val fake = Fake().apply {
            effect = { enabled, callback ->
                if (enabled) { time += 100; current = ManualHotspotControl.State.FAILED; callback(1) }
            }
            wifiEffect = { enabled, _ ->
                wifiCurrent = if (enabled) ManualHotspotControl.State.STARTING else ManualHotspotControl.State.STOPPING
                tick = { wifiCurrent = if (enabled) ManualHotspotControl.State.ON else ManualHotspotControl.State.OFF }
            }
        }
        assertEquals(ManualHotspotControl.Result.TIMED_OUT,
            ManualHotspotControl(fake).ensureHotspot({ false }, 300))
        assertEquals(300L, fake.time)
        assertEquals(2, fake.writes) // Initial start and recovery stop; no start after the budget.
        assertEquals(2, fake.wifiWrites)
        assertFalse(ManualHotspotControl.isBusy())
    }

}
