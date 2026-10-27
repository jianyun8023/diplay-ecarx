package com.shilapi.xcertplay.hud

import android.content.Context
import com.ecarx.xui.adaptapi.diminteraction.DimInteraction
import com.ecarx.xui.adaptapi.diminteraction.FixtureLog
import com.ecarx.xui.adaptapi.diminteraction.INaviInteraction
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class EcarxDimApiFixtureTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() { DimInteraction.reset(); EcarxDimApi.attachReport {} }
    @After fun cleanup() { DimInteraction.reset(); EcarxDimApi.attachReport {} }

    @Test fun realReflectionFindsFactoryHandlesAndPresentationDescriptor() {
        assertTrue(EcarxCapabilities.dimPresent())
        assertTrue(EcarxCapabilities.available(context))
        val dim = EcarxDimApi.createDimInteraction(context)!!
        assertSame(DimInteraction.instance(), dim)
        assertSame(DimInteraction.navi, EcarxDimApi.naviInteraction(dim))
        assertSame(DimInteraction.media, EcarxDimApi.mediaInteraction(dim))
        assertSame(DimInteraction.phone, EcarxDimApi.phoneCallInteraction(dim))
        assertTrue(EcarxDimApi.setPresentationToDimSwitch(dim, true, 2, "fixture.package").ok)
        assertEquals(mapOf("screenId" to 2, "packageName" to "fixture.package",
            "className" to "fixture.package", "show" to true), FixtureLog.calls().last().payload)
        DimInteraction.presentationOption = 7
        assertEquals(7, EcarxDimApi.showPresentationOption(dim))
        assertEquals("int/string/bitmap", EcarxDimApi.arrowOverloads(DimInteraction.navi))
    }

    @Test fun navigationProxyIsReadThroughEveryRecoveredGetterAndTypedLaneArray() {
        val dim = EcarxDimApi.createDimInteraction(context)!!
        val navi = EcarxDimApi.naviInteraction(dim)!!
        val guidance = EcarxDimApi.Guidance(103, 350, 9_876, 1_800_000_000,
            drivingDirection = 2, status = 1, muteState = 1, nextGuidancePointName = "滨海路")
        assertTrue(EcarxDimApi.updateNavigationInfo(navi, guidance).ok)
        assertEquals(mapOf(
            "getNavigationTurnId" to 103, "getDistanceToNextGuidancePoint" to 350L,
            "getDistanceToDestination" to 9_876L, "getDrivingDirection" to 2,
            "getETA" to 1_800_000_000L, "getLaneInfo" to null, "getMuteState" to 1,
            "getNavigationStatus" to 1, "getNextGuidancePointName" to "滨海路",
            "getNavigationTurnSVG" to "", "getHighwayExitInfo" to null,
            "getRoadCameraInfo" to null, "getServiceAreaInfo" to null,
        ), FixtureLog.calls().last().payload)
        assertEquals(13, INaviInteraction.INavigationInfo::class.java.declaredMethods.size)
        assertTrue(EcarxDimApi.updateLaneInfo(navi, listOf(EcarxDimApi.Lane(1, 16))).ok)
        assertEquals(listOf(mapOf("number" to 1, "iconId" to 16, "bitmap" to null)),
            FixtureLog.calls().last().payload["lanes"])
        assertEquals(EcarxCapabilities.LANE_INFO, FixtureLog.calls().last().payload["componentType"])
        assertTrue(EcarxDimApi.clearLaneInfo(navi).ok)
        assertNull(FixtureLog.calls().last().payload["lanes"])
        assertNull(FixtureLog.calls().last().payload["count"])
        assertTrue(EcarxDimApi.updateNavigationInfo(navi, EcarxDimApi.Guidance()).ok)
        val empty = FixtureLog.calls().last().payload
        assertEquals(0L, empty["getDistanceToNextGuidancePoint"])
        assertEquals(0L, empty["getDistanceToDestination"])
        assertEquals(0L, empty["getETA"])
    }

    @Test fun nullHandlesAndThrownOemCallsReturnDiagnosableFailures() {
        val report = mutableListOf<String>()
        EcarxDimApi.attachReport { report += it }
        DimInteraction.nullCreate = true
        assertNull(EcarxDimApi.createDimInteraction(context))
        assertTrue(EcarxCapabilities.available(context))
        DimInteraction.nullCreate = false
        FixtureLog.failOnce("create")
        assertNull(EcarxDimApi.createDimInteraction(context))
        assertTrue(report.any { "IllegalStateException: fixture failure: create" in it })
        val dim = EcarxDimApi.createDimInteraction(context)!!
        DimInteraction.nullNavi = true
        assertNull(EcarxDimApi.naviInteraction(dim))
        FixtureLog.failOn("getMediaInteraction")
        assertNull(EcarxDimApi.mediaInteraction(dim))
        assertTrue(report.any { "getMediaInteraction failed" in it })
        FixtureLog.failOn("setPresentationToDimSwitch")
        val failed = EcarxDimApi.setPresentationToDimSwitch(dim, false)
        assertFalse(failed.ok)
        assertTrue(failed.reason.contains("fixture failure: setPresentationToDimSwitch"))
        val missingMethod = EcarxDimApi.updateEta(Any(), 100)
        assertFalse(missingMethod.ok)
        assertTrue(missingMethod.reason.contains("method not found"))
    }

    @Test fun callbackRegistrationAndPhonePayloadUseTheOemNestedInterfaces() {
        val dim = EcarxDimApi.createDimInteraction(context)!!
        assertNotNull(EcarxDimApi.registerInteractionCallback(dim, "fixture"))
        assertNotNull(EcarxDimApi.registerNavigationInteractionCallback(DimInteraction.navi, "fixture"))
        assertNotNull(DimInteraction.instance().callback)
        assertNotNull(DimInteraction.navi.callback)
        val phone = EcarxDimApi.phoneCallInteraction(dim)!!
        assertTrue(EcarxDimApi.updateCallInfo(phone,
            EcarxDimApi.CallInfo(3, 3, 1, 2, "张三", "+123")).ok)
        assertEquals(mapOf("getCallId" to 3, "getActiveCallId" to 3, "getCallCount" to 1,
            "getCallStatus" to 2, "getContactName" to "张三", "getContactNumber" to "+123",
            "getAvatar" to null), FixtureLog.calls().last().payload)
        assertTrue(EcarxDimApi.updateConnectedMobileDeviceState(phone, "iPhone", 1, 4).ok)
        assertEquals(mapOf("name" to "iPhone", "state" to 1, "feature" to 4),
            FixtureLog.calls().last().payload)
    }

    // Firmware media descriptors have not been dumped: exercise width coercion separately from
    // the standard int/long OEM fixture consumed by the media bridge flow tests.
    interface NumericPlayback {
        fun getDuration(): Int
        fun getPlaybackStatus(): Long
        fun getSourceType(): Short
        fun getLoopMode(): Byte
        fun getRadioMode(): Float
        fun getFavoriteState(): Double
        fun getUnknownFlag(): Boolean
    }
    class NumericMedia {
        var source = 0L
        var progress = 0
        var values: List<Any> = emptyList()
        fun updateCurrentSourceType(value: Long) { source = value }
        fun updateCurrentProgress(value: Int) { progress = value }
        fun updatePlaybackInfo(i: NumericPlayback) {
            values = listOf(i.getDuration(), i.getPlaybackStatus(), i.getSourceType(),
                i.getLoopMode(), i.getRadioMode(), i.getFavoriteState(), i.getUnknownFlag())
        }
    }

    @Test fun mediaCoercesNumericArgumentsAndProxyGetterReturnTypesToDeclaredWidths() {
        val media = NumericMedia()
        assertTrue(EcarxDimApi.updateCurrentSourceType(media, 6).ok)
        assertEquals(6L, media.source)
        assertTrue(EcarxDimApi.updateCurrentProgress(media, 12_345L).ok)
        assertEquals(12_345, media.progress)
        val itf = EcarxDimApi.playbackInfoInterface(media)!!
        assertTrue(EcarxDimApi.updatePlaybackInfo(media, itf,
            EcarxDimApi.PlaybackInfo(durationMillis = 99_999, playing = true)).ok)
        assertEquals(listOf(99_999, 1L, 6.toShort(), 0.toByte(), 0f, 0.0, false), media.values)
        assertTrue(EcarxDimApi.updatePlaybackInfo(media, itf,
            EcarxDimApi.PlaybackInfo(playing = false)).ok)
        assertEquals(2L, media.values[1])
    }

    @Test fun actualMissingDimClassIsToleratedEvenWhenOtherTestsHaveOemFixtures() {
        // Load the real reflection facade in a loader that cannot see OEM classes. A random
        // classPresent("missing") probe would not test the production factory's failure path.
        val parent = javaClass.classLoader!!
        val loader = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("com.ecarx.xui.adaptapi.diminteraction.")) throw ClassNotFoundException(name)
                if (name.startsWith("com.shilapi.xcertplay.hud.EcarxDimApi") ||
                    name.startsWith("com.shilapi.xcertplay.hud.EcarxCapabilities")) {
                    val loaded = findLoadedClass(name) ?: run {
                        val bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.use { it.readBytes() }
                        defineClass(name, bytes, 0, bytes.size)
                    }
                    if (resolve) resolveClass(loaded)
                    return loaded
                }
                return super.loadClass(name, resolve)
            }
        }
        val capabilities = loader.loadClass(EcarxCapabilities::class.java.name)
        assertEquals(false, capabilities.getMethod("dimPresent").invoke(capabilities.getField("INSTANCE").get(null)))
        val api = loader.loadClass(EcarxDimApi::class.java.name)
        assertNull(api.getMethod("createDimInteraction", Context::class.java)
            .invoke(api.getField("INSTANCE").get(null), context))
    }
}
