package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Looper
import com.ecarx.xui.adaptapi.diminteraction.DimInteraction
import com.ecarx.xui.adaptapi.diminteraction.FixtureLog
import com.ecarx.xui.adaptapi.diminteraction.NaviFixture
import com.ecarx.xui.adaptapi.diminteraction.INaviInteraction
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EcarxLaneGuidanceFlowTest {
    private companion object { const val FACTORY_PACKAGE = "ecarx.naviservice" }

    class NaviWithStatus : NaviFixture() {
        fun notifyNavigationStatus(status: Int) = FixtureLog.capture("notifyNavigationStatus", "status", status)
    }
    /** Proves guidance never disables the factory package (which also hosts its desktop widgets). */
    class NaviCheckingFactory(val context: Context) : NaviFixture() {
        override fun updateNavigationInfo(info: INaviInteraction.INavigationInfo) {
            assertNotEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                context.packageManager.getApplicationEnabledSetting(FACTORY_PACKAGE))
            super.updateNavigationInfo(info)
        }
    }

    private fun installFactoryNavigation() {
        val name = FACTORY_PACKAGE
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply { packageName = name; enabled = true }
        })
        shadowOf(context).grantPermissions(EcarxHicarControl.PERMISSION)
    }

    /** The factory package must never be left disabled; its desktop widgets depend on it. */
    private fun assertFactoryRestored() {
        assertNotEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            context.packageManager.getApplicationEnabledSetting(FACTORY_PACKAGE))
    }
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
        for (name in listOf("ecarxDetected", "presentationWanted", "releaseStalePresentation", "guidanceActive")) set(name, false)
        for (name in listOf("presentationCountdown", "presentationAttempts", "ticksSincePush")) set(name, 0)
        // Only the scheduler is suppressed: tests explicitly run the production tick below.
        set("tickerStarted", true)
        route = field("route").get(EcarxHudBridge) as RouteGuidanceState
        route.clear()
        val clock = route.javaClass.getDeclaredField("nanoTime").apply { isAccessible = true }
        originalClock = clock.get(route)
        clock.set(route, { nowNs })
        context.getSharedPreferences("diplay_ecarx_outputs_v2", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("diplay_factory_navigation_control", Context.MODE_PRIVATE).edit().clear().commit()
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
        assertTrue(EcarxOutputSettings.laneGuidanceEnabled(context))
        EcarxOutputSettings.setLaneGuidanceEnabled(context, false)
        assertFalse(EcarxOutputSettings.laneGuidanceEnabled(context))
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

    @Test fun dimCreationFromWorkerAndTickerRunsOnMainLooper() {
        DimInteraction.requireMainLooper = true
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            worker.submit { initialize() }.get(3, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(field("dim").get(EcarxHudBridge))
            set("dim", null)
            set("navi", null)
            worker.submit { tick() }.get(3, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(field("navi").get(EcarxHudBridge))
            assertEquals(2, calls("create").size)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun startupReleasesTheLegacyProjectionOnceWithoutRestoringAMirror() {
        EcarxOutputSettings.setPresentationSwitch(context, true)
        NavigationOutputs.onAppOpened(context)
        assertFalse(EcarxOutputSettings.presentationSwitch(context))
        repeat(12) { tick() }
        assertEquals(false, calls("setPresentationToDimSwitch").single().payload["show"])
        assertFalse(EcarxOutputSettings.presentationReleasePending(context))
        assertTrue(panelRequests.isEmpty())
    }

    @Test fun startupWithoutResidueDoesNotTouchProjection() {
        NavigationOutputs.onAppOpened(context)
        repeat(12) { tick() }
        assertTrue(calls("setPresentationToDimSwitch").isEmpty())
    }

    @Test fun startupReleaseWorksWithNavigationDisabledAndWaitsForDim() {
        EcarxOutputSettings.setEnabled(context, false)
        EcarxOutputSettings.setPresentationReleasePending(context, true)
        DimInteraction.nullCreate = true
        NavigationOutputs.onAppOpened(context)
        repeat(3) { tick() }
        assertTrue(calls("setPresentationToDimSwitch").isEmpty())
        assertTrue(EcarxOutputSettings.presentationReleasePending(context))
        DimInteraction.nullCreate = false
        repeat(12) { tick() }
        assertEquals(false, calls("setPresentationToDimSwitch").single().payload["show"])
        assertFalse(EcarxOutputSettings.presentationReleasePending(context))
    }

    @Test fun legacyResidueSurvivesAnotherStartupBeforeDimIsReady() {
        EcarxOutputSettings.setPresentationSwitch(context, true)
        DimInteraction.nullCreate = true
        NavigationOutputs.onAppOpened(context)
        assertFalse(EcarxOutputSettings.presentationSwitch(context))
        assertTrue(EcarxOutputSettings.presentationReleasePending(context))
        set("context", null)
        set("releaseStalePresentation", false)
        EcarxOutputSettings.setEnabled(context, false)
        NavigationOutputs.onAppOpened(context)
        DimInteraction.nullCreate = false
        tick()
        assertEquals(false, calls("setPresentationToDimSwitch").single().payload["show"])
    }

    @Test fun failedStartupReleaseKeepsResidueAndRetriesUntilTheCallSucceeds() {
        EcarxOutputSettings.setPresentationReleasePending(context, true)
        DimInteraction.failPresentation = true
        NavigationOutputs.onAppOpened(context)
        tick()
        assertTrue(EcarxOutputSettings.presentationReleasePending(context))
        DimInteraction.failPresentation = false
        tick()
        assertFalse(EcarxOutputSettings.presentationReleasePending(context))
        repeat(12) { tick() }
        assertEquals(listOf(false, false), calls("setPresentationToDimSwitch").map { it.payload["show"] })
    }

    @Test fun currentManualAndRouteRequestsSupersedeStartupReleaseEvenAfterReopeningTheApp() {
        for (origin in listOf("settings-on", "cluster stream")) {
            EcarxOutputSettings.setPresentationReleasePending(context, true)
            set("context", null)
            set("dim", null)
            set("navi", null)
            DimInteraction.nullCreate = true
            NavigationOutputs.onAppOpened(context)
            NavigationOutputs.requestPresentation(context, true, origin)
            NavigationOutputs.onAppOpened(context)
            FixtureLog.reset()
            DimInteraction.nullCreate = false
            repeat(12) { tick() }
            assertTrue(calls("setPresentationToDimSwitch").isNotEmpty())
            assertTrue(calls("setPresentationToDimSwitch").all { it.payload["show"] == true })
            assertTrue(EcarxOutputSettings.presentationReleasePending(context))
            NavigationOutputs.requestPresentation(context, false, origin)
            assertFalse(EcarxOutputSettings.presentationReleasePending(context))
        }
    }

    @Test fun currentFailedCloseCancelsStartupRetriesButPreservesResidueForNextLaunch() {
        EcarxOutputSettings.setPresentationReleasePending(context, true)
        NavigationOutputs.onAppOpened(context)
        DimInteraction.failPresentation = true
        assertFalse(NavigationOutputs.requestPresentation(context, false, "settings-off"))
        DimInteraction.failPresentation = false
        repeat(12) { tick() }
        assertEquals(1, calls("setPresentationToDimSwitch").size)
        assertTrue(EcarxOutputSettings.presentationReleasePending(context))
    }

    @Test fun manualProjectionRequestsKeepTheirStoredStateAndStopRetriesWhenDisabled() {
        assertTrue(NavigationOutputs.requestPresentation(context, true, "settings-on"))
        assertTrue(EcarxOutputSettings.presentationSwitch(context))
        val enabled = calls("setPresentationToDimSwitch").single().payload
        assertEquals(true, enabled["show"])
        assertEquals(1, enabled["screenId"])
        assertEquals("", enabled["packageName"])
        assertEquals("", enabled["className"])
        repeat(12) { tick() }
        assertTrue(calls("setPresentationToDimSwitch").size > 1)

        assertTrue(NavigationOutputs.requestPresentation(context, false, "settings-off"))
        assertFalse(EcarxOutputSettings.presentationSwitch(context))
        assertEquals(false, calls("setPresentationToDimSwitch").last().payload["show"])
        val stopped = calls("setPresentationToDimSwitch").size
        repeat(12) { tick() }
        assertEquals(stopped, calls("setPresentationToDimSwitch").size)
    }

    @Test fun aDetectedDimInterfaceCanAcceptARequestBeforeTheServiceBecomesReady() {
        DimInteraction.nullCreate = true
        assertEquals(EcarxCapabilities.Detection.DIM_INTERFACE, EcarxCapabilities.detect(context))
        assertTrue(EcarxCapabilities.detect(context).identified)
        assertTrue(NavigationOutputs.requestPresentation(context, true, "cluster stream"))
        assertTrue(EcarxOutputSettings.presentationSwitch(context))
        assertTrue(calls("setPresentationToDimSwitch").isEmpty())
        assertTrue(NavigationOutputs.diagnosticReport(context).contains("projectionWanted=true"))

        DimInteraction.nullCreate = false
        repeat(12) { tick() }
        assertTrue(calls("setPresentationToDimSwitch").isNotEmpty())
        assertEquals(true, calls("setPresentationToDimSwitch").last().payload["show"])
    }

    @Test fun aFailedProjectionReleaseStillClearsTheStoredRequestAndRetryIntent() {
        DimInteraction.nullCreate = true
        assertTrue(NavigationOutputs.requestPresentation(context, true, "cluster stream"))
        assertFalse(NavigationOutputs.requestPresentation(context, false, "cluster stream"))
        assertFalse(EcarxOutputSettings.presentationSwitch(context))
        DimInteraction.nullCreate = false
        repeat(12) { tick() }
        assertTrue(calls("setPresentationToDimSwitch").isEmpty())
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

    @Test fun distanceDiagnosticsSeparateInputSourcePartialUpdatesAndActualPublication() {
        NavigationOutputs.start(context)
        awaitOutputWorker()
        NavigationOutputs.onFrame(Iap2Messages.buildRaw(0x5202) {
            u16(1, 7); u8(3, 2); string(4, "private-road")
        }, "wireless-tunnel")
        NavigationOutputs.onFrame(Iap2Messages.buildRaw(0x5201) {
            u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 350)
        }, "wireless-tunnel")
        NavigationOutputs.onFrame(Iap2Messages.buildRaw(0x5201) { u32(0x0a, 1) }, "wireless-rfcomm")
        NavigationOutputs.onFrame(Iap2Messages.buildRaw(0x5201) { u32(7, 9000) }, "wired-usb")
        awaitOutputWorker()
        assertTrue(reports.any { it.startsWith("DIM input source=wireless-rfcomm") &&
            it.contains("state=absent next=1 list=absent") && it.contains("currentNext=1") })
        assertTrue(reports.any { it.startsWith("DIM input source=wired-usb") &&
            it.contains("next=absent") && it.contains("currentNext=1") })
        assertTrue(reports.any { it.startsWith("DIM publish next=350m") })
        assertTrue(reports.any { it.startsWith("DIM publish next=1m") && it.contains("next=ok") })
        repeat(5) { tick() }
        assertTrue(reports.last { it.startsWith("DIM publish") }.contains("force=true first=false"))
        send(0x5201) { u32(0x0a, 0) }
        assertTrue(reports.last { it.startsWith("DIM publish") }.startsWith("DIM publish next=0m"))
        val publications = reports.count { it.startsWith("DIM publish") }
        EcarxHudBridge.onFrame(Iap2Frame(0x5201, byteArrayOf(0, 1, 2)), "wireless-tunnel")
        assertTrue(reports.last { it.startsWith("DIM input") }.contains("invalid-tlv"))
        assertEquals(publications, reports.count { it.startsWith("DIM publish") })
        send(0x5201) { u8(1, 0) }
        assertTrue(reports.last { it.startsWith("DIM input") }.contains("state=0"))
        assertTrue(reports.last { it.startsWith("DIM input") }.contains("change=CLEAR currentNext=none"))
        assertTrue(reports.last { it.startsWith("DIM guidance ended") }.contains("reason=no-route"))
        assertTrue(reports.filter { it.startsWith("DIM input") || it.startsWith("DIM publish") }
            .none { it.contains("private-road") })
    }

    @Test fun guidanceDiagnosticsDistinguishTimeoutAndPhoneEndWithoutRoadNames() {
        initialize(); maneuver(); guidance()
        send(0x5201) { u16List(0x0d, emptyList()) }
        nowNs += 3_000_000_000L
        tick()
        assertTrue(reports.last { it.startsWith("DIM guidance ended") }.contains("reason=empty-list-timeout"))
        assertTrue(route.diagnosticState().contains("emptyAgeMs=3000"))
        assertFalse(route.diagnosticState().contains("滨海路"))

        guidance()
        nowNs += 30_000_000_000L
        tick()
        assertTrue(reports.last { it.startsWith("DIM guidance ended") }.contains("reason=route-stale"))
        guidance()
        send(0x5201) { u8(1, 0) }
        assertTrue(reports.last { it.startsWith("DIM guidance ended") }.contains("reason=no-route"))
        maneuver(); guidance()
        send(0x5201) { u8(1, 2) }
        assertTrue(reports.last { it.startsWith("DIM guidance ended") }.contains("reason=arrived"))
    }

    @Test fun quietRouteKeepsItsPanelAndFactoryOwnershipOnlyWhileCurrentSessionIsLive() {
        installFactoryNavigation()
        var sessionActive = true
        NavigationOutputs.start(context)
        NavigationOutputs.onSessionActive { sessionActive }
        awaitOutputWorker()
        maneuver(); guidance()
        nowNs += 90_000_000_000L
        repeat(5) { tick() }
        assertTrue(EcarxHudBridge.isActive())
        assertTrue(panelRequests.last())
        assertNotEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            context.packageManager.getApplicationEnabledSetting(FACTORY_PACKAGE))
        assertTrue(route.diagnosticState().contains("reason=active"))
        assertTrue(route.diagnosticState().contains("sessionActive=true"))
        assertFalse(reports.any { it.startsWith("DIM guidance ended") })

        sessionActive = false
        tick()
        assertFalse(EcarxHudBridge.isActive())
        assertFalse(panelRequests.last())
        assertFactoryRestored()
        assertTrue(reports.last { it.startsWith("DIM guidance ended") }.contains("reason=route-stale"))
    }

    @Test fun liveSessionStillClearsOnExplicitEndEmptyRouteAndOutputStop() {
        installFactoryNavigation()
        NavigationOutputs.start(context)
        NavigationOutputs.onSessionActive { true }
        awaitOutputWorker()
        for (end in listOf("no-route", "arrived", "empty-list-timeout", "disconnect")) {
            maneuver(); guidance()
            nowNs += 31_000_000_000L
            tick()
            assertTrue(EcarxHudBridge.isActive())
            when (end) {
                "no-route" -> send(0x5201) { u8(1, 0) }
                "arrived" -> send(0x5201) { u8(1, 2) }
                "empty-list-timeout" -> {
                    send(0x5201) { u16List(0x0d, emptyList()) }
                    nowNs += 3_000_000_000L
                    tick()
                }
                else -> {
                    NavigationOutputs.endNow()
                    awaitOutputWorker()
                }
            }
            assertFalse(end, EcarxHudBridge.isActive())
            assertFalse(end, panelRequests.last())
            assertFactoryRestored()
        }
        // A cleared output owner cannot retain a later route, even if its old session probe was true.
        maneuver(); guidance()
        nowNs += 30_000_000_000L
        tick()
        assertFalse(EcarxHudBridge.isActive())
    }

    @Test fun identicalRoutesRestartAfterNoRouteArrivalAndDisconnect() {
        initialize()
        repeat(3) { cycle ->
            // Route data can precede its maneuver; never reuse the previous route's cached turn.
            guidance()
            assertFalse(EcarxHudBridge.isActive())
            maneuver()
            assertTrue(EcarxHudBridge.isActive())
            assertTrue(DimInteraction.navi.ongoingNavigationActive)
            assertEquals(350L, calls("updateNavigationInfo").last().payload["getDistanceToNextGuidancePoint"])
            repeat(5) { tick() }
            assertEquals(cycle * 2 + 1, DimInteraction.navi.ongoingTransitions.size)
            when (cycle) {
                0 -> send(0x5201) { u8(1, 0) }
                1 -> send(0x5201) { u8(1, 2) }
                else -> EcarxHudBridge.clear()
            }
            assertFalse(EcarxHudBridge.isActive())
            assertFalse(DimInteraction.navi.ongoingNavigationActive)
            assertNull(route.current())
            val idle = calls("updateNavigationInfo").last().payload
            assertEquals(0, idle["getNavigationStatus"])
            assertEquals(0L, idle["getDistanceToNextGuidancePoint"])
            assertEquals(0L, idle["getDistanceToDestination"])
            assertEquals(0L, idle["getETA"])
            assertEquals("", idle["getNextGuidancePointName"])
            assertTrue(names().lastIndexOf("updateNavigationInfo") < names().lastIndexOf("notifyTurnByTurnStopped"))
            val endedCalls = names().size
            repeat(5) { tick() }
            assertEquals(endedCalls, names().size)
        }
        assertEquals(listOf(true, false, true, false, true, false), DimInteraction.navi.ongoingTransitions)
        assertEquals(listOf(true, false, true, false, true, false), panelRequests)
        assertEquals(3, calls("notifyTurnByTurnStarted").size)
        assertEquals(3, calls("notifyTurnByTurnStopped").size)
        assertTrue(calls("setPresentationToDimSwitch").isEmpty())
        assertEquals(3, reports.count { it.startsWith("DIM lifecycle start status=1 aggregate=ok notify=ok") })
        assertEquals(3, reports.count { it.startsWith("DIM lifecycle stop status=0 aggregate=ok notify=ok") })
        assertEquals(6, reports.count { it.startsWith("DIM lifecycle") && it.contains("method not found") })
    }

    @Test fun optionalHyStatusFollowsAggregateBeforeLifecycleAndDoesNotRepeatOnKeepalive() {
        initialize()
        set("navi", NaviWithStatus())
        maneuver(); guidance()
        assertTrue(names().indexOf("updateNavigationInfo") < names().indexOf("notifyNavigationStatus"))
        assertTrue(names().indexOf("notifyNavigationStatus") < names().indexOf("notifyTurnByTurnStarted"))
        repeat(10) { tick() }
        guidance(distance = 300)
        assertEquals(listOf(1), calls("notifyNavigationStatus").map { it.payload["status"] })
        send(0x5201) { u8(1, 0) }
        assertTrue(names().lastIndexOf("updateNavigationInfo") < names().lastIndexOf("notifyNavigationStatus"))
        assertTrue(names().lastIndexOf("notifyNavigationStatus") < names().lastIndexOf("notifyTurnByTurnStopped"))
        maneuver(); guidance()
        assertEquals(listOf(1, 0, 1), calls("notifyNavigationStatus").map { it.payload["status"] })
        assertTrue(calls("setPresentationToDimSwitch").isEmpty())
    }

    @Test fun guidanceNeverDisablesFactoryNavigationAcrossAllExitPaths() {
        installFactoryNavigation(); initialize()
        // UpdateNavigationInfo itself asserts the factory package is never DISABLED_USER.
        set("navi", NaviCheckingFactory(context))
        repeat(5) { cycle ->
            maneuver(); guidance()
            assertTrue(EcarxHudBridge.isActive())
            when (cycle) {
                0 -> send(0x5201) { u8(1, 0) }
                1 -> send(0x5201) { u8(1, 2) }
                2 -> EcarxHudBridge.clear()
                3 -> { nowNs += 30_000_000_000L; tick() }
                else -> {
                    EcarxOutputSettings.setEnabled(context, false)
                    NavigationOutputs.ecarxChanged(context)
                    awaitOutputWorker()
                }
            }
            assertFalse(EcarxHudBridge.isActive())
            assertFactoryRestored()
            repeat(5) { tick() }
        }
    }

    @Test fun firstAndOngoingAggregateFailuresStopGuidance() {
        installFactoryNavigation(); initialize()
        set("navi", NaviCheckingFactory(context))
        maneuver()
        FixtureLog.failOnce("updateNavigationInfo")
        guidance()
        assertFalse(EcarxHudBridge.isActive())
        assertFactoryRestored()
        guidance()
        assertTrue(EcarxHudBridge.isActive())
        FixtureLog.failOnce("updateNavigationInfo")
        guidance(distance = 200)
        assertFalse(EcarxHudBridge.isActive())
        assertFactoryRestored()
        assertTrue(reports.any { it.contains("reason=aggregate-failed") })
    }

    @Test fun failedPanelCleanupOnTimeoutStillStopsGuidance() {
        installFactoryNavigation(); initialize()
        maneuver(); guidance()
        NavigationOutputs.setClusterPanelOwner { if (!it) error("panel cleanup failed") }
        nowNs += 30_000_000_000L
        tick()
        assertFalse(EcarxHudBridge.isActive())
        assertFactoryRestored()
        tick()
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun rejectedOptionalHyStatusDoesNotBlockGuidanceOrCleanup() {
        initialize()
        val target = NaviWithStatus()
        set("navi", target)
        FixtureLog.failOn("notifyNavigationStatus")
        maneuver(); guidance()
        assertTrue(EcarxHudBridge.isActive())
        assertTrue(target.ongoingNavigationActive)
        assertEquals(1, calls("notifyTurnByTurnStarted").size)
        EcarxHudBridge.clear()
        assertFalse(target.ongoingNavigationActive)
        assertEquals(1, calls("notifyTurnByTurnStopped").size)
        assertEquals(listOf(true, false), panelRequests)
        assertEquals(2, reports.count { it.startsWith("DIM lifecycle") &&
            it.contains("navigationStatus=failed") && it.contains("threw") })
    }

    @Test fun unchangedRouteExpiresAtExistingDeadlineAndFreshInputRestartsIt() {
        initialize(); maneuver(); guidance()
        nowNs += 29_000_000_000L
        repeat(5) { tick() }
        assertTrue(EcarxHudBridge.isActive())
        assertEquals(listOf(true), DimInteraction.navi.ongoingTransitions)
        nowNs += 1_000_000_000L
        tick()
        assertFalse(EcarxHudBridge.isActive())
        assertEquals(listOf(true, false), DimInteraction.navi.ongoingTransitions)
        repeat(5) { tick() }
        assertEquals(listOf(true, false), DimInteraction.navi.ongoingTransitions)
        // A timeout hides output but retains maneuver details; a fresh partial update can resume it.
        send(0x5201) { u32(0x0a, 290) }
        assertTrue(EcarxHudBridge.isActive())
        assertEquals(290L, calls("updateNavigationInfo").last().payload["getDistanceToNextGuidancePoint"])
        send(0x5201) { u16List(0x0d, emptyList()) }
        nowNs += 3_000_000_000L
        tick()
        assertEquals(listOf(true, false, true, false), DimInteraction.navi.ongoingTransitions)
        assertTrue(reports.last { it.startsWith("DIM guidance ended") }.contains("reason=empty-list-timeout"))
    }

    @Test fun lifecycleReportsSeparateNotificationFailureFromIdleStateFailure() {
        initialize(); maneuver()
        FixtureLog.failOnce("notifyTurnByTurnStarted")
        guidance()
        assertTrue(DimInteraction.navi.ongoingNavigationActive)
        assertTrue(reports.any { it.startsWith("DIM lifecycle start status=1 aggregate=ok notify=failed") })
        FixtureLog.failOnce("notifyTurnByTurnStopped")
        send(0x5201) { u8(1, 0) }
        assertFalse(DimInteraction.navi.ongoingNavigationActive)
        assertTrue(reports.any { it.startsWith("DIM lifecycle stop status=0 aggregate=ok notify=failed") })
        maneuver(); guidance()
        assertEquals(listOf(true, false, true), DimInteraction.navi.ongoingTransitions)
        FixtureLog.failOnce("updateNavigationInfo")
        send(0x5201) { u8(1, 2) }
        assertFalse(EcarxHudBridge.isActive())
        assertNull(route.current())
        assertTrue(DimInteraction.navi.ongoingNavigationActive)
        assertTrue(reports.any { it.startsWith("DIM lifecycle stop status=0 aggregate=failed notify=ok") })
        assertEquals(listOf(true, false, true, false), panelRequests)
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
        shadowOf(Looper.getMainLooper()).idle()
    }
}
