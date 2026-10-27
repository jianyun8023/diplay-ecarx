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
class EcarxHudBridgeFlowTest {
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
        for (name in listOf("available", "presentationWanted", "guidanceActive", "panelRequested")) set(name, false)
        set("phoneNavigationActive", null)
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

    @Test fun iap2RouteStartsUpdatesAndArrivesThroughRealDimReflection() {
        initialize()
        assertEquals(listOf("create", "getNaviInteraction", "registerInteractionCallback",
            "registerNavigationInteractionCallback"), names())
        FixtureLog.reset()
        maneuver()
        assertTrue(FixtureLog.calls().isEmpty())
        guidance()
        assertEquals(listOf("updateNavigationInfo", "notifyTurnByTurnStarted",
            "updateDistanceToNextGuidancePoint", "updateDistanceToDestination", "updateETA",
            "updateNextGuidancePointName", "updateTurnByTurnArrow(int)"), names())
        assertTrue(EcarxHudBridge.isActive())
        assertEquals(listOf(true), panelRequests.toList())
        val first = calls("updateNavigationInfo").single().payload
        assertEquals(103, first["getNavigationTurnId"])
        assertEquals(350L, first["getDistanceToNextGuidancePoint"])
        assertEquals(9_876L, first["getDistanceToDestination"])
        assertEquals(1_800_000_000L, first["getETA"])
        assertEquals("滨海路", first["getNextGuidancePointName"])
        assertEquals(1, first["getNavigationStatus"])
        assertEquals(103, calls("updateTurnByTurnArrow(int)").single().payload["arrow"])
        guidance() // An identical iAP2 update must not repeat a DIM frame or start notification.
        assertEquals(1, calls("updateNavigationInfo").size)

        maneuver(type = 1, road = "人民路")
        guidance(75, 1_200, 1_800_000_300)
        assertEquals(1, calls("notifyTurnByTurnStarted").size)
        val updated = calls("updateNavigationInfo").last().payload
        assertEquals(102, updated["getNavigationTurnId"])
        assertEquals(75L, updated["getDistanceToNextGuidancePoint"])
        assertEquals(1_200L, updated["getDistanceToDestination"])
        assertEquals(1_800_000_300L, updated["getETA"])
        assertEquals("人民路", updated["getNextGuidancePointName"])
        assertEquals(75, calls("updateDistanceToNextGuidancePoint").last().payload["meters"])
        assertEquals(1_200, calls("updateDistanceToDestination").last().payload["meters"])
        assertEquals(1_800_000_300, calls("updateETA").last().payload["epochSeconds"])
        assertEquals("人民路", calls("updateNextGuidancePointName").last().payload["name"])

        FixtureLog.reset()
        send(0x5201) { u8(1, 2) }
        assertFalse(EcarxHudBridge.isActive())
        assertEquals(listOf("notifyTurnByTurnStopped", "updateTurnByTurnArrow(int)",
            "updateTurnByTurnArrow(String)", "updateDistanceToNextGuidancePoint",
            "updateNextGuidancePointName", "updateLaneInfo"), names())
        assertEquals(0, calls("updateTurnByTurnArrow(int)").single().payload["arrow"])
        assertEquals("", calls("updateTurnByTurnArrow(String)").single().payload["svg"])
        assertEquals(0, calls("updateDistanceToNextGuidancePoint").single().payload["meters"])
        assertEquals("", calls("updateNextGuidancePointName").single().payload["name"])
        assertNull(calls("updateLaneInfo").single().payload["lanes"])
        assertEquals(listOf(true, false), panelRequests.toList())
        val stopped = FixtureLog.calls().size
        EcarxHudBridge.clear(); tick(); EcarxHudBridge.clear()
        assertEquals(stopped, FixtureLog.calls().size)
    }

