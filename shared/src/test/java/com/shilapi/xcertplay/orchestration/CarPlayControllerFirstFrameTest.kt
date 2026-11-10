package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.Socket
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayControllerFirstFrameTest {
    @Test fun sessionOwnedOutputReceivesWiredAndWirelessFramesWithoutTheHostListener() {
        for (wireless in listOf(false, true)) {
            val controller = controller(mutableListOf())
            val active = session()
            val stale = session()
            var updates = 0
            // Register before a current output exists, then replace it exactly as a session does.
            controller.setClusterOutputStateListener {}
            val old: () -> Unit = {}
            controller.setClusterOutputStateListener(old)
            controller.setClusterOutputStateListener { updates++ }
            controller.clearClusterOutputStateListener(old)
            updates = 0
            controller.attachUi(object : AirPlaySessionListener {}, {})
            controller.javaClass.getDeclaredField("activeSession").apply { isAccessible = true }.set(controller, active)
            val listener = if (wireless) {
                controller.javaClass.getDeclaredField("phase").apply {
                    isAccessible = true
                    set(controller, type.enumConstants.single { (it as Enum<*>).name == "WIRELESS" })
                }
                val watchdog = FirstTcpWatchdog(AirPlayListenerIdentity(0), { _, _ -> {} }, {})
                val runType = controller.javaClass.declaredClasses.single { it.simpleName == "WirelessRun" }
                val run = runType.getDeclaredConstructor(CarPlayController::class.java, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.newInstance(controller, 0)
                controller.javaClass.getDeclaredField("wirelessRun").apply { isAccessible = true }.set(controller, run)
                runType.getDeclaredMethod("wirelessSessionListener", Int::class.javaPrimitiveType, FirstTcpWatchdog::class.java)
                    .apply { isAccessible = true }.invoke(run, 0, watchdog) as AirPlaySessionListener
            } else controller.javaClass.getDeclaredField("sessionListener")
                .apply { isAccessible = true }.get(controller) as AirPlaySessionListener
            try {
                listener.onClusterControlReady(stale)
                assertEquals(0, updates)
                listener.onClusterControlReady(active)
                assertEquals(1, updates)
                updates = 0
                listener.onVideoFrameRendered(stale)
                assertEquals(0, updates)
                listener.onVideoFrameRendered(active)
                assertEquals(1, updates)
                listener.onVideoFrameRendered(active)
                assertEquals("Output only needs the first rendered main frame", 1, updates)
                listener.onSessionEnded(stale)
                assertEquals(1, updates)
                listener.onSessionEnded(active)
                assertEquals(2, updates)
                assertNull(controller.activeAirPlaySession())
            } finally {
                stale.close(); active.close(); controller.close()
                assertTrue(controller.awaitClosed(2_000))
            }
        }
    }

    @Test fun clusterReadyRechecksOwnerIntentAfterSetupAndEventBecomeReady() {
        val controller = controller(mutableListOf())
        val listener = controller.javaClass.getDeclaredField("sessionListener")
            .apply { isAccessible = true }.get(controller) as AirPlaySessionListener
        val active = AirPlaySession(Socket(), AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480),
            cluster = AirPlayDisplayConfig(1600, 600, initialUrl = CarPlayClusterDisplay.MAP_URL)),
            AirPlayIdentity.generate(), PairingStore(), null, listener,
            object : AirPlayMediaHandler { override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>) = 1 })
        val field = controller.javaClass.getDeclaredField("activeSession").apply { isAccessible = true }
        val executor = controller.javaClass.getDeclaredField("touchExecutor").apply { isAccessible = true }
            .get(controller) as ExecutorService
        fun await() { executor.submit {}.get(3, TimeUnit.SECONDS) }
        val bytes = ByteArrayOutputStream()
        val socket = object : Socket() {
            override fun getOutputStream(): OutputStream = bytes
        }
        fun ready() {
            active.javaClass.getDeclaredMethod("notifySetupResponseSent").apply { isAccessible = true }.invoke(active)
            await()
        }
        try {
            field.set(controller, active)
            var wanted = true
            controller.setClusterOutputStateListener { controller.setClusterOutputVisible(wanted) }
            await()
            active.javaClass.getDeclaredMethod("handleStreams", List::class.java).apply { isAccessible = true }
                .invoke(active, listOf(mapOf("type" to 111L)))
            ready() // Initial URL is already shown; do not send showUI.
            assertEquals(0, bytes.size())
            active.javaClass.getDeclaredField("eventSocket").apply { isAccessible = true }.set(active, socket)
            active.javaClass.getDeclaredField("eventCipher").apply { isAccessible = true }
                .set(active, ControlCipher(ByteArray(32), ByteArray(32)))
            wanted = false
            controller.setClusterOutputVisible(false)
            await()
            active.javaClass.getDeclaredField("eventSocket").apply { isAccessible = true }.set(active, null)
            wanted = true
            controller.setClusterOutputVisible(true) // Failed while the event channel was absent.
            await()
            active.javaClass.getDeclaredField("eventSocket").apply { isAccessible = true }.set(active, socket)
            ready()
            val plain = ControlCipher(ByteArray(32), ByteArray(32)).decrypt(bytes.toByteArray()).data
            val commands = RtspMessage.parseMessages(plain).messages.map { (BplistCodec.decode(it.body) as Map<*, *>)["type"] }
            assertEquals(listOf("stopUI", "showUI", "forceKeyFrame"), commands)
        } finally {
            active.close(); controller.close()
            assertTrue(controller.awaitClosed(2_000))
        }
    }

    @Test fun queuedOldSessionVisibilityCannotStopItsReplacement() {
        val controller = controller(mutableListOf())
        val old = session()
        val next = AirPlaySession(Socket(), AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480),
            cluster = AirPlayDisplayConfig(1600, 600, initialUrl = CarPlayClusterDisplay.MAP_URL)),
            AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {},
            object : AirPlayMediaHandler { override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>) = 1 })
        val field = controller.javaClass.getDeclaredField("activeSession").apply { isAccessible = true }
        val executor = controller.javaClass.getDeclaredField("touchExecutor").apply { isAccessible = true }
            .get(controller) as ExecutorService
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bytes = ByteArrayOutputStream()
        try {
            next.javaClass.getDeclaredMethod("handleStreams", List::class.java).apply { isAccessible = true }
                .invoke(next, listOf(mapOf("type" to 111L)))
            next.javaClass.getDeclaredField("eventSocket").apply { isAccessible = true }.set(next,
                object : Socket() { override fun getOutputStream(): OutputStream = bytes })
            next.javaClass.getDeclaredField("eventCipher").apply { isAccessible = true }
                .set(next, ControlCipher(ByteArray(32), ByteArray(32)))
            field.set(controller, old)
            executor.execute { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            controller.setClusterOutputVisible(false)
            field.set(controller, next)
            release.countDown()
            executor.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(0, bytes.size())
        } finally {
            release.countDown()
            old.close(); next.close(); controller.close()
            assertTrue(controller.awaitClosed(2_000))
        }
    }

    @Test fun wiredMainFrameReachesTheHostButRetiredSessionsDoNot() {
        val frames = mutableListOf<AirPlaySession>()
        val controller = controller(frames)
        val active = session()
        val stale = session()
        val listener = controller.javaClass.getDeclaredField("sessionListener")
            .apply { isAccessible = true }.get(controller) as AirPlaySessionListener
        controller.javaClass.getDeclaredField("activeSession").apply { isAccessible = true }.set(controller, active)
        try {
            listener.onVideoFrameRendered(stale)
            assertTrue(frames.isEmpty())
            listener.onVideoFrameRendered(active)
            assertEquals(listOf(active), frames)
            active.close()
            listener.onVideoFrameRendered(active)
            assertEquals(listOf(active), frames)
        } finally {
            stale.close()
            active.close()
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
        }
    }

    private fun controller(frames: MutableList<AirPlaySession>) = CarPlayController(
        RuntimeEnvironment.getApplication(),
        CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRED,
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3),
        ),
        AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity.generate(), PairingStore(),
        object : AirPlaySessionListener {
            override fun onVideoFrameRendered(session: AirPlaySession) { frames += session }
        },
        object : AirPlayMediaHandler {}, {},
    )

    private fun session() = AirPlaySession(
        Socket(), AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity.generate(), PairingStore(), null,
        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
    )
}
