package com.shilapi.xcertplay.orchestration

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
            assertFalse(state.keepBluetoothFallback(renderedFrame = true))
            assertEquals(WirelessRunState.Handoff.TUNNEL_ACTIVE, state.handoff)
        }
    }

    @Test fun fallbackNeedsRenderedVideoAndCanLaterPromoteToTunnel() {
        val state = WirelessRunState()
        assertFalse(state.keepBluetoothFallback(renderedFrame = true))
        state.requestHandoff()
        assertFalse(state.keepBluetoothFallback(renderedFrame = false))
        assertFalse(state.activeReported)
        assertTrue(state.keepBluetoothFallback(renderedFrame = true))
        assertFalse(state.keepBluetoothFallback(renderedFrame = true))
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
            assertFalse(state.fail())
            assertFalse(state.requestHandoff())
            assertFalse(state.markTunnelReady())
            assertFalse(state.promoteTunnel())
            assertFalse(state.keepBluetoothFallback(renderedFrame = true))
            if (state.lifecycle != WirelessRunState.Lifecycle.CLOSING) assertTrue(state.beginClose())
            assertFalse(state.beginClose())
            state.finishClose()
            state.retire()
            assertEquals(WirelessRunState.Lifecycle.CLOSED, state.lifecycle)
            assertFalse(state.beginClose())
        }
    }
}