    @Test fun phoneNavigationKeepsMapAfterGuidanceExpiresButClearsStaleInstructions() {
        initialize()
        EcarxHudBridge.navigationChanged(true)
        maneuver(); guidance()
        nowNs += 30_000_000_000L
        tick()
        assertFalse(EcarxHudBridge.isActive())
        assertEquals(listOf(true), panelRequests.toList())
        assertTrue("stale instructions cannot notify the car that navigation stopped", calls("notifyTurnByTurnStopped").isEmpty())
        assertTrue(reports.any { "guidance expired" in it && "keepMap=true" in it })
        guidance()
        assertTrue(EcarxHudBridge.isActive())
        assertEquals(listOf(true), panelRequests.toList())
        assertEquals(1, calls("notifyTurnByTurnStarted").size)
        EcarxHudBridge.navigationChanged(false)
        assertEquals(1, calls("notifyTurnByTurnStopped").size)
        assertEquals(listOf(true, false), panelRequests.toList())
        assertFalse(EcarxHudBridge.isActive())
        guidance()
        assertFalse("late route frames cannot reopen an ended navigation", EcarxHudBridge.isActive())
    }

    @Test fun arrivalClosesRetainedMapWithoutWaitingForPhoneModeChange() {
        initialize()
        EcarxHudBridge.navigationChanged(true)
        maneuver(); guidance()
        nowNs += 30_000_000_000L
        tick()
        send(0x5201) { u8(1, 2) }
        assertEquals(listOf(true, false), panelRequests.toList())
    }

    @Test fun retainedMapClosesOnDisconnectAndNewPanelOwnerRestoresIt() {
        initialize()
        EcarxHudBridge.navigationChanged(true)
        maneuver(); guidance()
        nowNs += 30_000_000_000L
        tick()
        NavigationOutputs.setClusterPanelOwner(null)
        val replacement = mutableListOf<Boolean>()
        NavigationOutputs.setClusterPanelOwner { replacement += it }
        assertEquals(listOf(true), replacement)
        EcarxHudBridge.clear()
        assertEquals(listOf(true, false), replacement)
        tick()
        assertEquals(listOf(true, false), replacement)
    }

    @Test fun routeClockExpiresGuidanceAndEmptyManeuverListWithoutSleeping() {
        initialize(); maneuver(); guidance()
        FixtureLog.reset()
        send(0x5201) { u16List(0x0d, emptyList()) }
        nowNs += 2_999_999_999L
        tick()
        assertTrue(EcarxHudBridge.isActive())
        nowNs++
        tick()
        assertFalse(EcarxHudBridge.isActive())
        assertEquals(1, calls("notifyTurnByTurnStopped").size)
        maneuver(); guidance()
        nowNs += 30_000_000_000L
        tick()
        assertFalse(EcarxHudBridge.isActive())
        assertEquals(2, calls("notifyTurnByTurnStopped").size)
    }

    @Test fun missingFactoryAndNavigationHandleRecoverOnTickWithoutDroppingCachedRoute() {
        DimInteraction.nullCreate = true
        initialize(); maneuver(); guidance()
        assertFalse(EcarxHudBridge.isActive())
        DimInteraction.nullCreate = false
        DimInteraction.nullNavi = true
        tick()
        assertFalse(EcarxHudBridge.isActive())
        DimInteraction.nullNavi = false
        tick()
        // Tick keeps the current route until its normal keepalive deadline.
        repeat(5) { tick() }
        assertTrue(EcarxHudBridge.isActive())
        assertEquals(1, calls("notifyTurnByTurnStarted").size)
        assertTrue(reports.any { "DIM navi acquired" in it })
    }

    @Test fun aggregateFailureDoesNotStartOrAdvanceGuidanceAndRetriesSameFrame() {
        initialize(); maneuver()
        FixtureLog.failOnce("updateNavigationInfo")
        guidance()
        assertFalse(EcarxHudBridge.isActive())
        assertTrue(panelRequests.isEmpty())
        assertTrue(calls("notifyTurnByTurnStarted").isEmpty())
        assertTrue(reports.any { "updateNavigationInfo failed" in it })
        guidance()
        assertTrue(EcarxHudBridge.isActive())
        assertEquals(2, calls("updateNavigationInfo").size)
        assertEquals(1, calls("notifyTurnByTurnStarted").size)
    }

