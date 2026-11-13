package com.shihab.diplay.orchestration

import com.shihab.diplay.airplay.*
import com.shihab.diplay.network.CarPlayVpnService
import com.shihab.diplay.network.WirelessHotspotInfo
import com.shihab.diplay.network.WirelessHotspotManager
import com.shihab.diplay.transport.BlockingDuplexByteStream
import com.shihab.diplay.transport.Iap2IdentificationConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.Config

import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)

class WirelessRunOwnershipTest {
    private var handlerClears = 0
    private val statuses = mutableListOf<CarPlayStatus>()
    private val listenerEvents = mutableListOf<String>()
    private val controller = CarPlayController(
        RuntimeEnvironment.getApplication(),
        CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3),
        ),
        AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {
            override val debugLogsEnabled = true
            override fun onDebugLog(message: String) { listenerEvents += message }
        },
        object : AirPlayMediaHandler {
            override fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) {
                if (handler == null) handlerClears++
            }
        }, { statuses += it },
    )
    private val runType = controller.javaClass.declaredClasses.single { it.simpleName == "WirelessRun" }

    @After fun close() {
        controller.close()
        assertTrue(controller.awaitClosed(2_000))
    }

    @Test fun retiredRunClosesOnlyItsOwnResourcesAndOnlyOnce() {
        val old = run(1)
        var oldCloses = 0
        var newCloses = 0
        field(old, "hotspot", hotspot { oldCloses++ })
        val current = run(2)
        field(current, "hotspot", hotspot { newCloses++ })
        closeRun(old)
        closeRun(old)
        assertEquals(1, oldCloses)
        assertEquals(0, newCloses)
        assertEquals("Old teardown must not remove the new tunnel handler", 0, handlerClears)
        closeRun(current)
        assertEquals(1, newCloses)
        assertEquals(1, handlerClears)
    }

    @Test fun retiredTunnelReadyCannotMarkTheNewAddressReachableOrReportStatus() {
        val old = run(1)
        val current = run(2)
        runType.getDeclaredMethod("onWirelessTunnelReady", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(old, 1)
        assertFalse((field(old, "state") as WirelessRunState).addressFamilyReachable)
        assertFalse((field(current, "state") as WirelessRunState).addressFamilyReachable)
        runType.getDeclaredMethod("onStatus", CarPlayStatus::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(old, CarPlayStatus.WirelessActive, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.isEmpty())
    }

    @Test fun retiredHandlerRejectsTunnelBeforeReadingOrOpeningIt() {
        val old = run(1)
        run(2)
        val stream = object : BlockingDuplexByteStream {
            override fun send(data: ByteArray) { fail("stale stream must not be written") }
            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = error("stale stream must not be read")
            override fun close() { fail("Rejected stream remains owned by the caller") }
        }
        assertEquals(false, runType.getDeclaredMethod("startWirelessTunnelControl", BlockingDuplexByteStream::class.java)
            .apply { isAccessible = true }.invoke(old, stream))
    }

    @Test fun retiredListenerDropsCallbacksAndCannotReactivateAnOldSession() {
        val old = run(1)
        val watchdog = FirstTcpWatchdog(AirPlayListenerIdentity(1), { _, _ -> {} }, {})
        val listener = runType.getDeclaredMethod("wirelessSessionListener",
            Int::class.javaPrimitiveType, FirstTcpWatchdog::class.java)
            .apply { isAccessible = true }.invoke(old, 1, watchdog) as AirPlaySessionListener
        assertTrue(listener.wantsTrace)
        assertTrue(listener.debugLogsEnabled)
        run(2)
        listenerEvents.clear()
        val session = AirPlaySession(java.net.Socket(),
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        try {
            listener.onSessionConnecting(session)
            listener.onSessionActive(session)
            listener.onCommand(session, "disableBluetooth", emptyMap())
            listener.onVideoFrameRendered(session)
            listener.onCornerMask(session, byteArrayOf(1), 1)
            listener.onTransportError("old transport closed")
            listener.onDebugLog("old debug log")
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(controller.activeAirPlaySession())
            assertNull(controller.activeIphoneCornerMask())
            assertTrue(statuses.isEmpty())
            assertTrue(listenerEvents.isEmpty())
            assertFalse(listener.wantsTrace)
            assertFalse(listener.debugLogsEnabled)
        } finally { session.close() }
    }

    @Test fun failureDropsQueuedProgressAndPreventsLateTunnelReadinessInTheSameGeneration() {
        val run = run(1)
        runType.getDeclaredMethod("onStatus", CarPlayStatus::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(run, CarPlayStatus.ConnectingBluetooth, 1)
        controller.javaClass.getDeclaredMethod("fail", Throwable::class.java, Int::class.javaObjectType)
            .apply { isAccessible = true }.invoke(controller, java.io.IOException("radio lost"), 1)
        runType.getDeclaredMethod("onWirelessTunnelReady", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(run, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, statuses.size)
        assertTrue(statuses.single() is CarPlayStatus.Failed)
        assertFalse((field(run, "state") as WirelessRunState).addressFamilyReachable)
        assertEquals(WirelessRunState.Lifecycle.RETIRED, (field(run, "state") as WirelessRunState).lifecycle)
    }

    @Test fun closedRunCannotPublishProgressOrReactivateItsTunnel() {
        val run = run(1)
        closeRun(run)
        runType.getDeclaredMethod("onStatus", CarPlayStatus::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(run, CarPlayStatus.RunningWireless, 1)
        runType.getDeclaredMethod("onWirelessTunnelReady", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(run, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.isEmpty())
        assertFalse((field(run, "state") as WirelessRunState).addressFamilyReachable)
        assertEquals(WirelessRunState.Lifecycle.CLOSED, (field(run, "state") as WirelessRunState).lifecycle)
    }

    @Test fun prerequisiteFailuresBeforeTheFirstRunStillReportOnlyOnce() {
        val fail = controller.javaClass.getDeclaredMethod("fail", Throwable::class.java, Int::class.javaObjectType)
            .apply { isAccessible = true }
        fail.invoke(controller, java.io.IOException("MFi unavailable"), 0)
        fail.invoke(controller, java.io.IOException("late cleanup failure"), 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("MFi unavailable", (statuses.single() as CarPlayStatus.Failed).message)
    }

    @Test fun vpnDisconnectAfterRetirementStopsTheQueuedRestart() = assertVpnDisconnectDuringRestart("retired")
    @Test fun vpnDisconnectDuringCloseStopsTheQueuedRestart() = assertVpnDisconnectDuringRestart("closing")
    @Test fun vpnDisconnectAfterCloseStopsTheQueuedRestart() = assertVpnDisconnectDuringRestart("closed")

    private fun assertVpnDisconnectDuringRestart(lifecycle: String) {
        val connection = field(controller, "serviceConnection") as android.content.ServiceConnection
        val old = run(1)
        val state = field(old, "state") as WirelessRunState
        runType.getDeclaredMethod("retire").apply { isAccessible = true }.invoke(old)
        val restartGeneration = lifecycle().beginWireless()
        if (lifecycle != "retired") state.beginClose()
        if (lifecycle == "closed") state.finishClose()

        connection.onServiceDisconnected(android.content.ComponentName("test", "VPN"))
        connection.onServiceDisconnected(android.content.ComponentName("test", "VPN"))
        controller.javaClass.getDeclaredMethod("startWireless", Int::class.javaObjectType)
            .apply { isAccessible = true }.invoke(controller, restartGeneration)
        shadowOf(Looper.getMainLooper()).idle()

        assertSame("A controller failure must stop the queued restart", old, field(controller, "wirelessRun"))
        assertEquals("CarPlay VPN service disconnected", (statuses.single() as CarPlayStatus.Failed).message)
    }

    @Test fun vpnDisconnectAfterAnAlreadyReportedFailureDoesNotReportAgain() {
        val run = run(1)
        controller.javaClass.getDeclaredMethod("fail", Throwable::class.java, Int::class.javaObjectType)
            .apply { isAccessible = true }.invoke(controller, java.io.IOException("radio lost"), 1)
        closeRun(run)
        (field(controller, "serviceConnection") as android.content.ServiceConnection)
            .onServiceDisconnected(android.content.ComponentName("test", "VPN"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("radio lost", (statuses.single() as CarPlayStatus.Failed).message)
    }

    @Test fun reconnectMfiRetiresTheLiveWirelessRunBeforeDiscoveryCompletes() {
        val executor = field(controller, "executor") as java.util.concurrent.ExecutorService
        val release = java.util.concurrent.CountDownLatch(1)
        // Hold the single worker: retirement must not depend on MFi discovery succeeding or failing.
        executor.execute { release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        val old = run(1)
        val state = field(old, "state") as WirelessRunState
        try {
            controller.reconnectMfi()
            assertEquals(WirelessRunState.Lifecycle.RETIRED, state.lifecycle)
            assertFalse(state.failedOnce)
            runType.getDeclaredMethod("onStatus", CarPlayStatus::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(old, CarPlayStatus.WirelessActive, 1)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(statuses.none { it is CarPlayStatus.WirelessActive })
            assertTrue(statuses.any { it is CarPlayStatus.DiscoveringMfi })
        } finally { release.countDown() }
    }

    private fun run(generation: Int): Any {
        field(controller, "wirelessRun")?.let {
            runType.getDeclaredMethod("retire").apply { isAccessible = true }.invoke(it)
        }
        assertEquals(generation, lifecycle().beginWireless())
        val run = runType.getDeclaredConstructor(CarPlayController::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(controller, generation)
        field(controller, "wirelessRun", run)
        return run
    }

    private fun lifecycle(): ControllerLifecycleCore = field(controller, "lifecycle") as ControllerLifecycleCore

    private fun closeRun(run: Any) = runType.getDeclaredMethod(
        "closeWirelessStack", CarPlayVpnService::class.java, Int::class.javaObjectType,
    ).apply { isAccessible = true }.invoke(run, null, null)

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun field(owner: Any, name: String, value: Any) =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)

    private fun hotspot(onClose: () -> Unit) = object : WirelessHotspotManager {
        override fun start(timeoutMillis: Long): WirelessHotspotInfo = error("not started by this test")
        override fun close() = onClose()
    }
}
