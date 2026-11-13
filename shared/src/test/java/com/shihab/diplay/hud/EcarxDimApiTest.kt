package com.shihab.diplay.hud

import android.cevt.dimsupportservice.DimSupportManager
import com.ecarx.xui.adaptapi.diminteraction.DimInteraction
import com.ecarx.xui.adaptapi.diminteraction.FixtureLog
import com.ecarx.xui.adaptapi.diminteraction.INaviInteraction
import com.ecarx.xui.adaptapi.diminteraction.NaviFixture
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
class EcarxDimApiTest {
    class NaviWithStatus : NaviFixture() {
        fun notifyNavigationStatus(status: Int) = FixtureLog.capture("notifyNavigationStatus", "status", status)
    }

    @Before fun setup() { DimInteraction.reset() }
    @After fun cleanup() { DimInteraction.reset() }

    @Test fun callbacksForwardOptionsAndActionsAndKeepObjectIdentity() {
        val dim = checkNotNull(EcarxDimApi.createDimInteraction(RuntimeEnvironment.getApplication()))
        val navi = checkNotNull(EcarxDimApi.naviInteraction(dim))
        var option: Int? = null
        val actions = mutableListOf<Int>()
        val dimCallback = checkNotNull(EcarxDimApi.registerInteractionCallback(dim, "test") { option = it })
        val naviCallback = checkNotNull(EcarxDimApi.registerNavigationInteractionCallback(navi, "test") { actions += it })
        assertSame(dimCallback, DimInteraction.instance().callback)
        assertSame(naviCallback, DimInteraction.navi.callback)
        DimInteraction.instance().callback.onShowPresentationOptionChanged(2)
        DimInteraction.navi.callback.onDoInteractionAction(42, null)
        DimInteraction.navi.callback.onSearchAddress(null)
        assertEquals(2, option)
        assertEquals(listOf(42), actions)
        for (callback in listOf(dimCallback, naviCallback)) {
            assertEquals(System.identityHashCode(callback), callback.hashCode())
            assertEquals(callback, callback)
            assertNotEquals(callback, Any())
            assertTrue(callback.toString().isNotBlank())
        }
    }

    @Test fun navigationPayloadIncludesTheOptionalRuntimeGetters() {
        val navi = EcarxDimApi.NaviHandle(object : NaviFixture() {
            override fun updateNavigationInfo(info: INaviInteraction.INavigationInfo) {
                assertEquals(2, info.dayNightMode)
                assertNull(info.extensionInfo)
                super.updateNavigationInfo(info)
            }
        })
        val result = EcarxDimApi.updateNavigationInfo(navi, EcarxDimApi.Guidance(dayNightMode = 2))
        assertTrue(result.reason, result.ok)
        val payload = FixtureLog.calls().single().payload
        assertEquals(0L, payload["getETA"])
        assertEquals("", payload["getNavigationTurnSVG"])
        for (getter in listOf("getLaneInfo", "getHighwayExitInfo", "getRoadCameraInfo", "getServiceAreaInfo")) {
            assertNull(payload[getter])
        }
    }

    interface FutureInfo {
        fun getLabel(): String
        fun getLanes(): Array<INaviInteraction.ILaneInfo>
        fun getItems(): List<String>
        fun getCount(): Int
        fun getKnownNull(): String?
    }

    @Test fun unknownGettersReturnEmptyValuesAndAreReportedOnce() {
        val reports = mutableListOf<String>()
        EcarxDimApi.attachReport { reports += it }
        val info = EcarxDimApi.proxy(FutureInfo::class.java, "FutureInfo") { method, _ ->
            if (method == "getKnownNull") null else EcarxDimApi.UNHANDLED
        } as FutureInfo
        assertEquals("", info.getLabel())
        assertEquals(0, info.getLanes().size)
        assertEquals(emptyList<String>(), info.getItems())
        assertEquals(0, info.getCount())
        assertNull(info.getKnownNull())
        info.getLabel()
        assertEquals(1, reports.count { it == "unhandled getter FutureInfo.getLabel: String" })
        assertTrue(reports.none { "getKnownNull" in it })
        EcarxDimApi.attachReport {}
    }