    @Test fun integerArrowFailureFallsBackToSvgAndCleanupContinuesAfterOneOemFailure() {
        initialize(); maneuver()
        FixtureLog.failOnce("updateTurnByTurnArrow(int)")
        guidance()
        val intIndex = names().indexOf("updateTurnByTurnArrow(int)")
        val svgIndex = names().indexOf("updateTurnByTurnArrow(String)")
        assertTrue(svgIndex > intIndex)
        val svg = calls("updateTurnByTurnArrow(String)").single().payload["svg"] as String
        assertTrue(svg.startsWith("<svg"))
        assertTrue(svg.contains("<path"))
        assertTrue(calls("updateTurnByTurnArrow(Bitmap)").isEmpty())
        FixtureLog.reset()
        FixtureLog.failOnce("notifyTurnByTurnStopped")
        EcarxHudBridge.clear()
        assertFalse(EcarxHudBridge.isActive())
        assertEquals("updateLaneInfo", names().last())
        assertEquals(listOf(true, false), panelRequests.toList())
    }

    @Test fun turningNavigationOffClearsOnNextRouteUpdateAndDoesNotRestartUntilEnabled() {
        initialize(); maneuver(); guidance()
        EcarxOutputSettings.setEnabled(context, false)
        guidance(distance = 200)
        assertFalse(EcarxHudBridge.isActive())
        guidance(distance = 150)
        assertEquals(1, calls("notifyTurnByTurnStopped").size)
        EcarxOutputSettings.setEnabled(context, true)
        guidance(distance = 100)
        assertTrue(EcarxHudBridge.isActive())
        assertEquals(listOf(true, false, true), panelRequests.toList())
    }

    @Test fun mirrorShowHideRetriesAndReleaseResendsTheActiveGuidance() {
        initialize(); maneuver(); guidance()
        FixtureLog.reset()
        assertTrue(EcarxOutputSettings.requestPresentation(context, true, "test panel ready"))
        assertTrue(EcarxOutputSettings.presentationSwitch(context))
        assertEquals(true, calls("setPresentationToDimSwitch").single().payload["show"])
        assertEquals(mapOf("screenId" to 1, "packageName" to "", "className" to "", "show" to true),
            calls("setPresentationToDimSwitch").single().payload)
        tick(); tick()
        assertEquals(2, calls("setPresentationToDimSwitch").size)
        assertTrue(EcarxOutputSettings.requestPresentation(context, false, "test panel closed"))
        assertFalse(EcarxOutputSettings.presentationSwitch(context))
        val methods = names()
        val hide = FixtureLog.calls().indexOfLast { it.method == "setPresentationToDimSwitch" }
        assertEquals(false, FixtureLog.calls()[hide].payload["show"])
        assertEquals("updateNavigationInfo", methods[hide + 1])
        assertEquals(0, calls("notifyTurnByTurnStarted").size)
        assertTrue(EcarxHudBridge.isActive())
        val switches = calls("setPresentationToDimSwitch").size
        repeat(5) { tick() }
        assertEquals(switches, calls("setPresentationToDimSwitch").size)
        EcarxHudBridge.clear()
        assertEquals(listOf(true, false), panelRequests.toList())
    }

