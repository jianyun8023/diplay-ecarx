package com.shihab.diplay.orchestration

import com.shihab.diplay.network.WirelessStartupFailure
import com.shihab.diplay.transport.TransportFailure
import com.shihab.diplay.transport.TransportFailureKind
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ConnectionRecoveryTest {
    @Test fun usbRecoveryMatchesFormerHostForPolicyAndEvents() {
        val old = HostUsbRecoveryReference()
        val actual = HostUsbRecovery()
        for (bits in 0 until 16) {
            val f = (0..3).map { bits and (1 shl it) != 0 }
            assertEquals(old.shouldTakeOver(f[0], f[1], f[2], f[3]),
                HostUsbRecovery.shouldTakeOver(f[0], f[1], f[2], f[3]))
        }
        for (bits in 0 until 8) {
            val f = (0..2).map { bits and (1 shl it) != 0 }
            assertEquals(old.fallback(f[0], f[1], f[2]), actual.fallbackAction(f[0], f[1], f[2]))
        }
        assertEquals(old.delay(false), actual.delayMillis(false))
        assertEquals(old.delay(true), actual.delayMillis(true))
        old.reenumerating = true; actual.waiting()
        assertEquals(old.reenumerating, actual.reenumerating)
        assertEquals(old.delay(true), actual.delayMillis(true))
        old.reenumerating = false; actual.failed()
        assertEquals(old.reenumerating, actual.reenumerating)
        actual.waiting(); actual.running()
        assertEquals(old.delay(true), actual.delayMillis(true))
        actual.waiting(); actual.disconnected()
        assertEquals(old.delay(true), actual.delayMillis(true))
    }
    @Test fun hostFailureLatchesMatchFormerHostForIndependentTransitions() {
        val old = HostFailureReference()
        val actual = HostFailureState()
        for (generation in listOf(-1, Int.MIN_VALUE, 0, Int.MAX_VALUE, -1)) {
            assertEquals(old.duplicateStartup(generation), actual.duplicateStartup(generation))
            assertEquals(old.duplicateStartup(generation), actual.duplicateStartup(generation))
            old.stopUsb(generation); actual.stopUsb(generation)
            assertEquals(old.stopped, actual.stopped)
            assertEquals(old.usbBlocks(generation), actual.usbBlocks(generation))
            old.resume(); actual.resume()
            assertEquals(old.stopped, actual.stopped)
            assertEquals(old.usbBlocks(generation), actual.usbBlocks(generation))
            old.usbFallback(); actual.usbFallback()
            assertEquals(old.usbBlocks(generation), actual.usbBlocks(generation))
            assertEquals(old.duplicateStartup(generation), actual.duplicateStartup(generation))
        }
    }
    @Test fun backgroundAttemptTransitionsMatchFormerHostMarker() {
        val actual = BackgroundAttemptState()
        val old = BackgroundAttemptReference()
        assertEquals(old.consumeFailure(), actual.consumeFailure())
        old.keep(); actual.keep()
        old.keep(); actual.keep()
        assertEquals(old.consumeFailure(), actual.consumeFailure())
        assertEquals(old.consumeFailure(), actual.consumeFailure())
        old.keep(); actual.keep()
        old.foreground(); actual.foreground()
        assertEquals(old.consumeFailure(), actual.consumeFailure())
    }
    @Test fun menuDecisionsMatchFormerHostBranchesForAllFacts() {
        for (existing in listOf(false, true)) for (incoming in listOf(false, true)) {
            val old = MenuRecoveryReference.failure(existing, incoming)
            val actual = ConnectionRecovery.menuFailure(existing, incoming)
            assertEquals(old.first, actual.replace)
            assertEquals(old.second, actual.stop)
        }
        for (bits in 0 until 512) {
            val f = (0..8).map { bits and (1 shl it) != 0 }
            assertEquals("facts=$bits", MenuRecoveryReference.exit(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8]),
                ConnectionRecovery.menuExit(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8]))
        }
    }
    @Test fun controlRecoveryMatchesFormerHostAcrossSnapshotsAndLosses() {
        val actual = ControlRecoveryState()
        val old = ControlRecoveryReference()
        val initial = actual.snapshot() to old.snapshot()
        actual.loss(); old.loss()
        assertEquals(old.recoveredSince(initial.second), actual.recoveredSince(initial.first))
        val afterLoss = actual.snapshot() to old.snapshot()
        actual.running(); old.running()
        assertEquals(old.recoveredSince(initial.second), actual.recoveredSince(initial.first))
        assertEquals(old.recoveredSince(afterLoss.second), actual.recoveredSince(afterLoss.first))
        actual.loss(); old.loss()
        assertEquals(old.recoveredSince(initial.second), actual.recoveredSince(initial.first))
        actual.running(); old.running()
        assertEquals(old.recoveredSince(initial.second), actual.recoveredSince(initial.first))
    }
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
