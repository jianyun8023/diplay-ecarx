package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    private val controller = CarPlayController(
        RuntimeEnvironment.getApplication(),
        CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3),
        ),
        AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {},
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
        assertFalse((field(old, "wirelessAddressFamilyReachable") as AtomicBoolean).get())
        assertFalse((field(current, "wirelessAddressFamilyReachable") as AtomicBoolean).get())
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
        assertFalse((field(run, "wirelessAddressFamilyReachable") as AtomicBoolean).get())
        assertEquals(WirelessRunState.Lifecycle.FAILED, (field(run, "state") as WirelessRunState).lifecycle)
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
        assertFalse((field(run, "wirelessAddressFamilyReachable") as AtomicBoolean).get())
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

    private fun run(generation: Int): Any {
        val run = runType.getDeclaredConstructor(CarPlayController::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(controller, generation)
        field(controller, "wirelessRun", run)
        (field(controller, "wirelessGeneration") as AtomicInteger).set(generation)
        controller.javaClass.getDeclaredField("phase").apply {
            isAccessible = true
            set(controller, requireNotNull(type.enumConstants).single { (it as Enum<*>).name == "WIRELESS" })
        }
        return run
    }

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
