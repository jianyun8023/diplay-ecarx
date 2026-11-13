package com.shihab.diplay.orchestration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

class WirelessHandoffTest {
    @Test
    fun activeTunnelKeepsHandoffAliveAfterBluetoothBootstrapCloses() {
        assertTrue(
            isWirelessHandoffInProgress(
                handoffRequested = false,
                tunnelActive = true,
                sessionActive = true,
            ),
        )
    }

    @Test
    fun expectedCsMCloseAfterTunnelPromotionIsNotAControlFailure() {
        assertTrue(isExpectedHandoffControlClose(
            WirelessBootstrapRecovery.Stage.CONTROL, IOException("CSM channel is closed"),
            wirelessActive = true, tunnelReady = true,
        ))
    }

    @Test
    fun genuineFailuresAndPrematureClosesStillFail() {
        val close = IOException("CSM channel is closed")
        assertFalse(isExpectedHandoffControlClose(
            WirelessBootstrapRecovery.Stage.CONTROL, close,
            wirelessActive = false, tunnelReady = true,
        ))
        assertFalse(isExpectedHandoffControlClose(
            WirelessBootstrapRecovery.Stage.BOOTSTRAP, close,
            wirelessActive = true, tunnelReady = true,
        ))
        assertFalse(isExpectedHandoffControlClose(
            WirelessBootstrapRecovery.Stage.CONTROL, InterruptedIOException("cancelled"),
            wirelessActive = true, tunnelReady = true,
        ))
        assertFalse(isExpectedHandoffControlClose(
            WirelessBootstrapRecovery.Stage.CONTROL,
            ConnectionFailureException(ConnectionFailureCause.WIRELESS_CARPLAY_UNAVAILABLE, "rejected"),
            wirelessActive = true, tunnelReady = true,
        ))
    }

    @Test
    fun ordinaryBootstrapLossStillFailsWithoutHandoffState() {
        assertFalse(
            isWirelessHandoffInProgress(
                handoffRequested = false,
                tunnelActive = false,
                sessionActive = false,
            ),
        )
    }
}
