package com.shilapi.xcertplay

import android.content.pm.PackageInfo
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlaySession
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
    private val display = CarPlaySessionDisplay(1920, 1080, Surface.ROTATION_0, true, true, 1920, 1080)

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "com.ecarx.xui.service" })
        assertEquals(1, ShadowDisplayManager.addDisplay("w1920dp-h720dp"))
        panels = mockConstruction(EcarxClusterPresentation::class.java) { panel, context ->
            assertSame("The window must not retain an Activity context", app, context.arguments()[0])
            @Suppress("UNCHECKED_CAST")
            surfaceCallbacks += context.arguments()[2] as (Surface?) -> Unit
            `when`(panel.isShowing).thenReturn(true)
        }
        `when`(controller.activeAirPlaySession()).thenReturn(session)
        CarPlayBackgroundSession.store(controller, sink, 1920, 1080, Any(), display) {}
        CarPlayBackgroundSession.bindClusterOutput(app, controller) {}
        output = ReflectionHelpers.getField(CarPlayBackgroundSession, "clusterOutput")
        idle()
    }

    @After fun cleanup() {
        CarPlayBackgroundSession.clear()
        idle()
        panels.close()
    }

    @Test fun routeAndSurfaceCannotBypassTheCurrentSessionsFirstMainFrame() {
        output.onRouteRequested(true)
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
        CarPlayBackgroundSession.bindClusterOutput(app, controller) {}
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
        output.onRouteRequested(false)
        idle()
        verify(sink).clearSurface(111, surface)
        verify(panels.constructed().single()).dismiss()
        output.onRouteRequested(true)
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

    @Test fun staleControllerClearAndOldOutputCloseCannotReleaseReplacementOwner() {
        open()
        val next = mock(CarPlayController::class.java)
        val nextSink = mock(AndroidMediaSink::class.java)
        `when`(next.activeAirPlaySession()).thenReturn(session)
        CarPlayBackgroundSession.store(next, nextSink, 1920, 1080, Any(), display) {}
        CarPlayBackgroundSession.bindClusterOutput(app, next) {}
        val replacement = ReflectionHelpers.getField<EcarxClusterSession>(CarPlayBackgroundSession, "clusterOutput")
        replacement.onRouteRequested(true)
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
        output.onRouteRequested(false)
        idle()
        output.onRouteRequested(true)
        idle()
        val replacement = mock(Surface::class.java)
        surfaceCallbacks.last()(replacement)
        clearInvocations(sink)
        surfaceCallbacks.first()(null)
        surfaceCallbacks.first()(surface)
        verifyNoInteractions(sink)
        assertSame(replacement, ReflectionHelpers.getField(output, "surface"))
    }

    private fun open() {
        `when`(session.hasRenderedMainFrame).thenReturn(true)
        output.onRouteRequested(true)
        idle()
        onSurface(surface)
    }
    private fun onSurface(value: Surface?) = output.javaClass.getDeclaredMethod("onSurface", Surface::class.java)
        .apply { isAccessible = true }.invoke(output, value)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