    @Test fun reflectiveFailuresReportTheVendorCauseAndMissingMethods() {
        FixtureLog.failOnce("updateETA")
        val failure = EcarxDimApi.updateEta(EcarxDimApi.NaviHandle(NaviFixture()), 600)
        assertFalse(failure.ok)
        assertTrue(failure.reason, failure.reason.contains("IllegalStateException: fixture failure: updateETA"))
        val missing = EcarxDimApi.updateEta(EcarxDimApi.NaviHandle(Any()), 600)
        assertFalse(missing.ok)
        assertTrue(missing.reason, missing.reason.contains("NoSuchMethodException"))
    }

    @Test fun managerRefreshReachesTheFirmwareEvenWhenSdkCachedAnInternallyFailedArrow() {
        val target = NaviFixture()
        val navi = EcarxDimApi.NaviHandle(target)
        DimSupportManager.failedIconWrites = 1
        repeat(2) { assertTrue(EcarxDimApi.updateTurnByTurnArrow(navi, 103).ok) }
        assertEquals(103, target.cachedArrowId)
        assertEquals(1, DimSupportManager.iconAttempts)
        assertTrue(DimSupportManager.writes.isEmpty())

        val snapshot = EcarxDimApi.Guidance(status = 1, turnId = 103, distanceToNextMeters = 350,
            nextGuidancePointName = "next-road", distanceToDestinationMeters = 9876, etaRemainingSeconds = 600)
        repeat(2) { assertTrue(EcarxDimApi.refreshNavigation(snapshot, "destination").ok) }
        assertEquals(3, DimSupportManager.iconAttempts)
        assertEquals(2, DimSupportManager.writes.count { it == "icon:0:103" })
        assertEquals(2, DimSupportManager.writes.count { it == "next:350:next-road" })
        assertEquals(2, DimSupportManager.writes.count { it == "distance:9876" })
        assertEquals(2, DimSupportManager.writes.count { it == "eta:600" })
        assertEquals(2, DimSupportManager.writes.count { it == "ongoing:true:destination:false" })
    }

    @Test fun managerRefreshOmitsUnknownTurnDataAndContinuesAfterAFieldFailure() {
        DimSupportManager.failNextTurn = true
        val failed = EcarxDimApi.refreshNavigation(EcarxDimApi.Guidance(status = 1, turnId = 103,
            distanceToNextMeters = 350, distanceToDestinationMeters = 9876, etaRemainingSeconds = 600), "")
        assertFalse(failed.ok)
        assertTrue(failed.reason.contains("next="))
        assertTrue(DimSupportManager.writes.containsAll(listOf("icon:0:103", "distance:9876", "eta:600")))

        DimSupportManager.reset()
        assertTrue(EcarxDimApi.refreshNavigation(EcarxDimApi.Guidance(status = 1,
            distanceToDestinationMeters = 9876, etaRemainingSeconds = 600), "").ok)
        assertEquals(listOf("ongoing:true::false", "distance:9876", "eta:600"), DimSupportManager.writes)
        DimSupportManager.available = false
        assertFalse(EcarxDimApi.refreshNavigation(EcarxDimApi.Guidance(status = 1), "").ok)
    }

