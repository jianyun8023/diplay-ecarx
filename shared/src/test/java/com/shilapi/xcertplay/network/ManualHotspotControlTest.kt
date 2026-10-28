package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class ManualHotspotControlTest {
    private class Fake : ManualHotspotControl.Platform {
        override var sdk = 28
        var permission = true
        var current = ManualHotspotControl.State.OFF
        var writes = 0
        var time = 0L
        var effect: (Boolean, (Int) -> Unit) -> Unit = { _, _ -> }
        var tick: () -> Unit = {}
        override fun permitted() = permission
        override fun state() = current
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
            ManualHotspotControl.State.STARTING to ManualHotspotControl.Result.BUSY,
            ManualHotspotControl.State.STOPPING to ManualHotspotControl.Result.BUSY,
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
}
