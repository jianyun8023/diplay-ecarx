package com.shilapi.xcertplay.hud

import android.content.Context
import android.hardware.display.DisplayManager
import com.ecarx.xui.adaptapi.diminteraction.DimInteraction
import com.ecarx.xui.adaptapi.diminteraction.FixtureLog
import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EcarxLaneGuidanceFlowTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val reports = CopyOnWriteArrayList<String>()
    private val panelRequests = CopyOnWriteArrayList<Boolean>()
    private var nowNs = 1_000_000_000L
    private lateinit var route: RouteGuidanceState
    private var originalClock: Any? = null

    private fun field(name: String) = EcarxHudBridge.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any?) = field(name).set(EcarxHudBridge, value)
    private fun tick() = EcarxHudBridge.javaClass.getDeclaredMethod("tick").apply { isAccessible = true }
        .invoke(EcarxHudBridge)
    private fun send(id: Int, body: Iap2BodyBuilder.() -> Unit) =
        EcarxHudBridge.onFrame(Iap2Messages.buildRaw(id, body))
    private fun maneuver(index: Int = 7, type: Int = 2, road: String = "滨海路") = send(0x5202) {
        u16(1, index); u8(3, type); string(4, road); u8(8, 0)
    }
    private fun guidance(distance: Int = 350, remaining: Int = 9_876, eta: Long = 1_800_000_000) = send(0x5201) {
        u8(1, 1); u32(0x0a, distance); u32(7, remaining); u64(5, eta); u16List(0x0d, listOf(7))
    }
    private fun initialize() = EcarxHudBridge.initialize(context)
    private fun names() = FixtureLog.calls().map { it.method }
    private fun calls(method: String) = FixtureLog.calls().filter { it.method == method }

    @Before fun setup() {
        NavigationOutputs.endNow()
        awaitOutputWorker()
        EcarxHudBridge.clear()
        DimInteraction.reset()
        for (name in listOf("context", "dim", "navi", "protocol", "dimCallback", "naviCallback",
            "lastPushed", "lastArrowChannel", "presentedPackage", "lanes", "lastLanePushed")) set(name, null)
        for (name in listOf("available", "presentationWanted", "guidanceActive")) set(name, false)
        for (name in listOf("presentationCountdown", "presentationAttempts", "ticksSincePush")) set(name, 0)
        // Only the scheduler is suppressed: tests explicitly run the production tick below.
        set("tickerStarted", true)
        route = field("route").get(EcarxHudBridge) as RouteGuidanceState
        route.clear()
        val clock = route.javaClass.getDeclaredField("nanoTime").apply { isAccessible = true }
        originalClock = clock.get(route)
        clock.set(route, { nowNs })
        context.getSharedPreferences("diplay_ecarx_outputs_v2", Context.MODE_PRIVATE).edit().clear().commit()
        EcarxOutputSettings.setMediaEnabled(context, false)
        NavigationOutputs.setClusterPanelOwner { panelRequests += it }
        EcarxHudBridge.attachReport { reports += it }
        reports.clear()
        panelRequests.clear()
    }

    @After fun cleanup() {
        NavigationOutputs.endNow()
        awaitOutputWorker()
        EcarxHudBridge.clear()
        NavigationOutputs.setClusterPanelOwner(null)
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        manager.unregisterDisplayListener(field("displayWatch").get(EcarxHudBridge) as DisplayManager.DisplayListener)
        set("displayWatchRegistered", false)
        route.javaClass.getDeclaredField("nanoTime").apply { isAccessible = true }.set(route, originalClock)
        set("context", null)
        set("tickerStarted", false)
        EcarxHudBridge.attachReport {}
        DimInteraction.reset()
    }

    @Test fun laneFramesReachTypedDimArrayThroughTheProductionWorkerAndSwitch() {
        val frame = Iap2Frame(0x5204, hex(
            "00 06 00 00 00 2a 00 06 00 01 00 00" +
                " 00 15 00 02 00 06 00 00 00 00 00 05 00 01 00 00 06 00 02 ff a6" +
                " 00 15 00 02 00 06 00 00 00 01 00 05 00 01 00 00 06 00 02 00 00" +
                " 00 1b 00 02 00 06 00 00 00 02 00 05 00 01 02 00 06 00 02 00 00" +
                " 00 06 00 03 00 5a",
        ))
        NavigationOutputs.start(context)
        awaitOutputWorker()
        NavigationOutputs.onFrame(Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 2) })
        NavigationOutputs.onFrame(Iap2Messages.buildRaw(0x5201) {
            u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 100)
        })
        NavigationOutputs.onFrame(frame)
        awaitOutputWorker()
        assertTrue(calls("updateLaneInfo").isEmpty())

        EcarxOutputSettings.setLaneGuidanceEnabled(context, true)
        NavigationOutputs.ecarxChanged(context)
        awaitOutputWorker()
        NavigationOutputs.onFrame(frame)
        awaitOutputWorker()
        assertEquals(listOf(
            mapOf("number" to 1, "iconId" to 1, "bitmap" to null),
            mapOf("number" to 2, "iconId" to 0, "bitmap" to null),
            mapOf("number" to 3, "iconId" to 15, "bitmap" to null),
        ), calls("updateLaneInfo").single().payload["lanes"])
        assertEquals(EcarxCapabilities.LANE_INFO, calls("updateLaneInfo").single().payload["componentType"])
        NavigationOutputs.onFrame(frame)
        NavigationOutputs.onFrame(Iap2Frame(0x5204, byteArrayOf(0, 1, 2)))
        awaitOutputWorker()
        assertEquals(1, calls("updateLaneInfo").size)

        NavigationOutputs.onFrame(Iap2Messages.buildRaw(0x5204) {
            u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 0); i16(2, 135) }
        })
        awaitOutputWorker()
        assertNull(calls("updateLaneInfo").last().payload["lanes"])
        NavigationOutputs.onFrame(frame)
        awaitOutputWorker()
        assertEquals(3, calls("updateLaneInfo").size)
        NavigationOutputs.onFrame(Iap2Frame(0x5204, byteArrayOf()))
        awaitOutputWorker()
        assertNull(calls("updateLaneInfo").last().payload["lanes"])
        NavigationOutputs.onFrame(frame)
        awaitOutputWorker()
        EcarxOutputSettings.setLaneGuidanceEnabled(context, false)
        NavigationOutputs.ecarxChanged(context)
        awaitOutputWorker()
        assertNull(calls("updateLaneInfo").last().payload["lanes"])
    }

    @Test fun laneStateClearsOnEndExpiryAndDisconnectWithoutReviving() {
        EcarxOutputSettings.setLaneGuidanceEnabled(context, true)
        initialize(); maneuver(); guidance()
        fun lane() = send(0x5204) {
            u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 2); i16(2, 90) }
        }
        lane()
        assertEquals(18, (calls("updateLaneInfo").last().payload["lanes"] as List<*>).let {
            (it.single() as Map<*, *>)["iconId"]
        })
        send(0x5201) { u8(1, 2) }
        assertNull(calls("updateLaneInfo").last().payload["lanes"])
        val afterEnd = calls("updateLaneInfo").size
        lane()
        assertEquals(afterEnd, calls("updateLaneInfo").size)

        maneuver(); guidance(); lane()
        nowNs += 30_000_000_000L
        tick()
        assertNull(calls("updateLaneInfo").last().payload["lanes"])
        val afterExpiry = calls("updateLaneInfo").size
        maneuver(); guidance()
        assertEquals(afterExpiry, calls("updateLaneInfo").size)
        lane()
        EcarxHudBridge.clear()
        assertNull(calls("updateLaneInfo").last().payload["lanes"])
    }

    @Test fun laneWithNoPushedManeuverClearsWithoutSendingNavigationStartOrStop() {
        EcarxOutputSettings.setLaneGuidanceEnabled(context, true)
        initialize(); maneuver()
        FixtureLog.failOnce("updateNavigationInfo")
        guidance()
        send(0x5204) {
            u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 0); i16(2, 0) }
        }
        assertTrue(calls("updateLaneInfo").isEmpty())
        FixtureLog.reset()
        send(0x5201) { u8(1, 2) }
        assertEquals(listOf("updateLaneInfo"), names())
        assertNull(calls("updateLaneInfo").single().payload["lanes"])
        assertTrue(panelRequests.isEmpty())
    }

    @Test fun rejectedLaneWriteIsReportedAndRetriedOnExistingTicker() {
        EcarxOutputSettings.setLaneGuidanceEnabled(context, true)
        initialize(); maneuver(); guidance()
        FixtureLog.failOnce("updateLaneInfo")
        send(0x5204) {
            u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 2); i16(2, 90) }
        }
        assertEquals(1, calls("updateLaneInfo").size)
        assertTrue(reports.any { "DIM lane updateLaneInfo(1) threw" in it })
        repeat(5) { tick() }
        assertEquals(2, calls("updateLaneInfo").size)
        assertEquals(18, ((calls("updateLaneInfo").last().payload["lanes"] as List<*>).single() as Map<*, *>)["iconId"])
    }

    private fun hex(value: String): ByteArray =
        value.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    private fun awaitOutputWorker() {
        val worker = NavigationOutputs.javaClass.getDeclaredField("ecarx").apply { isAccessible = true }.get(NavigationOutputs)
        val executor = worker.javaClass.getDeclaredField("executor").apply { isAccessible = true }.get(worker) as ThreadPoolExecutor
        executor.submit {}.get(3, TimeUnit.SECONDS)
    }
}