    @Test fun startupDoesNotRestoreStaleMirrorAndFailedShowIsRetried() {
        EcarxOutputSettings.setPresentationSwitch(context, true)
        initialize()
        assertFalse(EcarxOutputSettings.presentationSwitch(context))
        assertTrue(calls("setPresentationToDimSwitch").isEmpty())
        FixtureLog.failOnce("setPresentationToDimSwitch")
        assertTrue(EcarxHudBridge.applyPresentation(true, "fixture").ok)
        assertTrue(reports.any { "presentation switch failed" in it })
        tick(); tick()
        assertEquals(listOf(true, true), calls("setPresentationToDimSwitch").map { it.payload["show"] })
        assertTrue(EcarxHudBridge.applyPresentation(false).ok)
        assertTrue(EcarxHudBridge.applyPresentation(false).ok)
        val count = calls("setPresentationToDimSwitch").size
        repeat(4) { tick() }
        assertEquals(count, calls("setPresentationToDimSwitch").size)
    }

    @Test fun mirrorArmsWhileFactoryIsNullThenRecoversAndReportsHideFailure() {
        DimInteraction.nullCreate = true
        initialize()
        assertTrue(EcarxHudBridge.applyPresentation(true).ok)
        assertTrue(calls("setPresentationToDimSwitch").isEmpty())
        DimInteraction.nullCreate = false
        tick(); tick()
        assertEquals(true, calls("setPresentationToDimSwitch").single().payload["show"])
        FixtureLog.failOnce("setPresentationToDimSwitch")
        assertFalse(EcarxHudBridge.applyPresentation(false).ok)
        assertFalse(EcarxOutputSettings.presentationSwitch(context))
        assertTrue(EcarxHudBridge.applyPresentation(false).ok)
        assertEquals(false, calls("setPresentationToDimSwitch").last().payload["show"])
    }

    @Test fun outputWorkerEndRejectsLateFramesAndRestartDoesNotReuseThePreviousManeuver() {
        NavigationOutputs.start(context)
        awaitOutputWorker()
        NavigationOutputs.onFrame(Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 2); string(4, "旧路") })
        val oldRoute = Iap2Messages.buildRaw(0x5201) { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 100) }
        NavigationOutputs.onFrame(oldRoute)
        awaitOutputWorker()
        assertTrue(EcarxHudBridge.isActive())
        NavigationOutputs.endNow()
        awaitOutputWorker()
        FixtureLog.reset()
        NavigationOutputs.onFrame(oldRoute)
        awaitOutputWorker()
        assertFalse(EcarxHudBridge.isActive())
        assertTrue(FixtureLog.calls().isEmpty())
        NavigationOutputs.start(context)
        awaitOutputWorker()
        NavigationOutputs.onFrame(oldRoute)
        awaitOutputWorker()
        assertFalse(EcarxHudBridge.isActive())
        NavigationOutputs.onFrame(Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 1); string(4, "新路") })
        awaitOutputWorker()
        assertTrue(EcarxHudBridge.isActive())
        assertEquals("新路", calls("updateNavigationInfo").last().payload["getNextGuidancePointName"])
        assertEquals(listOf(true, false, true), panelRequests.toList())
    }

    @Test fun phoneModesAreValidatedBeforeReachingTheProductionWorker() {
        NavigationOutputs.start(context)
        awaitOutputWorker()
        maneuver(); guidance()
        NavigationOutputs.onModesChanged(mapOf("appStates" to listOf(null, "bad", mapOf("appStateID" to 3, "entity" to 1))))
        awaitOutputWorker()
        nowNs += 30_000_000_000L
        tick()
        assertEquals(listOf(true), panelRequests.toList())
        NavigationOutputs.onModesChanged(mapOf("appStates" to listOf(mapOf("appStateID" to 3, "entity" to "0"),
            mapOf("appStateID" to 3, "entity" to 2), mapOf("appStateID" to 2, "entity" to 0))))
        awaitOutputWorker()
        assertEquals(listOf(true), panelRequests.toList())
        NavigationOutputs.onModesChanged(mapOf("appStates" to listOf(mapOf("appStateID" to 3, "entity" to 0))))
        awaitOutputWorker()
        assertEquals(listOf(true, false), panelRequests.toList())
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
