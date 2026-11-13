package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.hud.NavigationOutputs
import java.net.Socket
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Live dashboard selections must not claim delivery before their commands reach the event channel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterContentTest {
    private fun session(initialUrl: String, listener: AirPlaySessionListener = object : AirPlaySessionListener {}, onTeardown: (AirPlaySession) -> Unit = {}): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
            sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            cluster = AirPlayDisplayConfig(widthPixels = 1600, heightPixels = 600, initialUrl = initialUrl)),
        identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
        listener = listener, media = object : AirPlayMediaHandler {
            override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int = 1
            override fun onTeardown(session: AirPlaySession, type: Int) { onTeardown(session) }
        },
    )

    private fun setup(session: AirPlaySession) {
        session.javaClass.getDeclaredMethod("handleStreams", List::class.java).apply { isAccessible = true }
            .invoke(session, listOf(mapOf("type" to 111L)))
    }

    private fun teardown(session: AirPlaySession) {
        val request = RtspMessage.Request("TEARDOWN", "*", "RTSP/1.0", emptyMap(),
            BplistCodec.encode(mapOf("streams" to listOf(mapOf("type" to 111L)))))
        session.javaClass.getDeclaredMethod("handleTeardown", RtspMessage.Request::class.java).apply { isAccessible = true }
            .invoke(session, request)
    }

    @Test fun setupReadyNotifiesSessionOwnerAfterClusterIsAvailable() {
        val available = mutableListOf<Int>()
        val session = session(CarPlayClusterDisplay.MAP_URL, listener = object : AirPlaySessionListener {
            override fun onClusterControlReady(session: AirPlaySession) { available += session.clusterStream }
        })
        try {
            setup(session)
            readyAfterSetup(session)
            assertEquals(listOf(1), available)
        } finally { session.close() }
    }

    @Test fun aFailedShowUiMustNotClaimThatATurnCardHasBecomeAMap() {
        val initial = CarPlayClusterDisplay.Content.TURN_CARD.url
        val session = session(initial)
        try {
            setup(session)
            assertFalse(session.setClusterUrl(CarPlayClusterDisplay.MAP_URL, send = true)) // No event connection.
            assertEquals("No command was delivered, so map zoom must not become eligible", initial, session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun aRecreatedStreamMustNotReportThePreviousStreamsUrlAsAlreadyShown() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            setup(session)
            assertTrue(session.setClusterUrl(CarPlayClusterDisplay.Content.TURN_CARD.url, send = false))
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
            teardown(session)
            assertEquals(0, session.clusterStream)
            setup(session)
            assertEquals(2, session.clusterStream)
            assertEquals("A new cluster stream starts at its configured initialURL", CarPlayClusterDisplay.MAP_URL,
                session.clusterUrl())
        } finally { session.close() }
    }

    private class EventSocket(private val onWrite: (() -> Unit)? = null) : Socket() {
        val bytes = ByteArrayOutputStream()
        var writes = 0
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) { bytes.write(value) }
            override fun write(data: ByteArray, offset: Int, length: Int) {
                bytes.write(data, offset, length)
                writes++
                if (writes == 1) onWrite?.invoke()
            }
        }
    }

    private fun eventReady(session: AirPlaySession, event: EventSocket = EventSocket()): EventSocket {
        for ((name, value) in listOf("eventSocket" to event, "eventCipher" to ControlCipher(ByteArray(32), ByteArray(32)))) {
            session.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }
        return event
    }

    private fun readyAfterSetup(session: AirPlaySession) {
        session.javaClass.getDeclaredMethod("notifySetupResponseSent").apply { isAccessible = true }.invoke(session)
    }

    private fun commands(event: EventSocket): List<Map<String, Any?>> {
        val plaintext = ControlCipher(ByteArray(32), ByteArray(32)).use { it.decrypt(event.bytes.toByteArray()).data }
        return RtspMessage.parseMessages(plaintext).messages.map { BplistCodec.decode(it.body) as Map<String, Any?> }
    }

    @Test fun navigationExitClaimsThenReleasesTurnByTurnWithoutChangingDisplayContent() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            setup(session)
            val event = eventReady(session)
            assertTrue(session.requestNavigationExit())
            assertTrue(session.requestNavigationExit())
            assertEquals(1, commands(event).size)
            fun modes(entity: Int) {
                val request = RtspMessage.Request("POST", "/command", "RTSP/1.0", emptyMap(),
                    BplistCodec.encode(mapOf("type" to "modesChanged", "params" to mapOf(
                        "appStates" to listOf(mapOf("appStateID" to 3, "entity" to entity))))))
                session.javaClass.getDeclaredMethod("handleCommand", RtspMessage.Request::class.java)
                    .apply { isAccessible = true }.invoke(session, request)
            }
            modes(1)
            assertEquals(1, commands(event).size)
            modes(2)
            val sent = commands(event)
            assertEquals(listOf("changeModes", "changeModes"), sent.map { it["type"] })
            val states = sent.map { ((it["params"] as Map<*, *>)["appStates"] as List<*>).single() as Map<*, *> }
            assertEquals(listOf(true, false), states.map { it["state"] })
            assertTrue(states.all { it["appStateID"] == 3L && !it.containsKey("entity") })
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun navigationExitReportCorrelatesWritesArbitrationAndLateTerminal() {
        val lines = mutableListOf<String>()
        NavigationOutputs.attachReport { lines.add(it) }
        NavigationOutputs.onSessionActive { true }
        val id = NavigationOutputs.navigationExitAction()
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            eventReady(session)
            assertTrue(session.requestNavigationExit(id))
            session.javaClass.getDeclaredMethod("releaseNavigationExit", String::class.java)
                .apply { isAccessible = true }.invoke(session, "timeout")
            val modes = RtspMessage.Request("POST", "/command", "RTSP/1.0", emptyMap(),
                BplistCodec.encode(mapOf("type" to "modesChanged", "params" to mapOf(
                    "appStates" to listOf(mapOf("appStateID" to 3, "entity" to 2))))))
            session.javaClass.getDeclaredMethod("handleCommand", RtspMessage.Request::class.java)
                .apply { isAccessible = true }.invoke(session, modes)
            NavigationOutputs.navigationExitTerminal(0)
            NavigationOutputs.navigationExitTerminal(2)
            NavigationOutputs.diagnostic("exit cleanup accepted=true")
            NavigationOutputs.navigationExitFinished("phone-end")
            val events = lines.filter { it.contains("request=$id ") }
            assertTrue(events.any { it.contains("action=2") })
            assertTrue(events.any { it.contains("claim written=true") })
            assertTrue(events.any { it.contains("release reason=timeout written=true") })
            assertTrue(events.any { it.contains("modesChanged entity=2") })
            assertEquals(1, events.count { it.contains("phoneState=") })
            assertTrue(events.any { it.contains("phoneState=0") && it.contains("requestAgeMs=") })
            assertTrue(events.any { it.contains("cleanup accepted=true") })
            assertTrue(events.any { it.contains("finished reason=phone-end terminalSeen=true") })
        } finally { session.close() }
    }

    @Test fun duplicateExitActionReportsCoalescedClaim() {
        val lines = mutableListOf<String>()
        NavigationOutputs.attachReport { lines.add(it) }
        NavigationOutputs.onSessionActive { true }
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            val event = eventReady(session)
            val first = NavigationOutputs.navigationExitAction()
            assertTrue(session.requestNavigationExit(first))
            val second = NavigationOutputs.navigationExitAction()
            assertTrue(session.requestNavigationExit(second))
            assertEquals(1, commands(event).size)
            assertTrue(lines.any { it.contains("request=$first ") && it.contains("claim written=true") })
            assertTrue(lines.any { it.contains("request=$second ") && it.contains("claim coalesced withRequest=$first") })
            assertFalse(lines.any { it.contains("request=$second ") && it.contains("claim written=true") })
        } finally {
            session.close()
            NavigationOutputs.navigationExitFinished("test")
        }
    }

    @Test fun oldSessionCleanupCannotFinishOrMisattributeNewRequest() {
        val lines = mutableListOf<String>()
        NavigationOutputs.attachReport { lines.add(it) }
        NavigationOutputs.onSessionActive { true }
        val old = NavigationOutputs.navigationExitAction()
        val cleanup = NavigationOutputs.scopedClear {
            NavigationOutputs.diagnostic("cleanup idle=failed")
            NavigationOutputs.navigationExitFinished("old-cleanup")
        }
        NavigationOutputs.onSessionActive { true }
        val current = NavigationOutputs.navigationExitAction()
        cleanup()
        NavigationOutputs.navigationExitTerminal(0)
        NavigationOutputs.navigationExitFinished("new-cleanup")
        assertTrue(lines.any { it.contains("request=$old ") && it.contains("cleanup idle=failed") })
        assertFalse(lines.any { it.contains("request=$current ") && it.contains("cleanup idle=failed") })
        assertTrue(lines.any { it.contains("request=$current ") && it.contains("phoneState=0") })
        assertTrue(lines.any { it.contains("request=$current ") && it.contains("finished reason=new-cleanup") })
    }

    @Test fun navigationExitWithoutAnAcknowledgmentReleasesOnTimeout() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            val event = eventReady(session)
            assertTrue(session.requestNavigationExit())
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(7)
            while (commands(event).size < 2 && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals(2, commands(event).size)
        } finally { session.close() }
    }

    @Test fun aSuccessfulSwitchPublishesTheDeliveredUrlAndInvalidatesTheOldRoute() {
        val session = session(CarPlayClusterDisplay.Content.TURN_CARD.url)
        try {
            setup(session)
            val before = session.clusterContentRoute()!!.third
            val event = eventReady(session)
            assertTrue(session.setClusterUrl(CarPlayClusterDisplay.MAP_URL, send = true))
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
            assertNotSame(before, session.clusterContentRoute()!!.third)
            val commands = commands(event)
            assertEquals(listOf("showUI", "forceKeyFrame"), commands.map { it["type"] })
            assertEquals(CarPlayClusterDisplay.MAP_URL, (commands.first()["params"] as Map<*, *>)["url"])
        } finally { session.close() }
    }

    @Test fun aPausedSelectionStaysPendingUntilResume() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            setup(session)
            val event = eventReady(session)
            assertTrue(session.setClusterUiShown(false))
            assertTrue(session.setClusterUrl(CarPlayClusterDisplay.Content.TURN_CARD.url, send = false))
            readyAfterSetup(session) // A ready-channel notification must not undo a pause.
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
            assertEquals(1, event.writes)
            assertTrue(session.setClusterUiShown(true))
            assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, session.clusterUrl())
            assertEquals(listOf("stopUI", "showUI", "forceKeyFrame"), commands(event).map { it["type"] })
        } finally { session.close() }
    }

    @Test fun anUnavailableEventChannelKeepsTheOldUrlUntilRetryActuallyDelivers() {
        val session = session(CarPlayClusterDisplay.Content.TURN_CARD.url)
        try {
            setup(session)
            assertFalse(session.setClusterUrl(CarPlayClusterDisplay.MAP_URL, send = true))
            assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, session.clusterUrl())
            eventReady(session)
            readyAfterSetup(session)
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun aRecreatedStreamReappliesTheSelectionAfterSetupWithoutClaimingItEarly() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            setup(session)
            eventReady(session)
            assertTrue(session.setClusterUrl(CarPlayClusterDisplay.Content.TURN_CARD.url, send = true))
            teardown(session)
            assertNull(session.clusterContentRoute())
            setup(session)
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
            readyAfterSetup(session)
            assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun aStreamReplacementDuringDeliveryCannotPublishTheOldResultForTheNewStream() {
        val session = session(CarPlayClusterDisplay.Content.TURN_CARD.url)
        try {
            setup(session)
            eventReady(session, EventSocket { setup(session) })
            assertFalse(session.setClusterUrl(CarPlayClusterDisplay.MAP_URL, send = true))
            assertEquals(2, session.clusterStream)
            assertNull(session.clusterUrl()) // In-flight showUI may have reached either generation.
            readyAfterSetup(session)
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun aClosedSessionHasNoContentRouteAndCannotAcceptSelections() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        setup(session)
        val event = eventReady(session)
        session.close()
        assertEquals(0, session.clusterStream)
        assertNull(session.clusterContentRoute())
        assertFalse(session.setClusterUrl(CarPlayClusterDisplay.Content.TURN_CARD.url, send = false))
        assertEquals(0, event.writes)
    }

    @Test fun aStopUiThatOutlastsStreamReplacementInvalidatesTheNewRoute() {
        val session = session(CarPlayClusterDisplay.MAP_URL)
        try {
            setup(session)
            val event = eventReady(session, EventSocket { setup(session) })
            assertFalse(session.setClusterUiShown(false))
            assertEquals(2, session.clusterStream)
            assertNull(session.clusterUrl())
            readyAfterSetup(session)
            assertEquals(1, event.writes) // An uncertain stop cannot be silently undone by a ready event.
            assertTrue(session.setClusterUiShown(true))
            assertEquals(CarPlayClusterDisplay.MAP_URL, session.clusterUrl())
        } finally { session.close() }
    }

    @Test fun teardownInvalidatesTheRouteBeforeAnUnsuccessfulMediaCallback() {
        var checked = false
        val session = session(CarPlayClusterDisplay.MAP_URL) {
            assertEquals(0, it.clusterStream)
            assertNull(it.clusterContentRoute())
            checked = true
            throw java.io.IOException("media teardown failed")
        }
        try {
            setup(session)
            val failure = assertThrows(java.lang.reflect.InvocationTargetException::class.java) { teardown(session) }
            assertTrue(failure.cause is java.io.IOException)
            assertTrue(checked)
            assertNull(session.clusterContentRoute())
        } finally { session.close() }
    }
}
