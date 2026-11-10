package com.shilapi.xcertplay

import android.content.pm.PackageInfo
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.hud.ClusterMapIntent.*
import com.shilapi.xcertplay.hud.NavigationOutputs
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class EcarxClusterFirstFrameTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val controller = mock(CarPlayController::class.java)
    private val sink = mock(AndroidMediaSink::class.java)
    private val session = mock(AirPlaySession::class.java)
    private val surface = mock(Surface::class.java)
    private lateinit var output: EcarxClusterSession
    private lateinit var panels: MockedConstruction<EcarxClusterPresentation>
    private val surfaceCallbacks = mutableListOf<(Surface?) -> Unit>()
    private val frameCallbacks = mutableListOf<(Surface) -> Unit>()
    private val events = mutableListOf<String>()
    private val display = CarPlaySessionDisplay(1920, 1080, Surface.ROTATION_0, true, true, 1920, 1080)

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        NavigationOutputs.attachReport(events::add)
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "com.ecarx.xui.service" })
        assertEquals(1, ShadowDisplayManager.addDisplay("w1920dp-h720dp"))
        panels = mockConstruction(EcarxClusterPresentation::class.java) { panel, context ->
            assertSame("The window must not retain an Activity context", app, context.arguments()[0])
            @Suppress("UNCHECKED_CAST")
            surfaceCallbacks += context.arguments()[2] as (Surface?) -> Unit
            @Suppress("UNCHECKED_CAST")
            frameCallbacks += context.arguments()[3] as (Surface) -> Unit
            `when`(panel.isShowing).thenReturn(true)
        }
        `when`(controller.activeAirPlaySession()).thenReturn(session)
        `when`(surface.isValid).thenReturn(true)
        CarPlayBackgroundSession.store(controller, sink, 1920, 1080, Any(), display) {}
        CarPlayBackgroundSession.bindClusterOutput(app, controller)
        output = ReflectionHelpers.getField(CarPlayBackgroundSession, "clusterOutput")
        NavigationOutputs.onAppOpened(app)
        ReflectionHelpers.setStaticField(Class.forName("com.shilapi.xcertplay.hud.EcarxHudBridge"), "guidanceActive", true)
        idle()
    }

    @After fun cleanup() {
        CarPlayBackgroundSession.clear()
        idle()
        panels.close()
        NavigationOutputs.attachReport {}
        ReflectionHelpers.setStaticField(Class.forName("com.shilapi.xcertplay.hud.EcarxHudBridge"), "guidanceActive", false)
        com.shilapi.xcertplay.hud.EcarxOutputSettings.setMirrorMainScreen(app, false)
    }

    @Test fun routeAndSurfaceCannotBypassTheCurrentSessionsFirstMainFrame() {
        output.onRouteRequested(SHOWN)
        idle()
        assertTrue(panels.constructed().isEmpty())
        onSurface(surface)
        verifyNoInteractions(sink)
        `when`(session.hasRenderedMainFrame).thenReturn(true)
        output.onSessionChanged()
        idle()
        verify(sink).setSurface(111, surface)
        assertEquals(1, panels.constructed().size)
        clearInvocations(sink)
        `when`(controller.activeAirPlaySession()).thenReturn(mock(AirPlaySession::class.java))
        output.onSessionChanged()
        idle()
        verify(sink).clearSurface(111, surface)
        verify(sink, never()).setSurface(111, surface)
        verify(panels.constructed().single()).dismiss()
    }

    @Test fun activityDestructionAndSameSessionAdoptionKeepTheSameWindowAnd111Surface() {
        open()
        val panel = panels.constructed().single()
        val oldHost = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(oldHost, "controller", controller)
        ReflectionHelpers.setField(oldHost, "sink", sink)
        ReflectionHelpers.setField(oldHost, "activeAirPlaySession", session)
        oldHost.javaClass.getDeclaredMethod("onDestroy").apply { isAccessible = true }.invoke(oldHost)
        CarPlayBackgroundSession.store(controller, sink, 1920, 1080, Any(), display) {}
        CarPlayBackgroundSession.bindClusterOutput(app, controller)
        idle()
        assertSame(output, ReflectionHelpers.getField(CarPlayBackgroundSession, "clusterOutput"))
        assertSame(panel, panels.constructed().single())
        verify(panel, never()).dismiss()
        verify(sink, never()).clearSurface(111, surface)
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            ReflectionHelpers.getField<ExecutorService>(oldHost, name).shutdownNow()
        }
        ReflectionHelpers.getField<Handler>(oldHost, "mainHandler").removeCallbacksAndMessages(null)
    }

    @Test fun mainSurfaceAttachmentOnlyBinds110() {
        open()
        clearInvocations(sink)
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(host, "sink", sink)
        val main = mock(Surface::class.java)
        host.javaClass.getDeclaredMethod("attachSurface", Surface::class.java)
            .apply { isAccessible = true }.invoke(host, main)
        verify(sink).setSurface(110, main)
        verifyNoMoreInteractions(sink)
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            ReflectionHelpers.getField<ExecutorService>(host, name).shutdownNow()
        }
    }

    @Test fun routeEndDisconnectAndRestartClearOnlyTheirOwnSurface() {
        open()
        output.onRouteRequested(NO_ROUTE)
        idle()
        verify(sink).clearSurface(111, surface)
        verify(panels.constructed().single()).dismiss()
        output.onRouteRequested(SHOWN)
        idle()
        onSurface(surface)
        `when`(controller.activeAirPlaySession()).thenReturn(null)
        output.onSessionChanged()
        idle()
        verify(panels.constructed().last()).dismiss()
        CarPlayBackgroundSession.clear(controller, keepOwner = true)
        idle()
        verify(controller).clearClusterOutputStateListener(ReflectionHelpers.getField(output, "stateListener"))
        assertNull(ReflectionHelpers.getField(CarPlayBackgroundSession, "clusterOutput"))
    }

    @Test fun hidingAndRestoringReusesTheLiveMapAndAnnouncesItsReadinessAgain() {
        open()
        frameCallbacks.single()(surface)
        idle()
        clearInvocations(controller, sink)
        events.clear()
        repeat(2) { output.onRouteRequested(HIDDEN); idle() }
        verify(controller, never()).setClusterOutputVisible(false)
        verify(sink, never()).clearSurface(111, surface)
        verify(panels.constructed().single(), never()).dismiss()
        assertFalse(events.any { "presentation request" in it })
        repeat(2) { output.onRouteRequested(SHOWN); idle() }
        assertEquals(1, events.count { "presentation request show=true" in it })
        assertEquals(1, panels.constructed().size)
        output.onRouteRequested(HIDDEN)
        output.onRouteRequested(NO_ROUTE)
        idle()
        verify(sink).clearSurface(111, surface)
        verify(panels.constructed().single()).dismiss()
    }

    @Test fun aRouteStartingHiddenWaitsForAVisibilityRequestBeforePreparingItsMap() {
        `when`(session.hasRenderedMainFrame).thenReturn(true)
        repeat(2) {
            output.onRouteRequested(HIDDEN)
            output.onSessionChanged()
            idle()
        }
        assertTrue(panels.constructed().isEmpty())
        verifyNoInteractions(sink)
        verify(controller, never()).setClusterOutputVisible(true)
        assertFalse(events.any { "presentation request show=true" in it })

        output.onRouteRequested(SHOWN)
        idle()
        verify(panels.constructed().single()).show()
        surfaceCallbacks.single()(surface)
        verify(sink).setSurface(111, surface)
        verify(controller, atLeastOnce()).setClusterOutputVisible(true)
        assertFalse(events.any { "presentation request show=true" in it })
        frameCallbacks.single()(surface)
        idle()
        assertEquals(1, events.count { "presentation request show=true" in it })
        verify(panels.constructed().single(), never()).dismiss()

        output.onRouteRequested(HIDDEN)
        idle()
        verify(panels.constructed().single(), never()).dismiss()
        output.onRouteRequested(NO_ROUTE)
        idle()
        clearInvocations(controller, sink)
        output.onRouteRequested(HIDDEN)
        output.onSessionChanged()
        idle()
        assertEquals(1, panels.constructed().size)
        verifyNoInteractions(sink)
        verify(controller, never()).setClusterOutputVisible(true)
    }

    @Test fun theWindowWaitsForAValidSurfaceBeforeClaimingProjectionOrPhoneUi() {
        `when`(session.hasRenderedMainFrame).thenReturn(true)
        output.onRouteRequested(SHOWN)
        idle()
        assertEquals(1, panels.constructed().size)
        verify(controller, never()).setClusterOutputVisible(true)
        assertTrue(NavigationOutputs.diagnosticReport(app).contains("projectionWanted=false"))
        onSurface(mock(Surface::class.java))
        verify(controller, never()).setClusterOutputVisible(true)
        onSurface(surface)
        verify(controller, atLeastOnce()).setClusterOutputVisible(true)
        assertFalse(NavigationOutputs.diagnosticReport(app).contains("projectionWanted=true"))
        frameCallbacks.last()(surface)
        idle()
        assertTrue(NavigationOutputs.diagnosticReport(app).contains("projectionWanted=true"))
    }

    @Test fun staleControllerClearAndOldOutputCloseCannotReleaseReplacementOwner() {
        open()
        val next = mock(CarPlayController::class.java)
        val nextSink = mock(AndroidMediaSink::class.java)
        `when`(next.activeAirPlaySession()).thenReturn(session)
        CarPlayBackgroundSession.store(next, nextSink, 1920, 1080, Any(), display) {}
        CarPlayBackgroundSession.bindClusterOutput(app, next)
        val replacement = ReflectionHelpers.getField<EcarxClusterSession>(CarPlayBackgroundSession, "clusterOutput")
        replacement.onRouteRequested(SHOWN)
        idle()
        CarPlayBackgroundSession.clear(controller)
        output.close()
        idle()
        assertSame(replacement, ReflectionHelpers.getField(CarPlayBackgroundSession, "clusterOutput"))
        verify(panels.constructed().last(), never()).dismiss()
        verify(next, never()).clearClusterOutputStateListener(ReflectionHelpers.getField(replacement, "stateListener"))
    }

    @Test fun displayRemovalClosesWindowAndReturningDisplayRestoresRequestedRoute() {
        open()
        val listener = ReflectionHelpers.getField<DisplayManager.DisplayListener>(output, "displayListener")
        listener.onDisplayRemoved(1)
        idle()
        verify(sink).clearSurface(111, surface)
        verify(panels.constructed().single()).dismiss()
        listener.onDisplayAdded(1)
        idle()
        assertEquals(2, panels.constructed().size)
    }

    @Test fun lateSurfaceCallbacksFromAnOldWindowCannotDetachTheReplacement() {
        open()
        output.onRouteRequested(NO_ROUTE)
        idle()
        output.onRouteRequested(SHOWN)
        idle()
        val replacement = mock(Surface::class.java)
        surfaceCallbacks.last()(replacement)
        clearInvocations(sink)
        surfaceCallbacks.first()(null)
        surfaceCallbacks.first()(surface)
        verifyNoInteractions(sink)
        assertSame(replacement, ReflectionHelpers.getField(output, "surface"))
    }

    @Test fun advancedMirrorUsesMainStreamAndOnlyChangesAtTheNextSession() {
        com.shilapi.xcertplay.hud.EcarxOutputSettings.setMirrorMainScreen(app, true)
        val mirrored = mock(AirPlaySession::class.java)
        `when`(mirrored.hasRenderedMainFrame).thenReturn(true)
        `when`(controller.activeAirPlaySession()).thenReturn(mirrored)
        output.onRouteRequested(SHOWN)
        idle()
        onSurface(surface)
        verify(sink).setMirrorSurface(110, "ecarx-cluster-main", surface)
        verify(sink, never()).setSurface(111, surface)
        verify(panels.constructed().last()).setVideoSize(1920, 1080)
        assertTrue("Mirror mode does not wait for stream 111", NavigationOutputs.diagnosticReport(app).contains("projectionWanted=true"))
        verify(controller, never()).setClusterOutputVisible(true)

        com.shilapi.xcertplay.hud.EcarxOutputSettings.setMirrorMainScreen(app, false)
        clearInvocations(sink)
        output.onSessionChanged()
        idle()
        verify(sink).setMirrorSurface(110, "ecarx-cluster-main", surface)
        verify(sink, never()).setSurface(111, surface)

        `when`(session.hasRenderedMainFrame).thenReturn(true)
        `when`(controller.activeAirPlaySession()).thenReturn(session)
        output.onSessionChanged()
        idle()
        verify(sink).setMirrorSurface(110, "ecarx-cluster-main", null)
        onSurface(surface)
        verify(sink).setSurface(111, surface)
        verify(sink, never()).clearSurface(110, surface)
    }

    @Test fun independentProjectionWaitsForTheCurrentBindingsFirstTextureFrame() {
        events.clear()
        open()
        assertFalse("A bound Surface must request phone content before arming the platform projection",
            NavigationOutputs.diagnosticReport(app).contains("projectionWanted=true"))
        verify(controller, atLeastOnce()).setClusterOutputVisible(true)
        assertTrue("Waiting for pixels retains navigation ownership", NavigationOutputs.diagnosticReport(app).contains(" active=true"))
        assertTrue(events.none { "lifecycle stop" in it || "lane clear" in it })
        frameCallbacks.last()(surface)
        idle()
        assertTrue(NavigationOutputs.diagnosticReport(app).contains("projectionWanted=true"))
    }

    @Test fun replacingTheSurfaceAndRetogglingWaitAgainAndIgnoreLateFrames() {
        open()
        frameCallbacks.last()(surface)
        idle()
        assertTrue(NavigationOutputs.diagnosticReport(app).contains("projectionWanted=true"))
        events.clear()
        repeat(3) { frameCallbacks.last()(surface); idle() }
        assertTrue(events.none { "presentation request show=true" in it })

        val replacement = mock(Surface::class.java)
        `when`(replacement.isValid).thenReturn(true)
        surfaceCallbacks.last()(replacement)
        assertFalse(NavigationOutputs.diagnosticReport(app).contains("projectionWanted=true"))
        frameCallbacks.last()(surface)
        idle()
        assertFalse(NavigationOutputs.diagnosticReport(app).contains("projectionWanted=true"))
        frameCallbacks.last()(replacement)
        idle()
        assertTrue(NavigationOutputs.diagnosticReport(app).contains("projectionWanted=true"))

        output.onRouteRequested(NO_ROUTE)
        frameCallbacks.last()(replacement) // Intent changed before the queued window hide.
        idle()
        assertFalse(NavigationOutputs.diagnosticReport(app).contains("projectionWanted=true"))
        output.onRouteRequested(SHOWN)
        idle()
        onSurface(replacement)
        frameCallbacks.first()(replacement) // The dismissed window cannot arm the replacement.
        idle()
        assertFalse(NavigationOutputs.diagnosticReport(app).contains("projectionWanted=true"))
        output.onRouteRequested(NO_ROUTE)
        frameCallbacks.last()(replacement) // Hidden before this binding received its first frame.
        idle()
        assertFalse(NavigationOutputs.diagnosticReport(app).contains("projectionWanted=true"))
        output.onRouteRequested(SHOWN)
        idle()
        onSurface(replacement)
        frameCallbacks.last()(replacement)
        output.close() // Session close wins over the queued first-frame apply.
        idle()
        assertFalse(NavigationOutputs.diagnosticReport(app).contains("projectionWanted=true"))
    }

    @Test fun ordinaryDiagnosticsCoalesceWindowFailuresAndObserveRecovery() {
        panels.close()
        var fail = true
        panels = mockConstruction(EcarxClusterPresentation::class.java) { panel, _ ->
            if (fail) doThrow(IllegalStateException("private display name")).`when`(panel).show()
        }
        `when`(session.hasRenderedMainFrame).thenReturn(true)
        output.onRouteRequested(SHOWN)
        idle()
        output.onSessionChanged()
        idle()
        assertEquals(1, events.count { "panel window show-failed" in it })
        assertTrue(events.none { "private display name" in it })
        fail = false
        output.onSessionChanged()
        idle()
        assertEquals(1, events.count { "panel window shown" in it })
    }

    @Test fun ordinaryDiagnosticsObserveEachBindingOnceWithoutClaimingPhysicalDisplay() {
        open()
        events.clear()
        repeat(3) {
            output.onRouteRequested(SHOWN)
            output.onSessionChanged()
            idle()
            frameCallbacks.last()(surface)
        }
        assertEquals(1, events.count { "panel video first-frame" in it })
        assertTrue(events.single { "panel video first-frame" in it }.contains("physicalDisplay=unobserved"))
        assertTrue(events.none { "panel intent" in it })

        output.onRouteRequested(NO_ROUTE)
        idle()
        output.onRouteRequested(SHOWN)
        idle()
        onSurface(surface)
        frameCallbacks.first()(surface)
        assertEquals(1, events.count { "panel video first-frame" in it })
        val replacementSession = mock(AirPlaySession::class.java)
        `when`(controller.activeAirPlaySession()).thenReturn(replacementSession)
        frameCallbacks.last()(surface)
        assertEquals(1, events.count { "panel video first-frame" in it })
        `when`(controller.activeAirPlaySession()).thenReturn(session)
        repeat(3) { frameCallbacks.last()(surface) }
        assertEquals(2, events.count { "panel video first-frame" in it })
        assertEquals(1, events.count { "panel surface attached" in it })
        assertEquals(1, events.count { "panel surface detached" in it })
        assertEquals(2, events.count { "panel intent" in it })
    }

    private fun open() {
        `when`(session.hasRenderedMainFrame).thenReturn(true)
        output.onRouteRequested(SHOWN)
        idle()
        onSurface(surface)
    }
    private fun onSurface(value: Surface?) = output.javaClass.getDeclaredMethod("onSurface", Surface::class.java)
        .apply { isAccessible = true }.invoke(output, value)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
