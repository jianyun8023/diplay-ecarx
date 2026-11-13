package com.shihab.diplay.orchestration

import org.junit.Assert.*
import org.junit.Test

class WirelessRunStateTest {
    @Test fun tunnelAndRequestMayArriveInEitherOrderButPromoteOnlyOnce() {
        for (tunnelFirst in listOf(false, true)) {
            val state = WirelessRunState()
            if (tunnelFirst) {
                assertTrue(state.markTunnelReady())
                assertFalse(state.promoteTunnel())
                assertTrue(state.requestHandoff())
            } else {
                assertTrue(state.requestHandoff())
                assertFalse(state.promoteTunnel())
                assertTrue(state.markTunnelReady())
            }
            assertTrue(state.promoteTunnel())
            assertFalse(state.promoteTunnel())
            assertFalse(state.requestHandoff())
            assertFalse(state.keepBluetoothFallback())
            assertEquals(WirelessRunState.Handoff.TUNNEL_ACTIVE, state.handoff)
        }
    }

    @Test fun fallbackRequiresPendingHandoffAndCanLaterPromoteToTunnel() {
        val state = WirelessRunState()
        assertFalse(state.keepBluetoothFallback())
        state.requestHandoff()
        assertFalse(state.activeReported)
        assertTrue(state.keepBluetoothFallback())
        assertFalse(state.keepBluetoothFallback())
        assertTrue(state.activeReported)
        assertEquals(WirelessRunState.Handoff.BLUETOOTH_FALLBACK, state.handoff)
        state.markTunnelReady()
        assertTrue(state.promoteTunnel())
        assertFalse(state.promoteTunnel())
    }

    @Test fun failedRetiredAndClosingRunsCannotAcceptEventsOrCloseTwice() {
        for (end in listOf<(WirelessRunState) -> Unit>({ it.fail() }, { it.retire() }, { it.beginClose() })) {
            val state = WirelessRunState()
            state.requestHandoff()
            end(state)
            assertFalse(state.running)
            assertFalse(state.requestHandoff())
            assertFalse(state.markTunnelReady())
            assertFalse(state.promoteTunnel())
            assertFalse(state.keepBluetoothFallback())
            if (state.lifecycle != WirelessRunState.Lifecycle.CLOSING) assertTrue(state.beginClose())
            assertFalse(state.beginClose())
            state.finishClose()
            state.retire()
            assertEquals(WirelessRunState.Lifecycle.CLOSED, state.lifecycle)
            assertFalse(state.beginClose())
        }
    }

    @Test fun firstFailureIsRecordedEvenAfterRetirementOrCloseWithoutRevivingTheRun() {
        for (lifecycle in WirelessRunState.Lifecycle.entries) {
            val state = WirelessRunState()
            if (lifecycle == WirelessRunState.Lifecycle.RETIRED) state.retire()
            if (lifecycle == WirelessRunState.Lifecycle.CLOSING || lifecycle == WirelessRunState.Lifecycle.CLOSED) {
                state.beginClose()
            }
            if (lifecycle == WirelessRunState.Lifecycle.CLOSED) state.finishClose()
            assertFalse(state.failedOnce)
            assertTrue(state.fail())
            assertTrue(state.failedOnce)
            assertFalse(state.running)
            assertEquals(if (lifecycle == WirelessRunState.Lifecycle.RUNNING)
                WirelessRunState.Lifecycle.RETIRED else lifecycle, state.lifecycle)
            assertFalse(state.fail())
        }
    }

    @Test fun failureRemainsRecordedThroughRetirementAndClose() {
        val state = WirelessRunState()
        assertTrue(state.fail())
        state.retire()
        state.beginClose()
        state.finishClose()
        assertTrue(state.failedOnce)
        assertFalse(state.fail())
        assertEquals(WirelessRunState.Lifecycle.CLOSED, state.lifecycle)
    }
}
