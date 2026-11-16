package com.shihab.diplay.orchestration

import com.shihab.diplay.network.WirelessStartupFailure
import com.shihab.diplay.transport.TransportFailure
import com.shihab.diplay.transport.TransportFailureKind
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ConnectionRecoveryTest {
    @Test fun startupFailuresKeepTheirSeparateBudgetEligibility() {
        for (cause in WirelessStartupFailure.entries) {
            val expected = when (cause) {
                WirelessStartupFailure.HOTSPOT_NOT_READY, WirelessStartupFailure.FIRST_TCP_TIMEOUT -> ConnectionRecovery.STARTUP_RETRY
                else -> ConnectionRecovery.STARTUP_STOP
            }
            val decision = CarPlayStatus.Failed("localized", startupFailure = cause).recovery
            assertEquals(expected, decision.action)
            assertFalse(decision.requiresUserAction) // unchanged menu deferral until the host consumes the failure
        }
    }

    @Test fun terminalAndWifiResetPrecedenceAlsoPreservesMenuDeferral() {
        val rejected = CarPlayStatus.Failed("拒绝", automaticRetryAllowed = false)
        assertEquals(ConnectionRecovery.USER_ACTION, rejected.recovery.action)
        assertTrue(rejected.recovery.requiresUserAction)
        val reset = rejected.copy(wifiResetRequired = true)
        assertEquals(ConnectionRecovery.WIFI_RESET, reset.recovery.action)
        assertTrue(reset.recovery.requiresUserAction)
        assertEquals(ConnectionRecovery.USB_PAIRING,
            reset.copy(usbPairingFailure = UsbPairingFailure.TRUST_REFUSED).recovery.action)
        val unavailable = CarPlayStatus.Failed("unavailable", knownCause = ConnectionFailureCause.WIRELESS_CARPLAY_UNAVAILABLE)
        assertEquals(ConnectionRecovery.USER_ACTION, unavailable.recovery.action)
    }

    @Test fun transportKindsSurviveWrappingAndDoNotInterpretMessageText() {
        val radio = IOException("outer", TransportFailure(TransportFailureKind.BLUETOOTH_OFF, "蓝牙已关闭"))
        assertEquals(TransportFailureKind.BLUETOOTH_OFF, TransportFailureKind.from(radio))
        assertTrue(WirelessBootstrapRecovery.requiresUserAction(radio))
        assertFalse(WirelessBootstrapRecovery.retryable(radio))
        assertEquals(TransportFailureKind.OTHER, TransportFailureKind.from(IOException("AirPlay iAP tunnel")))
        assertFalse(WirelessBootstrapRecovery.requiresUserAction(IOException("Turn on Bluetooth")))
        val tunnel = IOException("outer", TransportFailure(TransportFailureKind.IAP_TUNNEL, "隧道已关闭"))
        assertEquals(15_000L, HostRetryState().schedule(startup = false,
            tunnel = TransportFailureKind.from(tunnel) == TransportFailureKind.IAP_TUNNEL))
    }

    @Test fun normalBackoffHasNoAttemptLimitAndTunnelDelayRemainsFixed() {
        val state = HostRetryState()
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..5).map { state.schedule(startup = false, tunnel = false) })
        repeat(3) {
            assertEquals(ConnectionRecovery.RETRY, CarPlayStatus.Failed("lost").recovery.action)
            assertEquals(15_000L, state.schedule(startup = false, tunnel = true))
        }
    }
}
