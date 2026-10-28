package com.shilapi.xcertplay.bluetooth

import android.bluetooth.BluetoothAdapter
import org.junit.Assert.*
import org.junit.Test

class BluetoothAdapterRestoreTest {
    private class Radio {
        var state = BluetoothAdapter.STATE_OFF
        var time = 0L
        var current = true
        var enables = 0
        var accepted = true
        var advance: () -> Unit = {}
        fun restore() = BluetoothAdapterRestore.awaitOn(
            state = { state },
            requestEnable = { enables++; accepted },
            isCurrent = { current },
            nowMillis = { time },
            pause = { time += it; advance() },
            timeoutMillis = 300,
        )
    }

    @Test fun acceptedRequestWithoutStateOnTimesOut() {
        val radio = Radio()
        assertEquals(BluetoothAdapterRestore.Outcome.TIMED_OUT, radio.restore())
        assertEquals(1, radio.enables)
        assertEquals(300L, radio.time)
    }

    @Test fun delayedStateOnConfirmsActualRecovery() {
        val radio = Radio()
        radio.advance = { radio.state = if (radio.time < 200) BluetoothAdapter.STATE_TURNING_ON else BluetoothAdapter.STATE_ON }
        assertEquals(BluetoothAdapterRestore.Outcome.ON, radio.restore())
        assertEquals(200L, radio.time)
        assertEquals(1, radio.enables)
    }

    @Test fun alreadyOnNeedsNoEnable() {
        val radio = Radio().apply { state = BluetoothAdapter.STATE_ON }
        assertEquals(BluetoothAdapterRestore.Outcome.ON, radio.restore())
        assertEquals(0, radio.enables)
    }

    @Test fun inFlightEnableIsObservedWithoutDuplicateRequest() {
        val radio = Radio().apply { state = BluetoothAdapter.STATE_TURNING_ON }
        radio.advance = { radio.state = BluetoothAdapter.STATE_ON }
        assertEquals(BluetoothAdapterRestore.Outcome.ON, radio.restore())
        assertEquals(0, radio.enables)
    }

    @Test fun disableCompletesBeforeEnableRequest() {
        val radio = Radio().apply { state = BluetoothAdapter.STATE_TURNING_OFF }
        radio.advance = { radio.state = if (radio.time < 200) BluetoothAdapter.STATE_OFF else BluetoothAdapter.STATE_ON }
        assertEquals(BluetoothAdapterRestore.Outcome.ON, radio.restore())
        assertEquals(1, radio.enables)
    }

    @Test fun refusedEnableDoesNotClaimRestoration() {
        val radio = Radio().apply { accepted = false }
        assertEquals(BluetoothAdapterRestore.Outcome.REJECTED, radio.restore())
        assertEquals(0L, radio.time)
    }

    @Test fun replacedOwnerCancelsWaitingWithoutAnotherRequest() {
        val radio = Radio()
        radio.advance = { radio.current = false; radio.state = BluetoothAdapter.STATE_ON }
        assertEquals(BluetoothAdapterRestore.Outcome.CANCELLED, radio.restore())
        assertEquals(1, radio.enables)
        assertEquals(100L, radio.time)
    }

    @Test fun staleOwnerNeverEnablesRadio() {
        val radio = Radio().apply { current = false }
        assertEquals(BluetoothAdapterRestore.Outcome.CANCELLED, radio.restore())
        assertEquals(0, radio.enables)
    }

    @Test fun interruptedWaitRetainsInterruptAndCancels() {
        try {
            assertEquals(BluetoothAdapterRestore.Outcome.CANCELLED, BluetoothAdapterRestore.awaitOn(
                state = { BluetoothAdapter.STATE_TURNING_ON }, requestEnable = { fail("must not enable"); false },
                isCurrent = { true }, pause = { throw InterruptedException() },
            ))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
    @Test fun observerWaitsThroughQueuedOffAndTurningOnWithoutEnabling() {
        var time = 0L
        val outcome = BluetoothAdapterRestore.awaitOn(
            state = { when { time < 100 -> BluetoothAdapter.STATE_OFF
                time < 200 -> BluetoothAdapter.STATE_TURNING_ON
                else -> BluetoothAdapter.STATE_ON } },
            requestEnable = null,
            isCurrent = { true }, nowMillis = { time }, pause = { time += it }, timeoutMillis = 300,
        )
        assertEquals(BluetoothAdapterRestore.Outcome.ON, outcome)
        assertEquals(200L, time)
    }

}
