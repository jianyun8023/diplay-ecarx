package com.shilapi.xcertplay

import android.os.Handler
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EcarxClusterFirstFrameTest {
    private val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
    private val sink = mock(AndroidMediaSink::class.java)
    private val session = mock(AirPlaySession::class.java)
    private val surface = mock(Surface::class.java)

    @After fun cleanup() {
        ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            ReflectionHelpers.getField<ExecutorService>(host, name).shutdownNow()
        }
    }

    @Test fun existingPanelRebindsOnlyAfterTheCurrentSessionsFirstMainFrame() {
        ReflectionHelpers.setField(host, "sink", sink)
        ReflectionHelpers.setField(host, "clusterSurface", surface)
        ReflectionHelpers.setField(host, "clusterPresentation", mock(EcarxClusterPresentation::class.java))
        ReflectionHelpers.setField(host, "activeAirPlaySession", session)
        invoke("showClusterPanel")
        verifyNoInteractions(sink)
        `when`(session.hasRenderedMainFrame).thenReturn(true)
        invoke("showClusterPanel")
        verify(sink).setSurface(111, surface)
        clearInvocations(sink)
        ReflectionHelpers.setField(host, "activeAirPlaySession", mock(AirPlaySession::class.java))
        invoke("showClusterPanel")
        verifyNoInteractions(sink)
    }

    @Test fun surfaceCallbacksAndMainSurfaceAttachmentCannotBypassFirstFrameGate() {
        ReflectionHelpers.setField(host, "sink", sink)
        ReflectionHelpers.setField(host, "activeAirPlaySession", session)
        invoke("onClusterSurface", Surface::class.java, surface)
        verifyNoInteractions(sink)
        val main = mock(Surface::class.java)
        invoke("attachSurface", Surface::class.java, main)
        verify(sink).setSurface(110, main)
        verify(sink, never()).setSurface(111, surface)
        `when`(session.hasRenderedMainFrame).thenReturn(true)
        invoke("attachSurface", Surface::class.java, main)
        verify(sink).setSurface(111, surface)
        val replacement = mock(Surface::class.java)
        invoke("onClusterSurface", Surface::class.java, replacement)
        verify(sink).clearSurface(111, surface)
        verify(sink).setSurface(111, replacement)
    }

    @Test fun routeRequestBeforeFirstMainFrameDoesNotCreateAPanel() {
        assertEquals(1, ShadowDisplayManager.addDisplay("w1920dp-h720dp"))
        ReflectionHelpers.setField(host, "activeAirPlaySession", session)
        ReflectionHelpers.setField(host, "clusterPanelWanted", true)
        invoke("applyClusterPanel")
        assertNull(ReflectionHelpers.getField<EcarxClusterPresentation?>(host, "clusterPresentation"))
    }

    private fun invoke(name: String) = host.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(host)
    private fun invoke(name: String, type: Class<*>, value: Any?) = host.javaClass.getDeclaredMethod(name, type)
        .apply { isAccessible = true }.invoke(host, value)
}
