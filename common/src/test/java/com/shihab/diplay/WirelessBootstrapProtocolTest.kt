package com.shihab.diplay

import com.shihab.diplay.iap2.session.Iap2Session
import com.shihab.diplay.iap2.wire.Iap2Frame
import com.shihab.diplay.iap2.wire.Iap2Parameter
import com.shihab.diplay.iap2.wire.Iap2ParameterList
import com.shihab.diplay.mfi.Iap2MfiAuthenticationClient
import com.shihab.diplay.transport.Iap2BootstrapPhase
import com.shihab.diplay.transport.Iap2IdentificationConfig
import com.shihab.diplay.transport.Iap2WiredControlClient
import com.shihab.diplay.transport.Iap2WirelessCarPlayEndpoint
import com.shihab.diplay.transport.Iap2WirelessControlClient
import com.shihab.diplay.transport.Iap2WirelessControlResult
import com.shihab.diplay.transport.Iap2WirelessControlStage
import com.shihab.diplay.transport.Iap2WirelessControlTerminal
import com.shihab.diplay.transport.Iap2WirelessIdentification
import com.shihab.diplay.transport.Iap2WirelessSecurity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class WirelessBootstrapProtocolTest {
    @Test fun handshakeBudgetBoundsReadinessAuthenticationAndEverySubscription() {
        val fixture = Fixture()
        val handshakeMillis = 5_000L
        val result = fixture.run(timeoutMillis = 60_000L, handshakeTimeoutMillis = handshakeMillis)

        assertEquals(Iap2WirelessControlTerminal.CHANNEL_CLOSED, result.terminal)
        assertEquals(Iap2WirelessControlStage.SUBSCRIBED, result.stage)
        assertHandshakeTimeouts(fixture, handshakeMillis)
        assertEquals(Iap2WiredControlClient.subscriptions().map { it.messageId }, fixture.sent.map { it.first })
        assertEquals(5, fixture.sent.size)
        assertEquals(1, fixture.readyCalls)
        assertEquals(listOf(Iap2BootstrapPhase.CSM_ESTABLISHED, Iap2BootstrapPhase.MFi_AUTHENTICATED), fixture.phases)
    }

    @Test fun structuredProgressIsPreservedThroughTransportAndSuccessfulStartSession() {
        val fixture = Fixture()
        fixture.onControlRecv = {
            when (fixture.controlRecvTimeouts.size) {
                1 -> Iap2Frame(0x4e0d, Iap2ParameterList.of(Iap2Parameter(0, byteArrayOf(1))).encode())
                2 -> Iap2Frame(0x4e0e, byteArrayOf())
                3 -> Iap2Frame(0x4300, byteArrayOf())
                else -> null
            }
        }
        fixture.run(timeoutMillis = 60_000L, handshakeTimeoutMillis = 5_000L)
        assertEquals(listOf(Iap2BootstrapPhase.CSM_ESTABLISHED, Iap2BootstrapPhase.MFi_AUTHENTICATED,
            Iap2BootstrapPhase.WIRELESS_CARPLAY_AVAILABLE, Iap2BootstrapPhase.TRANSPORT_IDENTIFIED,
            Iap2BootstrapPhase.START_SESSION_SENT), fixture.phases)
        assertEquals(0x4301, fixture.sent.last().first)
    }

    @Test fun startSessionGuardRunsBeforeWireWriteAndSuccessCallback() {
        val fixture = Fixture()
        val steps = mutableListOf<String>()
        fixture.onControlRecv = {
            if (fixture.controlRecvTimeouts.size == 1) Iap2Frame(0x4300, byteArrayOf()) else null
        }
        fixture.beforeStartSession = { steps += "guard" }
        fixture.onSend = { frame -> if (frame.messageId == 0x4301) steps += "wire" }
        fixture.onStartSessionSent = { steps += "sent" }

        fixture.run(timeoutMillis = 60_000L, handshakeTimeoutMillis = 5_000L)
        assertEquals(listOf("guard", "wire", "sent"), steps)
    }

    @Test fun authenticationAndSubscriptionsConsumeOneSharedHandshakeBudget() {
        val fixture = Fixture()
        fixture.onAuthentication = { Thread.sleep(40L) }
        fixture.run(timeoutMillis = 60_000L, handshakeTimeoutMillis = 5_000L)
        assertTrue("Subscriptions must not reset the handshake budget",
            fixture.sent.first().second <= fixture.authenticationTimeouts.single() - 20L)
    }

    @Test fun longerHandshakeBudgetCannotEnlargeShorterOverallDeadline() {
        val fixture = Fixture()
        val overallMillis = 2_000L
        val result = fixture.run(timeoutMillis = overallMillis, handshakeTimeoutMillis = 60_000L)

        assertEquals(Iap2WirelessControlTerminal.CHANNEL_CLOSED, result.terminal)
        assertHandshakeTimeouts(fixture, overallMillis)
        assertEquals(1, fixture.controlRecvTimeouts.size)
        assertTrue(fixture.controlRecvTimeouts.single() in 1..overallMillis)
    }

    @Test fun authenticatedControlPollAndSendUseLongLivedDeadlineInsteadOfHandshakeBudget() {
        val fixture = Fixture()
        val handshakeMillis = 5_000L
        fixture.onControlRecv = {
            if (fixture.controlRecvTimeouts.size == 1) Iap2Frame(0x5702, byteArrayOf()) else null
        }

        val result = fixture.run(timeoutMillis = 60_000L, handshakeTimeoutMillis = handshakeMillis)

        assertEquals(Iap2WirelessControlTerminal.CHANNEL_CLOSED, result.terminal)
        assertEquals(Iap2WirelessControlStage.WIFI_CONFIG_SENT, result.stage)
        assertEquals(1, result.wifiConfigurationsSent)
        assertEquals(2, fixture.controlRecvTimeouts.size)
        fixture.controlRecvTimeouts.forEach { timeout ->
            assertTrue("Control recv retained handshake deadline: $timeout", timeout > handshakeMillis)
        }
        val subscriptions = fixture.sent.dropLast(1)
        assertEquals(Iap2WiredControlClient.subscriptions().map { it.messageId }, subscriptions.map { it.first })
        subscriptions.forEach { assertTrue(it.second in 1..handshakeMillis) }
        assertEquals(0x5703, fixture.sent.last().first)
        assertTrue("Control send retained handshake deadline", fixture.sent.last().second > handshakeMillis)
    }

    @Test fun channelClosedAfterUnavailableUpdatePreservesUnavailableTerminalSnapshot() {
        val fixture = unavailableFixture(closed = true)

        val result = fixture.run(timeoutMillis = 60_000L, handshakeTimeoutMillis = 5_000L)

        assertUnavailableSnapshot(result, Iap2WirelessControlTerminal.CHANNEL_CLOSED)
        assertEquals(2, fixture.controlRecvTimeouts.size)
        verify(fixture.session).isClosed
    }

    @Test fun nullReceiveAtOverallTimeoutPreservesUnavailableTerminalSnapshot() {
        val fixture = unavailableFixture(closed = false)
        fixture.onControlRecv = { timeout ->
            if (fixture.controlRecvTimeouts.size == 1) {
                unavailableFrame()
            } else {
                // Model the blocking recv consuming its actual remaining budget, not a busy poll.
                // Expire inside recv so this covers the null/TIMED_OUT return, not the loop header.
                Thread.sleep(timeout + 10L)
                null
            }
        }

        val result = fixture.run(timeoutMillis = 2_000L, handshakeTimeoutMillis = 5_000L)

        assertUnavailableSnapshot(result, Iap2WirelessControlTerminal.TIMED_OUT)
        assertEquals(2, fixture.controlRecvTimeouts.size)
        verify(fixture.session).isClosed
    }

    private fun assertHandshakeTimeouts(fixture: Fixture, maximumMillis: Long) {
        assertEquals(1, fixture.readyTimeouts.size)
        assertEquals(1, fixture.identificationRecvTimeouts.size)
        assertEquals(1, fixture.authenticationTimeouts.size)
        assertEquals(5, fixture.sent.size)
        val timeouts = fixture.readyTimeouts + fixture.identificationRecvTimeouts +
            fixture.authenticationTimeouts + fixture.sent.map { it.second }
        timeouts.forEach { timeout ->
            assertTrue("Handshake call escaped $maximumMillis ms budget: $timeout", timeout in 1..maximumMillis)
        }
    }

    private fun unavailableFixture(closed: Boolean) = Fixture().apply {
        this.closed = closed
        onControlRecv = { if (controlRecvTimeouts.size == 1) unavailableFrame() else null }
    }

    private fun unavailableFrame() = Iap2Frame(
        0x4e0d,
        Iap2ParameterList.of(Iap2Parameter(0, byteArrayOf(0))).encode(),
    )

    private fun assertUnavailableSnapshot(result: Iap2WirelessControlResult, terminal: Iap2WirelessControlTerminal) {
        assertEquals(terminal, result.terminal)
        assertEquals(Iap2WirelessControlStage.SUBSCRIBED, result.stage)
        assertTrue(result.wirelessUnavailableReported)
        assertFalse(result.wirelessCarPlayAvailableSeen)
        assertFalse(result.transportNotificationSeen)
        assertEquals(0, result.forwardedFrames)
        assertEquals(0, result.wifiConfigurationsSent)
        assertEquals(0, result.postTransportWiFiConfigurationsSent)
        assertEquals(0, result.carPlayStartSessionsSent)
    }

    private class Fixture {
        var closed = true
        var readyCalls = 0
        val phases = mutableListOf<Iap2BootstrapPhase>()
        val readyTimeouts = mutableListOf<Long>()
        val identificationRecvTimeouts = mutableListOf<Long>()
        val authenticationTimeouts = mutableListOf<Long>()
        val controlRecvTimeouts = mutableListOf<Long>()
        val sent = mutableListOf<Pair<Int, Long>>()
        var onControlRecv: (Long) -> Iap2Frame? = { null }
        var onAuthentication: () -> Unit = {}
        var beforeStartSession: () -> Unit = {}
        var onStartSessionSent: () -> Unit = {}
        var onSend: (Iap2Frame) -> Unit = {}
        private var identificationAccepted = false

        // Default answers avoid null-valued Mockito argument matchers for Kotlin non-null parameters.
        // Only the transport and authentication are mocked; identification and control run for real.
        val session: Iap2Session = mock(Iap2Session::class.java) { invocation ->
            when (invocation.method.name) {
                "awaitReady" -> {
                    readyTimeouts += invocation.getArgument<Long>(0)
                    true
                }
                "recv" -> {
                    val timeout = invocation.getArgument<Long>(0)
                    if (!identificationAccepted) {
                        identificationRecvTimeouts += timeout
                        identificationAccepted = true
                        Iap2Frame(0x1d02, byteArrayOf())
                    } else {
                        controlRecvTimeouts += timeout
                        onControlRecv(timeout)
                    }
                }
                "send" -> {
                    val frame = invocation.getArgument<Iap2Frame>(0)
                    sent += frame.messageId to invocation.getArgument<Long>(1)
                    onSend(frame)
                    null
                }
                "isClosed" -> closed
                else -> RETURNS_DEFAULTS.answer(invocation)
            }
        }
        private val mfi: Iap2MfiAuthenticationClient = mock(Iap2MfiAuthenticationClient::class.java) { invocation ->
            if (invocation.method.name == "run") {
                assertSame(session, invocation.getArgument<Iap2Session>(0))
                authenticationTimeouts += invocation.getArgument<Long>(1)
                onAuthentication()
                null // Never invoke the actual authenticator or certificate reader.
            } else {
                RETURNS_DEFAULTS.answer(invocation)
            }
        }
        private val identification = Iap2IdentificationConfig(
            name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
            firmwareVersion = "1", hardwareVersion = "1",
            wireless = Iap2WirelessIdentification("AA:BB:CC:DD:EE:FF", "test-hotspot"),
        )
        private val endpoint = Iap2WirelessCarPlayEndpoint(
            ssid = "test-hotspot", passphrase = "secret123", channel = 36,
            security = Iap2WirelessSecurity.WPA_WPA2, ipAddresses = listOf("192.168.2.1"),
            airPlayPort = 7000, deviceIdentifier = "test-device", publicKey = "test-key", sourceVersion = "1.0",
        )

        fun run(timeoutMillis: Long, handshakeTimeoutMillis: Long?): Iap2WirelessControlResult =
            Iap2WirelessControlClient(session, mfi).run(
                identification = identification,
                endpoint = endpoint,
                timeoutMillis = timeoutMillis,
                handshakeTimeoutMillis = handshakeTimeoutMillis,
                onReady = { readyCalls++ },
                beforeStartSession = beforeStartSession,
                onStartSessionSent = { onStartSessionSent() },
                onBootstrapPhase = { phases += it },
            )
    }
}
