package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.NativeMedia
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NativeWirelessRunStateDeviceTest {
    @Test fun nativeTransitionSequencesMatchTheExistingKotlinContract() {
        assertTrue(NativeMedia.available)
        val random = Random(20261112)
        repeat(1000) {
            val actual = WirelessRunState()
            val reference = KotlinWirelessRunState()
            assertState(reference, actual)
            repeat(32) {
                when (random.nextInt(8)) {
                    0 -> assertEquals(reference.requestHandoff(), actual.requestHandoff())
                    1 -> assertEquals(reference.markTunnelReady(), actual.markTunnelReady())
                    2 -> assertEquals(reference.promoteTunnel(), actual.promoteTunnel())
                    3 -> assertEquals(reference.keepBluetoothFallback(), actual.keepBluetoothFallback())
                    4 -> assertEquals(reference.fail(), actual.fail())
                    5 -> { reference.retire(); actual.retire() }
                    6 -> assertEquals(reference.beginClose(), actual.beginClose())
                    7 -> assertEquals(runCatching { reference.finishClose() }.exceptionOrNull()?.javaClass,
                        runCatching { actual.finishClose() }.exceptionOrNull()?.javaClass)
                }
                assertState(reference, actual)
            }
        }
    }

    @Test fun closingAndLateCallbacksKeepTheTerminalStateAndReportFailureOnce() {
        assertTrue(NativeMedia.available)
        repeat(50) {
            val state = WirelessRunState()
            state.requestHandoff()
            val start = CountDownLatch(1)
            val failures = AtomicInteger()
            val closer = FutureTask {
                start.await()
                assertTrue(state.beginClose())
                state.finishClose()
            }
            val failing = FutureTask {
                start.await()
                repeat(100) { if (state.fail()) failures.incrementAndGet() }
            }
            val callbacks = FutureTask {
                start.await()
                repeat(100) {
                    state.running
                    state.markTunnelReady()
                    state.promoteTunnel()
                    state.keepBluetoothFallback()
                    state.retire()
                    state.lifecycle
                }
            }
            for (task in listOf(closer, failing, callbacks)) Thread(task).start()
            start.countDown()
            for (task in listOf(closer, failing, callbacks)) task.get(5, TimeUnit.SECONDS)
            assertEquals(WirelessRunState.Lifecycle.CLOSED, state.lifecycle)
            assertEquals(1, failures.get())
            assertTrue(state.failedOnce)
            assertFalse(state.running)
            assertFalse(state.requestHandoff())
            assertFalse(state.markTunnelReady())
            assertFalse(state.promoteTunnel())
            assertFalse(state.keepBluetoothFallback())
            assertFalse(state.beginClose())
            assertFalse(state.fail())
        }
    }

    private fun assertState(expected: KotlinWirelessRunState, actual: WirelessRunState) {
        assertEquals(expected.lifecycle, actual.lifecycle)
        assertEquals(expected.handoff, actual.handoff)
        assertEquals(expected.tunnelReady, actual.tunnelReady)
        assertEquals(expected.failedOnce, actual.failedOnce)
        assertEquals(expected.running, actual.running)
        assertEquals(expected.handoffRequested, actual.handoffRequested)
        assertEquals(expected.activeReported, actual.activeReported)
    }
}
