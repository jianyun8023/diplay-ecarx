package com.shilapi.xcertplay.hud

import android.content.Context
import android.cevt.dimsupportservice.DimSupportManager
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
import org.robolectric.shadows.ShadowToast
import com.shilapi.xcertplay.shared.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EcarxLaneGuidanceFlowTest {
    private companion object { const val FACTORY_PACKAGE = "ecarx.naviservice" }

    class NaviWithStatus : NaviFixture() {
        fun notifyNavigationStatus(status: Int) = FixtureLog.capture("notifyNavigationStatus", "status", status)
    }
    /** Every DIM frame, including the final idle update, must precede factory service restoration. */
    class NaviCheckingFactory(val context: Context) : NaviFixture() {
        override fun updateNavigationInfo(info: INaviInteraction.INavigationInfo) {
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
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

    /** After an exit the factory package is immediately available to Android again. */
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
    private var originalEpochClock: Any? = null

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
            "lastPushed", "lastArrowChannel", "lastNavigationRefreshOutcome", "presentedPackage", "lanes", "lastLanePushed")) set(name, null)
        for (name in listOf("ecarxDetected", "presentationWanted", "releaseStalePresentation", "clusterVisibleWanted", "guidanceActive", "hudActive", "hudOutputTouched")) set(name, false)
        for (name in listOf("presentationOption", "presentationCountdown", "presentationAttempts", "ticksSinceRefresh")) set(name, 0)
        // Only the scheduler is suppressed: tests explicitly run the production tick below.
        set("tickerStarted", true)
        route = field("route").get(EcarxHudBridge) as RouteGuidanceState
        route.clear()
        val clock = route.javaClass.getDeclaredField("nanoTime").apply { isAccessible = true }
        originalClock = clock.get(route)
        originalEpochClock = field("epochSeconds").get(EcarxHudBridge)
        clock.set(route, { nowNs })
        context.getSharedPreferences("diplay_ecarx_outputs_v2", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("diplay_factory_navigation_control", Context.MODE_PRIVATE).edit().clear().commit()
        EcarxOutputSettings.setMediaEnabled(context, false)
        NavigationOutputs.setClusterPanelOwner { panelRequests += (it == ClusterMapIntent.SHOWN) }
        EcarxHudBridge.attachReport { reports += it }
        reports.clear()
        panelRequests.clear()
        NavigationOutputs.debugLogsEnabled = { true }
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
        set("epochSeconds", originalEpochClock)
        set("context", null)
        set("tickerStarted", false)
        EcarxHudBridge.attachReport {}
        DimInteraction.reset()
    }

    @Test fun laneFramesReachTypedDimArrayDespiteLegacyLanePreference() {
        context.getSharedPreferences("diplay_ecarx_outputs_v2", Context.MODE_PRIVATE).edit()
            .putBoolean("dim_lane_guidance_enabled", false).commit()
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
        assertEquals(listOf(
            mapOf("number" to 1, "iconId" to 1, "bitmap" to null),
            mapOf("number" to 2, "iconId" to 0, "bitmap" to null),
            mapOf("number" to 3, "iconId" to 33, "bitmap" to null),
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
        NavigationOutputs.endNow()
        awaitOutputWorker()
        assertNull(calls("updateLaneInfo").last().payload["lanes"])
    }

    @Test fun etaNormalizesDurationTimestampAndRemainingTimeWithoutKeepaliveDrift() {
        initialize()
        for (mode in 0..3) {
            set("epochSeconds", originalEpochClock)
            maneuver()
            send(0x5201) {
                u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 350)
                when (mode) {
                    0 -> u64(5, 600)
                    1 -> u64(5, System.currentTimeMillis() / 1000 + 600)
                    2 -> u64(6, 600)
                    else -> { u64(5, 0); u64(6, 600) }
                }
            }
            val snapshot = calls("updateNavigationInfo").last().payload["getETA"] as Long
            val separate = calls("updateETA").last().payload["remainingSeconds"] as Int
            assertTrue("mode=$mode eta=$snapshot", snapshot in 598L..600L)
            assertEquals(snapshot.toInt(), separate)
            val arrival = route.arrivalEpochSeconds
            var wallSeconds = System.currentTimeMillis() / 1000
            set("epochSeconds", { wallSeconds })
            wallSeconds += 10
            repeat(5) { tick() }
            assertEquals(arrival, route.arrivalEpochSeconds)
            val refreshed = calls("updateETA").last().payload["remainingSeconds"] as Int
            assertTrue("mode=$mode refreshed=$refreshed", refreshed in 588..590)
            send(0x5201) { u8(1, 0) }
        }
    }

    @Test fun turnGapKeepsPublishingDestinationRemainingDistanceAndEtaWithoutFakeNextDistance() {
        installFactoryNavigation(); initialize(); maneuver(); guidance()
        val nextWrites = calls("updateDistanceToNextGuidancePoint").size
        send(0x5201) {
            u16List(0x0d, listOf(8)); u32(7, 6543); u64(6, 300); string(4, "新的目的地")
        }
        assertNull(route.currentForDim())
        assertTrue(EcarxHudBridge.isActive())
        assertTrue(DimInteraction.navi.handlerNavigationActive)
        assertEquals(nextWrites, calls("updateDistanceToNextGuidancePoint").size)
        assertEquals(6543, calls("updateDistanceToDestination").last().payload["meters"])
        assertTrue((calls("updateETA").last().payload["remainingSeconds"] as Int) in 298..300)
        assertEquals("新的目的地", DimInteraction.navi.destinationName)
        assertTrue(calls("notifyTurnByTurnStopped").isEmpty())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            context.packageManager.getApplicationEnabledSetting(FACTORY_PACKAGE))
    }

    @Test fun disablingHudDuringATurnGapStillWithdrawsItsOutputAndCanResume() {
        initialize(); maneuver(); guidance()
        send(0x5201) { u16List(0x0d, listOf(8)); u16(0x10, 2); u8(0x12, 1) }
        assertNull(field("lastPushed").get(EcarxHudBridge))
        assertTrue(field("hudActive").getBoolean(EcarxHudBridge))
        val panels = panelRequests.toList()
        EcarxOutputSettings.setEnabled(context, false)
        EcarxHudBridge.settingsChanged()
        assertTrue(EcarxHudBridge.isActive())
        assertFalse(DimInteraction.navi.ongoingNavigationActive)
        assertFalse(field("hudActive").getBoolean(EcarxHudBridge))
        send(0x5204) {
            u16(0, 42); u16(1, 2); group(2) { u16(0, 0); u8(1, 2); i16(2, -90) }
        }
        EcarxOutputSettings.setEnabled(context, true)
        EcarxHudBridge.settingsChanged()
        maneuver(index = 8)
        send(0x5201) { u32(0x0a, 150) }
        assertTrue(DimInteraction.navi.ongoingNavigationActive)
        assertEquals(150, calls("updateDistanceToNextGuidancePoint").last().payload["meters"])
        val lanes = calls("updateLaneInfo").last().payload["lanes"] as List<*>
        assertEquals(16, (lanes.single() as Map<*, *>)["iconId"])
        assertEquals(panels, panelRequests.toList())
    }

    @Test fun startupResidueWaitsUntilTheCurrentRouteEndsBeforeClosingTheNavigationApp() {
        EcarxOutputSettings.setPresentationReleasePending(context, true)
        initialize(); maneuver(); guidance()
        repeat(12) { tick() }
        assertTrue(calls("setPresentationToDimSwitch").isEmpty())
        assertTrue(EcarxOutputSettings.presentationReleasePending(context))
        assertTrue(EcarxHudBridge.isActive())
        send(0x5201) { u8(1, 0) }
        assertEquals(listOf(false), calls("setPresentationToDimSwitch").map { it.payload["show"] })
        assertFalse(EcarxOutputSettings.presentationReleasePending(context))
    }

    @Test fun hudCanBeDisabledWhileTheMapAndInstrumentExitRemainAvailable() {
        installFactoryNavigation()
        EcarxOutputSettings.setEnabled(context, false)
        initialize(); maneuver(); guidance()
        assertTrue(panelRequests.last())
        assertTrue(calls("updateNavigationInfo").isEmpty())
        assertTrue(calls("updateTurnByTurnArrow(int)").isEmpty())
        assertNotNull(route.current())
        NavigationOutputs.onAppOpened(context)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            context.packageManager.getApplicationEnabledSetting(FACTORY_PACKAGE))
        var exits = 0
        NavigationOutputs.requestNavigationExit = { exits++; true }
        DimInteraction.navi.callback.onDoInteractionAction(2, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, exits)
        assertNotNull(route.current())
        assertTrue(panelRequests.last())
        send(0x5201) { u8(1, 0) }
        assertNull(route.current())
        assertFalse(panelRequests.last())
        assertTrue(calls("updateNavigationInfo").isEmpty())
        assertTrue(calls("notifyTurnByTurnStopped").isEmpty())
        assertFactoryRestored()
    }

    @Test fun keepaliveRecoversAnArrowCachedBeforeASwallowedSendFailure() {
        initialize(); maneuver()
        NavigationOutputs.attachReport { reports += it }
        NavigationOutputs.debugLogsEnabled = { false }
        DimSupportManager.failedIconWrites = 2
        guidance()
        assertEquals(103, DimInteraction.navi.cachedArrowId)
        assertFalse(DimSupportManager.writes.contains("icon:0:103"))
        assertTrue(reports.any { "manager refresh accepted=false" in it })
        repeat(5) {
            guidance(distance = 349 - it)
            assertEquals(2, DimSupportManager.iconAttempts)
            tick()
        }
        assertTrue(DimSupportManager.writes.contains("icon:0:103"))
        assertTrue(DimSupportManager.writes.contains("next:345:滨海路"))
        assertTrue(reports.any { "manager refresh accepted=true" in it })
        assertTrue(EcarxHudBridge.isActive())
        assertTrue(calls("notifyTurnByTurnStopped").isEmpty())
        assertTrue(calls("updateNavigationInfo").all { it.payload["getNavigationStatus"] == 1 })
    }

    @Test fun twentySourceChangesRefreshTheSameArrowWithoutClosingTheRoute() {
        initialize(); maneuver(); guidance()
        repeat(20) {
            DimSupportManager.writes.clear()
            assertTrue(NavigationOutputs.requestPresentation(context, false, "hide"))
            assertTrue(NavigationOutputs.requestPresentation(context, true, "current source ready"))
            assertTrue(DimSupportManager.writes.contains("icon:0:103"))
            assertTrue(DimSupportManager.writes.contains("next:350:滨海路"))
            assertTrue(DimSupportManager.writes.contains("distance:9876"))
            assertTrue(DimSupportManager.writes.any { it.startsWith("eta:") })
        }
        assertTrue(EcarxHudBridge.isActive())
        assertTrue(DimInteraction.navi.handlerNavigationActive)
        assertFalse(DimInteraction.navi.navigationEvents.contains("naviAppOff"))
        assertTrue(calls("notifyTurnByTurnStopped").isEmpty())
    }

    @Test fun managerRefreshDuringATurnGapDoesNotInventAnArrowOrNextDistance() {
        initialize(); maneuver(); guidance()
        send(0x5201) { u16List(0x0d, listOf(8)); u32(7, 6543); u64(6, 300) }
        DimSupportManager.writes.clear()
        repeat(5) { tick() }
        assertTrue(DimSupportManager.writes.contains("distance:6543"))
        assertTrue(DimSupportManager.writes.any { it.startsWith("eta:") })
        assertFalse(DimSupportManager.writes.any { it.startsWith("icon:") || it.startsWith("next:") })
        assertTrue(EcarxHudBridge.isActive())
        assertTrue(calls("notifyTurnByTurnStopped").isEmpty())
    }

    @Test fun partiallyFailedFirstAggregateIsClearedBeforeFactoryNavigationReturns() {
        installFactoryNavigation(); initialize()
        set("navi", EcarxDimApi.NaviHandle(object : NaviFixture() {
            override fun updateNavigationInfo(info: INaviInteraction.INavigationInfo) {
                super.updateNavigationInfo(info)
                if (info.navigationStatus == 1) throw IllegalStateException("partial aggregate")
            }
        }))
        maneuver(); guidance()
        assertEquals(listOf(1, 0), calls("updateNavigationInfo").map { it.payload["getNavigationStatus"] })
        assertEquals(1, calls("notifyTurnByTurnStopped").size)
        assertFalse(EcarxHudBridge.isActive())
        assertNotNull(route.currentForDim())
        assertFactoryRestored()
    }

    @Test fun hudToggleKeepsTheRouteMapAndLatestLaneData() {
        initialize()
        send(0x5204) {
            u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 2); i16(2, -90) }
        }
        maneuver()
        send(0x5201) {
            u8(1, 6); u16List(0x0d, listOf(7)); u32(0x0a, 90); u16(0x10, 0); u8(0x12, 1)
        }
        val panels = panelRequests.toList()
        val lanes = calls("updateLaneInfo").last().payload["lanes"]
        assertNotNull(lanes)
        EcarxOutputSettings.setEnabled(context, false)
        EcarxHudBridge.settingsChanged()
        assertTrue(EcarxHudBridge.isActive())
        assertFalse(field("hudActive").getBoolean(EcarxHudBridge))
        assertNull(calls("updateLaneInfo").last().payload["lanes"])
        val count = calls("updateNavigationInfo").size
        guidance(distance = 120)
        repeat(5) { tick() }
        assertEquals(count, calls("updateNavigationInfo").size)
        assertEquals(120, route.current()!!.distanceMeters)
        assertEquals(panels, panelRequests.toList())
        EcarxOutputSettings.setEnabled(context, true)
        EcarxHudBridge.settingsChanged()
        assertTrue(EcarxHudBridge.isActive())
        assertEquals(120L, calls("updateNavigationInfo").last().payload["getDistanceToNextGuidancePoint"])
        assertEquals(lanes, calls("updateLaneInfo").last().payload["lanes"])
        assertEquals(panels, panelRequests.toList())
    }

    @Test fun automaticMapChoiceAppliesAtRouteStartAndOemVisibilityStillWins() {
        EcarxOutputSettings.setAutoShowMap(context, false)
        initialize(); maneuver(); guidance()
        assertTrue(EcarxHudBridge.isActive())
        assertFalse(panelRequests.last())
        DimInteraction.instance().callback.onShowPresentationOptionChanged(1)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(panelRequests.last())
        EcarxOutputSettings.setAutoShowMap(context, true)
        EcarxHudBridge.settingsChanged()
        DimInteraction.instance().callback.onShowPresentationOptionChanged(1)
        shadowOf(Looper.getMainLooper()).idle()
        guidance(distance = 200)
        assertFalse(panelRequests.last())
        send(0x5201) { u8(1, 0) }
        maneuver(); guidance()
        assertTrue(panelRequests.last())
        DimInteraction.instance().callback.onShowPresentationOptionChanged(3)
        shadowOf(Looper.getMainLooper()).idle()
        guidance(distance = 100)
        assertFalse(panelRequests.last())
        assertTrue(EcarxHudBridge.isActive())
        assertEquals(100L, calls("updateNavigationInfo").last().payload["getDistanceToNextGuidancePoint"])
        DimInteraction.instance().callback.onShowPresentationOptionChanged(2)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(panelRequests.last())
        EcarxOutputSettings.setAutoShowMap(context, false)
        send(0x5201) { u8(1, 0) }
        maneuver(); guidance()
        assertTrue(panelRequests.last())
        assertFalse(EcarxOutputSettings.autoShowMap(context))
        EcarxHudBridge.clear()
        assertFalse(panelRequests.last())
    }

    @Test fun instrumentVisibilityPersistsAcrossRoutesUntilDiPlayRestarts() {
        for (initial in listOf(false, true)) {
            EcarxOutputSettings.setAutoShowMap(context, initial)
            initialize(); maneuver(); guidance()
            assertEquals(initial, panelRequests.last())
            DimInteraction.instance().callback.onShowPresentationOptionChanged(if (initial) 3 else 2)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(!initial, panelRequests.last())
            send(0x5201) { u8(1, 0) }
            maneuver(); guidance()
            assertEquals(!initial, panelRequests.last())

            EcarxHudBridge.clear()
            // Exit application kills the process; a fresh bridge must read the saved initial choice.
            set("context", null)
            NavigationOutputs.onAppOpened(context)
            assertEquals(0, field("presentationOption").getInt(EcarxHudBridge))
            maneuver(); guidance()
            assertEquals(initial, panelRequests.last())
            EcarxHudBridge.clear()
        }
    }

    @Test fun hiddenLanesStayCachedUntilThePhoneShowsThemAgain() {
        initialize()
        send(0x5204) {
            u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 2); i16(2, -90) }
        }
        maneuver()
        send(0x5201) {
            u8(1, 6); u16List(0x0d, listOf(7)); u32(0x0a, 90); u16(0x10, 0); u8(0x12, 0)
        }
        repeat(5) { tick() }
        assertTrue(calls("updateLaneInfo").isEmpty())
        send(0x5201) { u8(0x12, 1) }
        assertEquals(listOf(mapOf("number" to 1, "iconId" to 16, "bitmap" to null)),
            calls("updateLaneInfo").single().payload["lanes"])
        send(0x5201) { u8(0x12, 0) }
        assertNull(calls("updateLaneInfo").last().payload["lanes"])
        send(0x5204) {
            u16(0, 42); u16(1, 1); group(2) { u16(0, 0); u8(1, 2); i16(2, 90) }
        }
        send(0x5201) { u16(0x10, 1) }
        repeat(5) { tick() }
        assertNull(calls("updateLaneInfo").last().payload["lanes"])
        send(0x5201) { u8(0x12, 1) }
        assertEquals(listOf(mapOf("number" to 1, "iconId" to 18, "bitmap" to null)),
            calls("updateLaneInfo").last().payload["lanes"])
        send(0x5201) { u8(1, 2) }
        assertNull(calls("updateLaneInfo").last().payload["lanes"])
        assertNull(route.laneGuidanceShowing)
    }

    @Test fun prefetchedLanesWaitForGuidanceAndFollowTheirOwnCursor() {
        initialize()
        send(0x5204) {
            u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 2); i16(2, -90) }
        }
        send(0x5204) {
            u16(0, 42); u16(1, 1); group(2) { u16(0, 0); u8(1, 2); i16(2, 90) }
        }
        repeat(5) { tick() }
        assertTrue(calls("updateLaneInfo").isEmpty())
        // Apple Maps sends this initial NoRouteSet after its prefetch burst.
        send(0x5201) { u8(1, 0) }
        maneuver()
        send(0x5201) { u8(1, 6); u16List(0x0d, listOf(7)); u32(0x0a, 90); u16(0x10, 0) }
        assertEquals(listOf(mapOf("number" to 1, "iconId" to 16, "bitmap" to null)),
            calls("updateLaneInfo").single().payload["lanes"])
        send(0x5201) { u16(0x10, 1) }
        assertEquals(listOf(mapOf("number" to 1, "iconId" to 18, "bitmap" to null)),
            calls("updateLaneInfo").last().payload["lanes"])
        send(0x5201) { u16(0x10, 2) }
        assertNull(calls("updateLaneInfo").last().payload["lanes"])
        send(0x5201) { u16(0x10, 0) }
        assertNull(calls("updateLaneInfo").last().payload["lanes"])
        // A cursor rollback must wait for fresh data after the old event was retired.
        send(0x5204) {
            u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 2); i16(2, -90) }
        }
        assertNotNull(calls("updateLaneInfo").last().payload["lanes"])
        send(0x5201) { u8(1, 2) }
        assertNull(calls("updateLaneInfo").last().payload["lanes"])
        val afterEnd = calls("updateLaneInfo").size
        maneuver(); guidance()
        assertEquals(afterEnd, calls("updateLaneInfo").size)
    }

    @Test fun unknownLaneAnglesReachDimOnlyWhenAValidDirectionRemains() {
        initialize(); maneuver(); guidance()
        send(0x5201) { u16(0x10, 0) }
        for (unknown in listOf(1000, -1000)) {
            send(0x5204) {
                u16(0, 42); u16(1, 0)
                group(2) { u16(0, 0); u8(1, 2); i16(2, unknown); i16(2, -90); i16(3, unknown) }
            }
            assertEquals(listOf(mapOf("number" to 1, "iconId" to 16, "bitmap" to null)),
                calls("updateLaneInfo").last().payload["lanes"])
            send(0x5204) {
                u16(0, 42); u16(1, 0)
                group(2) { u16(0, 0); u8(1, 2); i16(2, unknown); i16(3, unknown) }
            }
            assertNull(calls("updateLaneInfo").last().payload["lanes"])
        }
    }

    @Test fun lanePrefetchIsBoundedBeforeGuidanceWithoutAssumingIndexOrder() {
        initialize()
        val cache = field("lanesByIndex").get(EcarxHudBridge) as Map<*, *>
        for (index in listOf(100) + (0..31)) {
            send(0x5204) {
                u16(0, 42); u16(1, index); group(2) { u16(0, 0); u8(1, 2); i16(2, 90) }
            }
            assertTrue(cache.size <= 32)
        }
        assertEquals((0..31).toSet(), cache.keys)
        assertTrue(calls("updateLaneInfo").isEmpty())
        maneuver(); guidance()
        send(0x5201) { u16(0x10, 0) }
        assertNotNull(calls("updateLaneInfo").last().payload["lanes"])
    }

    @Test fun cachePressureKeepsTheCurrentLaneEvenWhenHiddenAndCountsUnmappableEvents() {
        initialize(); maneuver(); guidance()
        send(0x5201) { u16(0x10, 0) }
        send(0x5204) {
            u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 2); i16(2, -90) }
        }
        val current = calls("updateLaneInfo").last().payload["lanes"]
        send(0x5201) { u8(0x12, 0) }
        val cache = field("lanesByIndex").get(EcarxHudBridge) as Map<*, *>
        for (index in 1..64) {
            send(0x5204) {
                u16(0, 42); u16(1, index); group(2) { u16(0, 0); u8(1, 2); i16(2, 12) }
            }
            assertTrue(cache.size <= 32)
            assertTrue(cache.containsKey(0))
        }
        assertEquals(setOf(0) + (34..64), cache.keys)
        send(0x5201) { u8(0x12, 1) }
        assertEquals(current, calls("updateLaneInfo").last().payload["lanes"])
        // Replacing an existing event must not evict another slot.
        send(0x5204) {
            u16(0, 42); u16(1, 64); group(2) { u16(0, 0); u8(1, 2); i16(2, 90) }
        }
        assertEquals(setOf(0) + (34..64), cache.keys)
    }

    @Test fun onlyLaneCursorChangesRetireOldEventsAndReplanningCanPrefetchLowerIndices() {
        initialize(); maneuver(); guidance()
        val cache = field("lanesByIndex").get(EcarxHudBridge) as Map<*, *>
        for (index in 0..3) {
            send(0x5204) {
                u16(0, 42); u16(1, index); group(2) { u16(0, 0); u8(1, 2); i16(2, -90) }
            }
        }
        send(0x5201) { u16(0x10, 0) }
        maneuver(index = 8)
        send(0x5201) { u16List(0x0d, listOf(8)); u32(0x0a, 100) }
        assertEquals((0..3).toSet(), cache.keys)
        for (cursor in 1..3) {
            send(0x5201) { u16(0x10, cursor) }
            assertEquals((cursor..3).toSet(), cache.keys)
            assertNotNull(calls("updateLaneInfo").last().payload["lanes"])
        }
        // New-route prefetch may arrive while the old cursor is still selected.
        send(0x5204) {
            u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 2); i16(2, 90) }
        }
        send(0x5201) { u32(0x0a, 50) }
        send(0x5201) { u16(0x10, 3) }
        assertEquals(setOf(0, 3), cache.keys)
        send(0x5201) { u16(0x10, 0) }
        assertEquals(listOf(mapOf("number" to 1, "iconId" to 18, "bitmap" to null)),
            calls("updateLaneInfo").last().payload["lanes"])
        send(0x5201) { u8(1, 2) }
        assertTrue(cache.isEmpty())
        maneuver(); guidance()
        send(0x5201) { u16(0x10, 0) }
        assertNull(calls("updateLaneInfo").last().payload["lanes"])
        send(0x5204) {
            u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 2); i16(2, 0) }
        }
        assertEquals(listOf(mapOf("number" to 1, "iconId" to 15, "bitmap" to null)),
            calls("updateLaneInfo").last().payload["lanes"])
        EcarxHudBridge.clear()
        assertTrue(cache.isEmpty())
    }

    @Test fun pendingLanesAreWithdrawnOnDisconnectAndExplicitClear() {
        initialize()
        for (clear in listOf<() -> Unit>(
            { EcarxHudBridge.clear() },
            { send(0x5204) {} },
        )) {
            send(0x5204) {
                u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 2); i16(2, 90) }
            }
            clear()
            maneuver(); guidance()
            assertTrue(calls("updateLaneInfo").none { it.payload["lanes"] != null })
            EcarxHudBridge.clear()
        }
    }

    @Test fun dimCreationFromWorkerAndTickerUsesABackgroundLooper() {
        DimInteraction.requireLooper = true
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            worker.submit { initialize() }.get(3, TimeUnit.SECONDS)
            awaitDimWorker()
            assertNotNull(field("dim").get(EcarxHudBridge))
            set("dim", null)
            set("navi", null)
            worker.submit { tick() }.get(3, TimeUnit.SECONDS)
            awaitDimWorker()
            assertNotNull(field("navi").get(EcarxHudBridge))
            assertEquals(2, calls("create").size)
            calls("create").forEach { assertNotEquals(Looper.getMainLooper(), it.payload["looper"]) }
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun coldStartDefaultsToVisibleRegardlessOfLegacyPreference() {
        for (saved in listOf(false, true)) {
            set("context", null)
            DimInteraction.reset()
            val prefs = context.getSharedPreferences("diplay_ecarx_outputs_v2", Context.MODE_PRIVATE)
            prefs.edit().clear().putBoolean("dim_presentation_switch", saved).commit()
            NavigationOutputs.onAppOpened(context)
            assertTrue(field("clusterVisibleWanted").getBoolean(EcarxHudBridge))
            assertFalse(prefs.contains("dim_presentation_switch"))
            repeat(12) { tick() }
            assertTrue(calls("setPresentationToDimSwitch").none { it.payload["show"] == true })
            assertFalse(EcarxOutputSettings.presentationReleasePending(context))
            assertTrue(panelRequests.isEmpty())
        }
    }

    @Test fun allDisplayControlsShareIntentAndRepeatedEventsRepairThePanel() {
        initialize(); maneuver(); guidance()
        val intents = mutableListOf<ClusterMapIntent>()
        NavigationOutputs.setClusterPanelOwner { intents += it }
        NavigationOutputs.requestPresentation(context, true, "cluster surface ready")
        fun fly(option: Int) {
            DimInteraction.instance().callback.onShowPresentationOptionChanged(option)
            shadowOf(Looper.getMainLooper()).idle()
        }
        fly(3); fly(3); fly(-1)
        assertEquals(listOf(ClusterMapIntent.SHOWN, ClusterMapIntent.HIDDEN, ClusterMapIntent.HIDDEN), intents)
        val before = calls("setPresentationToDimSwitch").size
        assertFalse(NavigationOutputs.requestPresentation(context, true, "stale surface"))
        repeat(25) { tick() }
        assertEquals(before, calls("setPresentationToDimSwitch").size)
        assertFalse(field("presentationWanted").getBoolean(EcarxHudBridge))
        fly(2); fly(2)
        assertEquals(listOf(ClusterMapIntent.SHOWN, ClusterMapIntent.SHOWN), intents.takeLast(2))
        assertTrue(field("clusterVisibleWanted").getBoolean(EcarxHudBridge))
        fly(1)
        assertEquals(ClusterMapIntent.SHOWN, intents.last())
        assertEquals(1, field("presentationOption").getInt(EcarxHudBridge))
        send(0x5201) { u8(1, 0) }
        assertEquals(ClusterMapIntent.NO_ROUTE, intents.last())
    }

    @Test fun theCruiseMapShowsWithoutARouteOnlyWhileConnectedAndOptedIn() {
        initialize()
        EcarxHudBridge.setCurrentSession { true }
        assertFalse(panelRequests.lastOrNull() ?: false)
        assertFalse(NavigationOutputs.requestPresentation(context, true, "no route"))

        EcarxOutputSettings.setCruiseMap(context, true)
        EcarxHudBridge.cruiseMapChanged()
        assertTrue(panelRequests.last())
        assertTrue(NavigationOutputs.requestPresentation(context, true, "cluster first texture frame"))
        assertEquals(true, calls("setPresentationToDimSwitch").last().payload["show"])
        assertFalse(EcarxHudBridge.isActive())

        // The cluster's own controls still win.
        DimInteraction.instance().callback.onShowPresentationOptionChanged(3)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(panelRequests.last())
        DimInteraction.instance().callback.onShowPresentationOptionChanged(2)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(panelRequests.last())

        EcarxHudBridge.setCurrentSession(null)
        assertFalse(panelRequests.last())
        assertFalse(NavigationOutputs.requestPresentation(context, true, "after disconnect"))
    }

    @Test fun aRouteEndKeepsTheCruiseMapUpAndTheSwitchOffTakesItDown() {
        EcarxOutputSettings.setCruiseMap(context, true)
        EcarxOutputSettings.setAutoShowMap(context, false)
        initialize()
        EcarxHudBridge.setCurrentSession { true }
        assertTrue(NavigationOutputs.requestPresentation(context, true, "cluster first texture frame"))
        maneuver(); guidance()
        assertTrue(EcarxHudBridge.isActive())
        // A map already up for cruising is not hidden by "show at route start" being off.
        assertTrue(panelRequests.last())

        val switches = calls("setPresentationToDimSwitch").size
        send(0x5201) { u8(1, 0) }
        assertFalse(EcarxHudBridge.isActive())
        assertTrue(panelRequests.last())
        assertEquals(switches, calls("setPresentationToDimSwitch").size)
        assertTrue(reports.any { "cluster map kept for cruising" in it })
        // Guidance stops on the cluster; the projection itself is not handed back.
        assertEquals("idle", DimInteraction.navi.navigationEvents.last())
        // The projection keepalive goes on without a route.
        repeat(3) { tick() }
        assertTrue(calls("setPresentationToDimSwitch").size > switches)
        assertEquals(true, calls("setPresentationToDimSwitch").last().payload["show"])

        EcarxOutputSettings.setCruiseMap(context, false)
        EcarxHudBridge.cruiseMapChanged()
        assertFalse(panelRequests.last())
        assertTrue(NavigationOutputs.requestPresentation(context, false, "cluster surface detached"))
        assertEquals(false, calls("setPresentationToDimSwitch").last().payload["show"])
    }

    @Test fun withoutTheCruiseMapARouteEndStillReleasesTheCluster() {
        initialize()
        EcarxHudBridge.setCurrentSession { true }
        maneuver(); guidance()
        assertTrue(NavigationOutputs.requestPresentation(context, true, "cluster first texture frame"))
        send(0x5201) { u8(1, 0) }
        assertFalse(panelRequests.last())
        assertEquals(false, calls("setPresentationToDimSwitch").last().payload["show"])
    }

    @Test fun navigationDetailsFollowTheDebugSwitchWithoutStoppingOutput() {
        NavigationOutputs.attachReport { reports += it }
        initialize()
        var debug = false
        NavigationOutputs.debugLogsEnabled = { debug }
        maneuver(); guidance()
        assertTrue(calls("updateNavigationInfo").isNotEmpty())
        assertFalse(reports.any { it.startsWith("DIM input") || it.startsWith("DIM publish") })
        NavigationOutputs.requestPresentation(context, true, "surface ready")
        assertTrue(reports.any { "reassert route=" in it && "nextMeters=" !in it })
        debug = true
        guidance(distance = 200)
        NavigationOutputs.requestPresentation(context, true, "surface ready")
        assertTrue(reports.any { it.startsWith("DIM input") })
        assertTrue(reports.any { it.startsWith("DIM publish") })
        assertTrue(reports.any { "reassert route=" in it && "nextMeters=200" in it })
        reports.clear()
        debug = false
        guidance(distance = 100)
        repeat(10) { NavigationOutputs.requestPresentation(context, true, "surface ready") }
        assertFalse(reports.any { it.startsWith("DIM input") || it.startsWith("DIM publish") })
        assertFalse(reports.any { "reassert route=" in it })
    }

    @Test fun unknownPlatformPolicyUsesDefaultIntentButWaitsForRouteAndSurface() {
        context.getSharedPreferences("diplay_ecarx_outputs_v2", Context.MODE_PRIVATE).edit().clear().commit()
        NavigationOutputs.attachReport { reports += it }
        NavigationOutputs.debugLogsEnabled = { false }
        initialize()
        assertFalse(NavigationOutputs.requestPresentation(context, true, "early surface"))
        assertTrue(calls("setPresentationToDimSwitch").isEmpty())
        maneuver(); guidance()
        assertTrue(panelRequests.last())
        assertTrue(calls("setPresentationToDimSwitch").isEmpty())
        send(0x5204) {
            u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 2); i16(2, 90) }
        }
        val lane = calls("updateLaneInfo").last().payload["lanes"] as List<*>
        assertEquals(1, lane.size)
        guidance(distance = 345) // No video frame or platform option has arrived.
        assertEquals(345, calls("updateDistanceToNextGuidancePoint").last().payload["meters"])
        assertEquals(lane, calls("updateLaneInfo").last().payload["lanes"])
        assertTrue(calls("setPresentationToDimSwitch").isEmpty())
        assertTrue(NavigationOutputs.requestPresentation(context, true, "independent map first frame"))
        assertTrue(reports.any { "sdkSwitch=true platformOption=unknown result=ok" in it })
        DimInteraction.instance().callback.onShowPresentationOptionChanged(3)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(NavigationOutputs.requestPresentation(context, false, "surface detached"))
        DimInteraction.instance().callback.onShowPresentationOptionChanged(2)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(NavigationOutputs.requestPresentation(context, true, "replacement surface"))
        val option = reports.indexOfLast { "map option=2 " in it }
        assertTrue(option >= 0)
        assertTrue(reports.indexOfLast { "sdkSwitch=true platformOption=2 result=ok" in it } > option)
        assertTrue(calls("notifyTurnByTurnStopped").isEmpty())
    }

    @Test fun aHiddenChoiceSurvivesReconnectionButColdStartRestoresTheDefault() {
        initialize(); maneuver(); guidance()
        DimInteraction.instance().callback.onShowPresentationOptionChanged(3)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(field("clusterVisibleWanted").getBoolean(EcarxHudBridge))
        EcarxHudBridge.clear()
        initialize(); maneuver(); guidance()
        assertFalse(panelRequests.last())
        EcarxHudBridge.clear()
        for (name in listOf("context", "dim", "navi", "dimCallback", "naviCallback")) set(name, null)
        set("presentationOption", 0)
        set("clusterVisibleWanted", false)
        set("presentationWanted", false)
        FixtureLog.reset()
        panelRequests.clear()
        initialize(); maneuver(); guidance()
        assertTrue(EcarxHudBridge.isActive())
        assertTrue(panelRequests.last())
        assertTrue(field("clusterVisibleWanted").getBoolean(EcarxHudBridge))
        assertTrue(calls("setPresentationToDimSwitch").isEmpty())
    }

    @Test fun routeEndAndWindowCleanupKeepTheLastExplicitDisplayChoice() {
        initialize(); maneuver(); guidance()
        NavigationOutputs.requestPresentation(context, true, "cluster surface ready")
        send(0x5201) { u8(1, 0) }
        NavigationOutputs.requestPresentation(context, false, "cluster surface detached")
        assertTrue(field("clusterVisibleWanted").getBoolean(EcarxHudBridge))
        assertFalse(panelRequests.last())
        maneuver(); guidance()
        assertTrue(panelRequests.last())
    }

    @Test fun wheelExitWithoutAPhoneHandlerDoesNotClearTheRoute() {
        initialize(); maneuver(); guidance()
        val panels = panelRequests.toList()
        val stopped = calls("notifyTurnByTurnStopped").size
        NavigationOutputs.debugLogsEnabled = { false }
        DimInteraction.navi.callback.onDoInteractionAction(2, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(EcarxHudBridge.isActive())
        assertNotNull(route.current())
        assertEquals(panels, panelRequests.toList())
        assertEquals(stopped, calls("notifyTurnByTurnStopped").size)
        assertTrue(reports.any { it.contains("navigation exit requested action=2 queued=false") })
        assertEquals(context.getString(R.string.carapp_navigation_exit_unavailable), ShadowToast.getTextOfLatestToast())
        assertFalse(reports.any { it.contains("exit completed") })
    }

    @Test fun wheelExitWaitsForThePhoneBeforeClearingClusterGuidance() {
        initialize()
        maneuver(); guidance()
        var requests = 0
        NavigationOutputs.requestNavigationExit = { requests++; true }
        val stopped = calls("notifyTurnByTurnStopped").size
        DimInteraction.navi.callback.onDoInteractionAction(2, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, requests)
        assertTrue(EcarxHudBridge.isActive())
        assertNotNull(route.current())
        assertEquals(stopped, calls("notifyTurnByTurnStopped").size)
        assertTrue(panelRequests.last())
        send(0x5201) { u8(1, 0) }
        assertFalse(EcarxHudBridge.isActive())
        assertNull(route.current())
        assertEquals(stopped + 1, calls("notifyTurnByTurnStopped").size)
        assertFalse(panelRequests.last())
        assertTrue(field("clusterVisibleWanted").getBoolean(EcarxHudBridge))
    }

    @Test fun startupWithoutResidueDoesNotTouchProjection() {
        NavigationOutputs.onAppOpened(context)
        repeat(12) { tick() }
        assertTrue(calls("setPresentationToDimSwitch").isEmpty())
    }

    @Test fun startupReleaseWorksWithNavigationDisabledAndWaitsForDim() {
        context.getSharedPreferences("diplay_ecarx_outputs_v2", Context.MODE_PRIVATE).edit()
            .putBoolean("dim_navigation_enabled", false).commit()
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
        context.getSharedPreferences("diplay_ecarx_outputs_v2", Context.MODE_PRIVATE).edit()
            .putBoolean("dim_presentation_switch", true).commit()
        EcarxOutputSettings.setPresentationReleasePending(context, true)
        DimInteraction.nullCreate = true
        NavigationOutputs.onAppOpened(context)
        assertTrue(field("clusterVisibleWanted").getBoolean(EcarxHudBridge))
        assertTrue(EcarxOutputSettings.presentationReleasePending(context))
        set("context", null)
        set("releaseStalePresentation", false)
        context.getSharedPreferences("diplay_ecarx_outputs_v2", Context.MODE_PRIVATE).edit()
            .putBoolean("dim_navigation_enabled", false).commit()
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
        repeat(2) { tick() }
        assertFalse(EcarxOutputSettings.presentationReleasePending(context))
        repeat(12) { tick() }
        assertEquals(listOf(false, false), calls("setPresentationToDimSwitch").map { it.payload["show"] })
    }

    @Test fun startupFailuresBackOffAndDiagnosticsIdentifyTheResidue() {
        EcarxOutputSettings.setPresentationReleasePending(context, true)
        DimInteraction.failPresentation = true
        initialize()
        repeat(60) { tick() }
        assertEquals(30, calls("setPresentationToDimSwitch").size)
        repeat(20) { tick() }
        assertEquals(32, calls("setPresentationToDimSwitch").size)
        val diagnostic = NavigationOutputs.diagnosticReport(context)
        assertTrue(diagnostic.contains("releasePending=true"))
        assertTrue(diagnostic.contains("startupReleaseOrigin=pending"))
        assertEquals(2, reports.count { it.startsWith("startup projection release call origin=pending") })
        DimInteraction.failPresentation = false
        repeat(10) { tick() }
        assertFalse(EcarxOutputSettings.presentationReleasePending(context))
        assertTrue(NavigationOutputs.diagnosticReport(context).contains("releasePending=false"))
    }

    @Test fun onlyTheReadyWindowSupersedesStartupRelease() {
        EcarxOutputSettings.setPresentationReleasePending(context, true)
        DimInteraction.nullCreate = true
        NavigationOutputs.onAppOpened(context)
        assertFalse(NavigationOutputs.requestPresentation(context, true, "early surface"))
        assertTrue(calls("setPresentationToDimSwitch").isEmpty())
        DimInteraction.nullCreate = false
        tick()
        assertEquals(false, calls("setPresentationToDimSwitch").single().payload["show"])
        maneuver(); guidance()
        NavigationOutputs.requestPresentation(context, true, "cluster surface ready")
        NavigationOutputs.onAppOpened(context)
        FixtureLog.reset()
        repeat(12) { tick() }
        assertTrue(calls("setPresentationToDimSwitch").isNotEmpty())
        assertTrue(calls("setPresentationToDimSwitch").all { it.payload["show"] == true })
        NavigationOutputs.requestPresentation(context, false, "cluster surface detached")
        assertTrue(EcarxOutputSettings.presentationReleasePending(context))
        assertTrue("Window cleanup must not erase the user choice", field("clusterVisibleWanted").getBoolean(EcarxHudBridge))
        send(0x5201) { u8(1, 0) }
        assertFalse(EcarxOutputSettings.presentationReleasePending(context))
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

    @Test fun manualDisplayIntentWaitsForTheWindowAndStopsRetriesWhenHidden() {
        initialize(); maneuver(); guidance()
        assertTrue(field("clusterVisibleWanted").getBoolean(EcarxHudBridge))
        assertTrue(field("clusterVisibleWanted").getBoolean(EcarxHudBridge))
        assertTrue(calls("setPresentationToDimSwitch").isEmpty())
        NavigationOutputs.requestPresentation(context, true, "cluster surface ready")
        val enabled = calls("setPresentationToDimSwitch").single().payload
        assertEquals(true, enabled["show"])
        assertEquals(1, enabled["screenId"])
        assertEquals("", enabled["packageName"])
        assertEquals("", enabled["className"])
        repeat(12) { tick() }
        assertTrue(calls("setPresentationToDimSwitch").size > 1)
        DimInteraction.instance().callback.onShowPresentationOptionChanged(3)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(field("clusterVisibleWanted").getBoolean(EcarxHudBridge))
        NavigationOutputs.requestPresentation(context, false, "cluster surface detached")
        val stopped = calls("setPresentationToDimSwitch").size
        repeat(12) { tick() }
        assertEquals(stopped, calls("setPresentationToDimSwitch").size)
    }

    @Test fun aSavedChoiceWaitsForTheDimServiceAndRouteBeforeDelivery() {
        DimInteraction.nullCreate = true
        assertTrue(EcarxCapabilities.detect(context).identified)
        initialize()
        assertTrue(field("clusterVisibleWanted").getBoolean(EcarxHudBridge))
        assertTrue(field("clusterVisibleWanted").getBoolean(EcarxHudBridge))
        assertTrue(calls("setPresentationToDimSwitch").isEmpty())
        DimInteraction.nullCreate = false
        tick()
        maneuver(); guidance()
        assertTrue(panelRequests.last())
        assertTrue(NavigationOutputs.requestPresentation(context, true, "cluster surface ready"))
        assertEquals(true, calls("setPresentationToDimSwitch").last().payload["show"])
    }

    @Test fun surfaceDetachStopsRetriesEvenWhenTheDimHandleIsUnavailable() {
        initialize(); maneuver(); guidance()
        set("dim", null)
        DimInteraction.nullCreate = true
        assertTrue(NavigationOutputs.requestPresentation(context, true, "cluster surface ready"))
        assertTrue(NavigationOutputs.requestPresentation(context, false, "cluster surface detached"))
        assertTrue(field("clusterVisibleWanted").getBoolean(EcarxHudBridge))
        DimInteraction.nullCreate = false
        repeat(12) { tick() }
        assertTrue(calls("setPresentationToDimSwitch").isEmpty())
    }

    @Test fun hidingTheMapPreservesNavigationAndLanesWithAndWithoutNewPhoneData() {
        initialize(); maneuver(); guidance()
        send(0x5204) {
            u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 2); i16(2, 90) }
        }
        val lane = calls("updateLaneInfo").last().payload["lanes"]
        for (newPhoneData in listOf(false, true)) {
            DimInteraction.instance().callback.onShowPresentationOptionChanged(2)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(NavigationOutputs.requestPresentation(context, true, "ready surface"))
            if (newPhoneData) guidance(distance = 300)
            assertFalse(DimInteraction.navi.resendOngoing)
            DimInteraction.instance().callback.onShowPresentationOptionChanged(3)
            shadowOf(Looper.getMainLooper()).idle()
            val switches = calls("setPresentationToDimSwitch").size
            val publications = calls("updateNavigationInfo").size
            val events = DimInteraction.navi.navigationEvents.toList()
            assertTrue(NavigationOutputs.requestPresentation(context, false, "surface detached"))
            assertEquals(switches, calls("setPresentationToDimSwitch").size)
            assertEquals(publications, calls("updateNavigationInfo").size)
            assertEquals(events, DimInteraction.navi.navigationEvents)
            assertFalse(panelRequests.last())
            assertTrue(EcarxHudBridge.isActive())
            assertTrue(DimInteraction.navi.ongoingNavigationActive)
            assertTrue("DIM must be active even when the SDK deduplicates the aggregate", DimInteraction.navi.handlerNavigationActive)
            assertFalse(DimInteraction.navi.navigationMute)
            assertEquals(lane, calls("updateLaneInfo").last().payload["lanes"])
            assertTrue(calls("notifyTurnByTurnStopped").isEmpty())
            assertTrue(DimInteraction.navi.ongoingTransitions.all { it })
        }
        guidance(distance = 125)
        send(0x5204) {
            u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 2); i16(2, -90) }
        }
        assertFalse(panelRequests.last())
        assertEquals(125L, calls("updateNavigationInfo").last().payload["getDistanceToNextGuidancePoint"])
        assertEquals(16, ((calls("updateLaneInfo").last().payload["lanes"] as List<*>).single() as Map<*, *>)["iconId"])
        assertTrue(DimInteraction.navi.handlerNavigationActive)
        val restored = calls("updateMuteState").size
        send(0x5201) { u8(1, 0) }
        NavigationOutputs.requestPresentation(context, false, "route ended")
        assertFalse(DimInteraction.navi.handlerNavigationActive)
        assertEquals(restored, calls("updateMuteState").size)
        assertNull(calls("updateLaneInfo").last().payload["lanes"])
    }

    @Test fun missingTurnAcrossTickAndMapSwitchKeepsOwnershipAndAcceptsCurrentLane() {
        installFactoryNavigation(); initialize()
        for (switchFirst in listOf(false, true)) {
            maneuver(); guidance()
            send(0x5201) { u16List(0x0d, listOf(8)); u16(0x10, 2); u8(0x12, 1) }
            if (!switchFirst) tick()
            NavigationOutputs.requestPresentation(context, false, "hidden during gap")
            send(0x5204) {
                u16(0, 42); u16(1, 2); group(2) { u16(0, 0); u8(1, 2); i16(2, -90) }
            }
            assertTrue("ownership lost during gap", EcarxHudBridge.isActive())
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                context.packageManager.getApplicationEnabledSetting(FACTORY_PACKAGE))
            assertTrue(NavigationOutputs.requestPresentation(context, true, "replacement during gap"))
            assertEquals("ongoingNavi", DimInteraction.navi.navigationEvents.last())
            val lane = calls("updateLaneInfo").last().payload["lanes"] as List<*>
            assertEquals(16, (lane.single() as Map<*, *>)["iconId"])
            assertEquals(0, calls("notifyTurnByTurnStopped").size)
            maneuver(index = 8)
            send(0x5201) { u32(0x0a, 150) }
            assertEquals(150, calls("updateDistanceToNextGuidancePoint").last().payload["meters"])
            assertEquals(1, calls("notifyTurnByTurnStarted").size)
            send(0x5201) { u8(1, 0) }
            assertFactoryRestored()
            FixtureLog.reset()
        }
    }

    @Test fun fallbackLaneRequiresFreshContentsAfterTheTurnChanges() {
        initialize(); maneuver(); guidance()
        fun lane(angle: Int) = send(0x5204) {
            u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 2); i16(2, angle) }
        }
        lane(-90)
        assertNotNull(calls("updateLaneInfo").last().payload["lanes"])
        send(0x5201) { u16List(0x0d, listOf(8)); u32(0x0a, 121) }
        maneuver(index = 8)
        lane(-90)
        assertNull(calls("updateLaneInfo").last().payload["lanes"])
        lane(90)
        assertNotNull(calls("updateLaneInfo").last().payload["lanes"])
    }

    @Test fun destinationFollowsItsOwnFieldAndDebugOffReportContainsOnlyTransitions() {
        NavigationOutputs.attachReport { reports += it }
        NavigationOutputs.debugLogsEnabled = { false }
        initialize()
        send(0x5201) { u8(1, 1); string(4, "private-end") }
        assertFalse(EcarxHudBridge.isActive())
        assertTrue(calls("updateAddresses").isEmpty())
        maneuver(road = "private-street"); guidance()
        assertEquals("private-end", DimInteraction.navi.destinationName)
        val writes = calls("updateAddresses").size
        guidance(distance = 349)
        assertEquals(writes, calls("updateAddresses").size)
        send(0x5201) { string(4, "private-new-end") }
        assertEquals("private-new-end", DimInteraction.navi.destinationName)
        send(0x5201) { string(4, "未知位置") }
        assertEquals("", DimInteraction.navi.destinationName)
        send(0x5201) { string(4, "private-new-end") }
        send(0x5201) { u8(1, 0); string(4, "ignored") }
        assertEquals("", DimInteraction.navi.destinationName)
        assertEquals("naviAppOff", DimInteraction.navi.navigationEvents.last())
        assertTrue(reports.any { "phase=waiting" in it })
        assertTrue(reports.any { "firstTurnMs=" in it && "phase=guiding" in it })
        assertTrue(reports.none { "private-" in it })
        maneuver(); guidance()
        assertEquals("", DimInteraction.navi.destinationName)
    }

    @Test fun unknownOrQuietStartupCannotClaimDimAndUnmeasuredTurnCannotPublishZero() {
        initialize()
        for (state in listOf(3, 6, 99)) send(0x5201) { u8(1, state) }
        maneuver()
        assertFalse(EcarxHudBridge.isActive())
        send(0x5201) { u8(1, 1); u16List(0x0d, listOf(7)) }
        assertTrue(calls("updateNavigationInfo").isEmpty())
        send(0x5201) { u32(0x0a, 0) }
        assertTrue(EcarxHudBridge.isActive())
        assertEquals(0L, calls("updateNavigationInfo").last().payload["getDistanceToNextGuidancePoint"])
    }

    @Test fun repeatedMapDeliveryRestoresTheFinalEventAndLiveLaneData() {
        initialize(); maneuver(); guidance()
        send(0x5204) {
            u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 2); i16(2, 90) }
        }
        assertTrue(NavigationOutputs.requestPresentation(context, true, "first surface"))
        guidance(distance = 349)
        assertEquals("ongoingNavi", DimInteraction.navi.navigationEvents.last())
        for (distance in listOf(125, 124, 123)) {
            DimInteraction.instance().callback.onShowPresentationOptionChanged(3)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(NavigationOutputs.requestPresentation(context, false, "surface detached"))
            guidance(distance = distance)
            send(0x5204) {
                u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 2); i16(2, -90) }
            }
            assertTrue(EcarxHudBridge.isActive())
            assertFalse(panelRequests.last())
            assertEquals(distance, calls("updateDistanceToNextGuidancePoint").last().payload["meters"])
            val lane = calls("updateLaneInfo").last().payload["lanes"]
            assertEquals(16, ((lane as List<*>).single() as Map<*, *>)["iconId"])
            assertEquals("ongoingNavi", DimInteraction.navi.navigationEvents.last())
            assertTrue(calls("notifyTurnByTurnStopped").isEmpty())

            DimInteraction.instance().callback.onShowPresentationOptionChanged(2)
            shadowOf(Looper.getMainLooper()).idle()
            val start = DimInteraction.navi.navigationEvents.size
            val traceStart = FixtureLog.snapshot().size
            assertTrue(NavigationOutputs.requestPresentation(context, true, "replacement surface"))
            guidance(distance = distance) // Unchanged phone data must not be required to repair DIM state.
            val events = DimInteraction.navi.navigationEvents.drop(start)
            assertEquals("firstFrameReady", events.first())
            assertEquals("ongoingNavi", events.last())
            assertFalse(events.any { it == "idle" || it == "naviAppOff" })
            assertEquals(lane, calls("updateLaneInfo").last().payload["lanes"])
            val trace = FixtureLog.snapshot().drop(traceStart)
            assertTrue(trace.indexOf("setPresentationToDimSwitch") < trace.indexOf("navigation:firstFrameReady"))
            assertTrue(trace.indexOf("navigation:firstFrameReady") < trace.indexOf("navigation:ongoingNavi"))
            assertTrue(trace.lastIndexOf("navigation:ongoingNavi") < trace.lastIndexOf("updateLaneInfo"))
            repeat(12) {
                tick()
                assertEquals("ongoingNavi", DimInteraction.navi.navigationEvents.last())
            }
        }
    }

    @Test fun routeEndClosesNavigationEvenWhenTheMapIsAlreadyHidden() {
        initialize()
        val finalEvents = linkedMapOf<Int, String>()
        for (endState in listOf(0, 2)) {
            DimInteraction.instance().callback.onShowPresentationOptionChanged(2)
            shadowOf(Looper.getMainLooper()).idle()
            maneuver(); guidance()
            assertTrue(NavigationOutputs.requestPresentation(context, true, "surface ready"))
            DimInteraction.instance().callback.onShowPresentationOptionChanged(3)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(NavigationOutputs.requestPresentation(context, false, "surface detached"))
            val start = DimInteraction.navi.navigationEvents.size
            val traceStart = FixtureLog.snapshot().size
            send(0x5201) { u8(1, endState) }
            assertFalse(EcarxHudBridge.isActive())
            assertFalse(DimInteraction.navi.ongoingNavigationActive)
            assertNull(calls("updateLaneInfo").last().payload["lanes"])
            val events = DimInteraction.navi.navigationEvents.drop(start)
            assertEquals("idle", events.first())
            assertFalse(events.contains("ongoingNavi"))
            finalEvents[endState] = events.last()
            val trace = FixtureLog.snapshot().drop(traceStart)
            assertTrue(trace.indexOf("navigation:idle") < trace.indexOf("notifyTurnByTurnStopped"))
            assertTrue(trace.lastIndexOf("updateLaneInfo") < trace.lastIndexOf("navigation:naviAppOff"))
            val count = DimInteraction.navi.navigationEvents.size
            repeat(12) { tick() }
            assertEquals(count, DimInteraction.navi.navigationEvents.size)
        }
        assertEquals(mapOf(0 to "naviAppOff", 2 to "naviAppOff"), finalEvents)
    }

    @Test fun routeEndDoesNotWaitForTheWindowsSurfaceTeardown() {
        initialize(); maneuver(); guidance()
        assertTrue(NavigationOutputs.requestPresentation(context, true, "surface ready"))
        send(0x5201) { u8(1, 0) }
        assertFalse(panelRequests.last())
        assertEquals("naviAppOff", DimInteraction.navi.navigationEvents.last())
        assertTrue(NavigationOutputs.requestPresentation(context, false, "late surface detached"))
        assertEquals("naviAppOff", DimInteraction.navi.navigationEvents.last())
        assertFalse(DimInteraction.navi.handlerNavigationActive)
        assertNull(calls("updateLaneInfo").last().payload["lanes"])
    }

    @Test fun aHideBeforeGuidanceCannotBeOverriddenByLateServiceOrRetry() {
        DimInteraction.nullNavi = true
        initialize()
        DimInteraction.instance().callback.onShowPresentationOptionChanged(3)
        shadowOf(Looper.getMainLooper()).idle()
        maneuver(); guidance()
        DimInteraction.nullNavi = false
        repeat(12) { tick() }
        initialize()
        assertTrue(EcarxHudBridge.isActive())
        assertFalse(panelRequests.last())
        assertFalse(NavigationOutputs.requestPresentation(context, true, "late surface"))
        assertTrue(calls("setPresentationToDimSwitch").isEmpty())
    }

    @Test fun laneStateClearsOnEndExpiryAndDisconnectWithoutReviving() {
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
        assertTrue(EcarxHudBridge.isActive())
        assertTrue(route.diagnosticState().contains("reason=empty-list-timeout"))
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
        assertTrue(reports.last { it.startsWith("DIM guidance ended") }.contains("reason=arrivedCandidate"))
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
        NavigationOutputs.onAppOpened(context)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
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
        for (end in listOf("no-route", "arrived", "disconnect")) {
            maneuver(); guidance()
            nowNs += 31_000_000_000L
            tick()
            assertTrue(EcarxHudBridge.isActive())
            send(0x5201) { u16List(0x0d, emptyList()) }
            nowNs += 3_000_000_000L
            tick()
            assertTrue(EcarxHudBridge.isActive())
            assertTrue(panelRequests.last())
            when (end) {
                "no-route" -> send(0x5201) { u8(1, 0) }
                "arrived" -> send(0x5201) { u8(1, 2) }
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
        assertEquals(listOf(false, false, false), calls("setPresentationToDimSwitch").map { it.payload["show"] })
        assertEquals(3, reports.count { it.startsWith("DIM lifecycle start status=1 aggregate=ok notify=ok") })
        assertEquals(3, reports.count { it.startsWith("DIM lifecycle stop status=0 aggregate=ok notify=ok") })
        assertEquals(6, reports.count { it.startsWith("DIM lifecycle") && it.contains("method not found") })
    }

    @Test fun sdkStartAndEndAreDistinctFromSnapshotAndDoNotRepeatOnKeepalive() {
        initialize()
        set("navi", EcarxDimApi.NaviHandle(NaviWithStatus()))
        maneuver(); guidance()
        assertTrue(names().indexOf("updateNavigationInfo") < names().indexOf("notifyNavigationStatus"))
        assertTrue(names().indexOf("notifyNavigationStatus") < names().indexOf("notifyTurnByTurnStarted"))
        repeat(10) { tick() }
        guidance(distance = 300)
        assertEquals(listOf(2), calls("notifyNavigationStatus").map { it.payload["status"] })
        send(0x5201) { u8(1, 0) }
        assertTrue(names().lastIndexOf("updateNavigationInfo") < names().lastIndexOf("notifyNavigationStatus"))
        assertTrue(names().lastIndexOf("notifyNavigationStatus") < names().lastIndexOf("notifyTurnByTurnStopped"))
        maneuver(); guidance()
        assertEquals(listOf(2, 3, 2), calls("notifyNavigationStatus").map { it.payload["status"] })
        assertEquals(listOf(false), calls("setPresentationToDimSwitch").map { it.payload["show"] })
    }

    @Test fun guidancePausesFactoryNavigationBeforeFirstPublishAndRestoresAcrossAllExitPaths() {
        installFactoryNavigation(); initialize()
        // The DIM fixture checks the package state at the instant of publication.
        set("navi", EcarxDimApi.NaviHandle(NaviCheckingFactory(context)))
        repeat(5) { cycle ->
            maneuver(); guidance()
            assertTrue(EcarxHudBridge.isActive())
            when (cycle) {
                0 -> send(0x5201) { u8(1, 0) }
                1 -> send(0x5201) { u8(1, 2) }
                2 -> EcarxHudBridge.clear()
                3 -> { nowNs += 30_000_000_000L; tick() }
                else -> {
                    NavigationOutputs.endNow()
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
        set("navi", EcarxDimApi.NaviHandle(NaviCheckingFactory(context)))
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
        NavigationOutputs.setClusterPanelOwner { if (it == ClusterMapIntent.NO_ROUTE) error("panel cleanup failed") }
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
        set("navi", EcarxDimApi.NaviHandle(target))
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
        assertEquals(listOf(true, false, true), DimInteraction.navi.ongoingTransitions)
        assertTrue(EcarxHudBridge.isActive())
        assertTrue(route.diagnosticState().contains("reason=empty-list-timeout"))
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

    @Test fun prefetchedLanesAfterAPublishFailureDoNotClaimOrClearTheFactoryDisplay() {
        initialize(); maneuver()
        FixtureLog.failOnce("updateNavigationInfo")
        guidance()
        val rollbackCalls = names().size
        send(0x5204) {
            u16(0, 42); u16(1, 0); group(2) { u16(0, 0); u8(1, 0); i16(2, 0) }
        }
        assertEquals(rollbackCalls, names().size)
        FixtureLog.reset()
        send(0x5201) { u8(1, 2) }
        assertTrue("Unowned prefetch must not clear the factory display", names().isEmpty())
        assertTrue(panelRequests.isEmpty())
    }

    @Test fun rejectedLaneWriteIsReportedAndRetriedOnExistingTicker() {
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

    @Test fun replacingSessionClearsOldOutputAndCachesBeforeInstallingNewProbe() {
        var sessionA = true
        var sessionB = true
        NavigationOutputs.start(context)
        awaitOutputWorker() // Let the DIM main-thread acquisition finish before exercising A/B.
        NavigationOutputs.onFrame(Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 2); string(4, "old road") })
        NavigationOutputs.onFrame(Iap2Messages.buildRaw(0x5201) {
            u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 100); string(4, "old destination")
        })
        NavigationOutputs.onSessionActive { sessionA }
        awaitOutputWorker()
        assertTrue(EcarxHudBridge.isActive()) // Frames before the first session remain valid.
        assertEquals("old destination", route.destinationName)
        val starts = calls("notifyTurnByTurnStarted").size
        NavigationOutputs.onSessionActive(newSession = false) { sessionA }
        awaitOutputWorker()
        assertTrue(EcarxHudBridge.isActive())
        assertEquals(starts, calls("notifyTurnByTurnStarted").size)
        assertTrue(calls("notifyTurnByTurnStopped").isEmpty())

        sessionA = false
        NavigationOutputs.onSessionActive(replacePrevious = true) { sessionB }
        awaitOutputWorker()
        assertFalse(EcarxHudBridge.isActive())
        assertNull(field("lastPushed").get(EcarxHudBridge))
        assertEquals("", route.destinationName)
        assertEquals(1, calls("notifyTurnByTurnStopped").size)
        assertFalse(panelRequests.last())
        assertTrue(route.diagnosticState().contains("sessionActive=true"))

        NavigationOutputs.onFrame(Iap2Messages.buildRaw(0x5201) {
            u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 200)
        })
        awaitOutputWorker()
        assertFalse(EcarxHudBridge.isActive()) // A's maneuver cannot revive B's route.
        NavigationOutputs.onFrame(Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 3) })
        awaitOutputWorker()
        assertTrue(EcarxHudBridge.isActive())
        assertEquals(starts + 1, calls("notifyTurnByTurnStarted").size)
        sessionB = false
        nowNs += 90_000_000_000L
        tick()
        assertFalse(EcarxHudBridge.isActive())
    }

    private fun hex(value: String): ByteArray =
        value.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    private fun awaitOutputWorker() {
        val worker = NavigationOutputs.javaClass.getDeclaredField("ecarx").apply { isAccessible = true }.get(NavigationOutputs)
        val executor = worker.javaClass.getDeclaredField("executor").apply { isAccessible = true }.get(worker) as ThreadPoolExecutor
        executor.submit {}.get(3, TimeUnit.SECONDS)
        awaitDimWorker()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitDimWorker() {
        val handler = EcarxHudBridge.javaClass.getDeclaredMethod("getDimHandler")
            .apply { isAccessible = true }.invoke(EcarxHudBridge) as android.os.Handler
        shadowOf(handler.looper).idle()
    }
}