    @Test fun destinationIsNameOnlyAndClearsWithoutReactivatingIdleNavigation() {
        val target = object : NaviFixture() {
            override fun updateAddresses(type: Int, addresses: MutableList<INaviInteraction.IAddress>) {
                assertEquals(INaviInteraction.TYPE_DESTINATION, type)
                addresses.singleOrNull()?.let {
                    assertTrue(it.latitude.isNaN())
                    assertTrue(it.longitude.isNaN())
                    assertEquals(-1L, it.distance)
                    assertEquals("", it.fullAddress)
                    assertEquals("", it.cityName)
                }
                super.updateAddresses(type, addresses)
            }
        }
        val navi = EcarxDimApi.NaviHandle(target)
        assertTrue(EcarxDimApi.updateNavigationInfo(navi,
            EcarxDimApi.Guidance(status = EcarxDimApi.NAVIGATION_SNAPSHOT_ACTIVE)).ok)
        assertTrue(EcarxDimApi.updateDestinationName(navi, "目的地").ok)
        assertEquals("目的地", target.destinationName)
        assertEquals(listOf("ongoingNavi", "ongoingNavi"), target.navigationEvents)
        assertTrue(EcarxDimApi.updateDestinationName(navi, "目的地").ok)
        assertEquals(2, target.navigationEvents.size)

        assertTrue(EcarxDimApi.updateNavigationInfo(navi,
            EcarxDimApi.Guidance(status = EcarxDimApi.NAVIGATION_SNAPSHOT_IDLE)).ok)
        assertTrue(EcarxDimApi.updateDestinationName(navi, "").ok)
        assertEquals("", target.destinationName)
        assertEquals(listOf("ongoingNavi", "ongoingNavi", "idle", "idle"), target.navigationEvents)
        val calls = FixtureLog.calls().filter { it.method == "updateAddresses" }
        assertEquals(listOf(1, 1, 0), calls.map { it.payload["count"] })
    }

    @Test fun destinationRejectionRetainsOldNameAndDoesNotBlockOtherDispatches() {
        val target = NaviFixture()
        val navi = EcarxDimApi.NaviHandle(target)
        assertTrue(EcarxDimApi.updateDestinationName(navi, "旧目的地").ok)
        FixtureLog.failOnce("updateAddresses")
        val rejected = EcarxDimApi.updateDestinationName(navi, "新目的地")
        assertFalse(rejected.ok)
        assertFalse(rejected.reason.contains("新目的地"))
        assertEquals("旧目的地", target.destinationName)
        assertTrue(EcarxDimApi.clearLaneInfo(navi).ok)
        assertTrue(EcarxDimApi.notifyTurnByTurnStopped(navi).ok)
        assertTrue(EcarxDimApi.updateDestinationName(navi, "新目的地").ok)
        FixtureLog.failOnce("updateAddresses")
        assertFalse(EcarxDimApi.updateDestinationName(navi, "").ok)
        assertEquals("新目的地", target.destinationName)
        assertTrue(EcarxDimApi.updateDestinationName(navi, "").ok)
        assertEquals("", target.destinationName)
    }

    @Test fun etaUsesRemainingSecondsInBothTheSnapshotAndSingleField() {
        val navi = EcarxDimApi.NaviHandle(NaviFixture())
        assertTrue(EcarxDimApi.updateNavigationInfo(navi,
            EcarxDimApi.Guidance(etaRemainingSeconds = 600)).ok)
        assertTrue(EcarxDimApi.updateEta(navi, 600).ok)
        assertEquals(600L, FixtureLog.calls().single { it.method == "updateNavigationInfo" }.payload["getETA"])
        assertEquals(600, FixtureLog.calls().single { it.method == "updateETA" }.payload["remainingSeconds"])
    }

    @Test fun lifecycleNotificationsUseSdkEventsRatherThanSnapshotValues() {
        val navi = EcarxDimApi.NaviHandle(NaviWithStatus())
        assertEquals(1, EcarxDimApi.NAVIGATION_SNAPSHOT_ACTIVE)
        assertEquals(0, EcarxDimApi.NAVIGATION_SNAPSHOT_IDLE)
        assertTrue(EcarxDimApi.notifyNavigationStarted(navi).ok)
        assertTrue(EcarxDimApi.notifyNavigationEnded(navi).ok)
        assertEquals(listOf(2, 3), FixtureLog.calls().map { it.payload["status"] })

        FixtureLog.failOnce("notifyNavigationStatus")
        assertFalse(EcarxDimApi.notifyNavigationEnded(navi).ok)
        assertTrue(EcarxDimApi.clearLaneInfo(navi).ok)
        assertFalse(EcarxDimApi.notifyNavigationStarted(EcarxDimApi.NaviHandle(NaviFixture())).ok)
    }
}
