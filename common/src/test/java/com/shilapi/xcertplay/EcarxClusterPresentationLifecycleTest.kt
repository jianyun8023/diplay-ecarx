package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.shilapi.xcertplay.hud.EcarxOutputSettings
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowWindowManagerImpl

/** Real host requests and Presentation lifecycle; Android windows are JVM shadows, not car renders. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [EcarxClusterPresentationLifecycleTest.WindowShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class EcarxClusterPresentationLifecycleTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var sink: AndroidMediaSink
    private val bridge = Class.forName("com.shilapi.xcertplay.hud.EcarxHudBridge")
        .getField("INSTANCE").get(null)
    private lateinit var savedBridge: Map<String, Any?>
    private var savedSwitch = false
    private val surfaceTextures = mutableListOf<SurfaceTexture>()
    private val attached = mutableListOf<Surface>()
    private val detached = mutableListOf<Surface>()
    private val switches = mutableListOf<SwitchCall>()
    private val events get() = WindowShadow.events

    @Before fun setUp() {
        WindowShadow.events.clear()
        WindowShadow.attemptedViews.clear()
        WindowShadow.rejectNext = false
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        savedSwitch = EcarxOutputSettings.presentationSwitch(activity)
        // Keep the real requestPresentation -> bridge -> reflective DIM call, without OEM workers.
        val bridgeValues = linkedMapOf<String, Any?>(
            "context" to activity.applicationContext, "available" to true,
            "dim" to DimReceiver { screen, firstPackage, secondPackage, show ->
                switches += SwitchCall(screen, firstPackage, secondPackage, show)
                if (show) {
                    val window = presentation()
                    assertTrue("OEM projection must follow an actual shown window", window.isShowing)
                    assertNotNull(texture(window).surfaceTextureListener)
                }
                events += if (show) "oem:on" else "oem:off"
            },
            "presentationWanted" to false, "presentationAttempts" to 0,
            "presentationCountdown" to 0, "presentedPackage" to null,
            "lastPushed" to null, "reportSink" to { _: String -> },
        )
        savedBridge = bridgeValues.keys.associateWith { field(bridge, it).get(bridge) }
        bridgeValues.forEach { (name, value) -> field(bridge, name).set(bridge, value) }
        sink = mock(AndroidMediaSink::class.java) { call ->
            when (call.method.name) {
                "setSurface" -> {
                    assertEquals("Only the instrument stream owns this window", 111, call.getArgument<Int>(0))
                    attached += call.getArgument<Surface>(1)
                    events += "surface:set"
                }
                "clearSurface" -> {
                    assertEquals(111, call.getArgument<Int>(0))
                    val surface = call.getArgument<Surface>(1)
                    assertTrue("Detach the decoder before releasing the wrapper", surface.isValid)
                    detached += surface
                    events += "surface:clear"
                }
            }
            Answers.RETURNS_DEFAULTS.answer(call)
        }
        field(activity, "sink").set(activity, sink)
    }

    @After fun tearDown() {
        try {
            WindowShadow.rejectNext = false
            request(false)
            idle()
            // Also release textures in a refused window or a deliberately failing stale-callback test.
            WindowShadow.attemptedViews.forEach { findTexture(it)?.close() }
            surfaceTextures.forEach(SurfaceTexture::release)
            (field(activity, "shuttingDown").get(activity) as AtomicBoolean).set(true)
            (field(activity, "mainHandler").get(activity) as Handler).removeCallbacksAndMessages(null)
            (field(activity, "teardownExecutor").get(activity) as ExecutorService).shutdownNow()
            (field(activity, "airPlayCommandExecutor").get(activity) as ExecutorService).shutdownNow()
        } finally {
            savedBridge.forEach { (name, value) -> field(bridge, name).set(bridge, value) }
            EcarxOutputSettings.setPresentationSwitch(activity, savedSwitch)
            WindowShadow.attemptedViews.clear()
            events.clear()
        }
    }

    @Test fun requestCreatesARealWindowBeforeOemProjectionAndRoutesItsSurfaceTo111() {
        addPanel()
        request(true)
        assertNull(field(activity, "clusterPresentation").get(activity))
        assertTrue(switches.isEmpty())
        idle()

        val window = presentation()
        assertEquals(1, window.display.displayId)
        assertTrue(window.isShowing)
        assertEquals(listOf("window:add", "oem:on"), events)
        assertEquals(listOf(SwitchCall(1, "", "", true)), switches)
        assertTrue(EcarxOutputSettings.presentationSwitch(activity))
        verifyNoInteractions(sink)

        val surface = available(texture(window))
        assertSame(surface, field(activity, "clusterSurface").get(activity))
        verify(sink).setSurface(111, surface)
        verifyNoMoreInteractions(sink)
    }

    @Test fun repeatedShowKeepsTheSameWindowAndDoesNotReenableProjection() {
        val window = showPanel()
        val surface = available(texture(window))
        request(true)
        idle()
        assertSame(window, presentation())
        assertEquals(1, WindowShadow.attemptedViews.size)
        assertEquals(listOf(true), switches.map { it.show })
        assertSame(surface, field(activity, "clusterSurface").get(activity))
        verify(sink).setSurface(111, surface)
        verifyNoMoreInteractions(sink)
    }

    @Test fun closeSwitchesOffBeforeRemovingWindowAndDetachesBeforeReleaseOnlyOnce() {
        val window = showPanel()
        val view = texture(window)
        val surface = available(view)
        events.clear()
        request(false)
        idle()

        assertEquals("oem:off", events.first())
        assertTrue(events.contains("window:remove"))
        assertTrue(events.contains("surface:clear"))
        assertFalse(window.isShowing)
        assertFalse(surface.isValid)
        assertNull(view.surfaceTextureListener)
        assertNull(field(activity, "clusterPresentation").get(activity))
        assertNull(field(activity, "clusterSurface").get(activity))
        assertFalse(EcarxOutputSettings.presentationSwitch(activity))
        val closedEvents = events.toList()

        request(false)
        idle()
        window.dismiss()
        assertEquals(closedEvents, events)
        assertEquals(listOf(true, false), switches.map { it.show })
        verify(sink).setSurface(111, surface)
        verify(sink).clearSurface(111, surface)
        verifyNoMoreInteractions(sink)
    }

    @Test fun showThenHideBeforeMainQueueRunsNeverCreatesAWindowOrEnablesProjection() {
        addPanel()
        request(true)
        request(false)
        idle()
        assertNull(field(activity, "clusterPresentation").get(activity))
        assertTrue(WindowShadow.attemptedViews.isEmpty())
        assertTrue(switches.isEmpty())
        verifyNoInteractions(sink)
    }

    @Test fun missingDisplayLeavesProjectionAloneAndALaterRequestCanOpenAnAttachedPanel() {
        assertNull(activity.getSystemService(DisplayManager::class.java).getDisplay(1))
        request(true)
        idle()
        assertNull(field(activity, "clusterPresentation").get(activity))
        assertTrue(switches.isEmpty())
        assertTrue(events.isEmpty())
        verifyNoInteractions(sink)

        addPanel()
        request(true)
        idle()
        assertTrue(presentation().isShowing)
        assertEquals(listOf(true), switches.map { it.show })
    }

    @Test fun rejectedAndroidWindowDoesNotEnableProjectionAndNextRequestCanRetry() {
        addPanel()
        WindowShadow.rejectNext = true
        request(true)
        idle()
        assertNull(field(activity, "clusterPresentation").get(activity))
        assertNull(field(activity, "clusterSurface").get(activity))
        assertEquals(listOf("window:rejected"), events)
        assertTrue(switches.isEmpty())
        verifyNoInteractions(sink)

        request(true)
        idle()
        assertTrue(presentation().isShowing)
        assertEquals(listOf(true), switches.map { it.show })
        assertEquals(listOf("window:rejected", "window:add", "oem:on"), events)
    }

    @Test fun closeThenReopenCreatesANewWindowAndAFreshInstrumentSurface() {
        val oldWindow = showPanel()
        val oldSurface = available(texture(oldWindow))
        request(false)
        idle()
        request(true)
        idle()
        val currentWindow = presentation()
        val currentSurface = available(texture(currentWindow))

        assertNotSame(oldWindow, currentWindow)
        assertFalse(oldWindow.isShowing)
        assertFalse(oldSurface.isValid)
        assertNotSame(oldSurface, currentSurface)
        assertSame(currentSurface, field(activity, "clusterSurface").get(activity))
        assertEquals(listOf(true, false, true), switches.map { it.show })
        inOrder(sink).apply {
            verify(sink).setSurface(111, oldSurface)
            verify(sink).clearSurface(111, oldSurface)
            verify(sink).setSurface(111, currentSurface)
            verifyNoMoreInteractions()
        }
    }

    @Test fun replacingAndDestroyingAnActiveTextureDetachesEachSurfaceExactlyOnce() {
        val view = texture(showPanel())
        val first = available(view)
        val second = available(view)
        val callback = checkNotNull(view.surfaceTextureListener)
        assertFalse(first.isValid)
        assertTrue(callback.onSurfaceTextureDestroyed(surfaceTextures.last()))
        assertTrue(callback.onSurfaceTextureDestroyed(surfaceTextures.last()))
        assertFalse(second.isValid)
        assertNull(field(activity, "clusterSurface").get(activity))
        request(false)
        idle()
        inOrder(sink).apply {
            verify(sink).setSurface(111, first)
            verify(sink).clearSurface(111, first)
            verify(sink).setSurface(111, second)
            verify(sink).clearSurface(111, second)
            verifyNoMoreInteractions()
        }
    }

    @Test fun lateDestructionFromClosedWindowCannotClearTheReopenedWindowSurface() {
        val oldView = texture(showPanel())
        val oldCallback = checkNotNull(oldView.surfaceTextureListener)
        available(oldView)
        val oldTexture = surfaceTextures.last()
        request(false)
        idle()
        request(true)
        idle()
        val current = available(texture(presentation()))
        clearInvocations(sink)

        assertTrue(oldCallback.onSurfaceTextureDestroyed(oldTexture))

        assertSame(current, field(activity, "clusterSurface").get(activity))
        assertTrue(current.isValid)
        verifyNoInteractions(sink)
    }

    // Retained listeners must stay harmless after their owning Presentation stops.
    @Test fun capturedAvailableCallbackCannotReviveSurfaceAfterWindowCloses() {
        val oldView = texture(showPanel())
        val oldCallback = checkNotNull(oldView.surfaceTextureListener)
        available(oldView)
        request(false)
        idle()
        clearInvocations(sink)

        oldCallback.onSurfaceTextureAvailable(newTexture(), 1920, 720)

        assertNull("A closed window must not reinstall a decoder surface",
            field(activity, "clusterSurface").get(activity))
        assertFalse("The rejected late Surface wrapper must be released",
            (field(oldView, "output").get(oldView) as Surface).isValid)
        assertTrue(oldCallback.onSurfaceTextureDestroyed(surfaceTextures.last()))
        verifyNoInteractions(sink)
    }

    @Test fun capturedAvailableCallbackCannotReplaceAReopenedWindowSurface() {
        val oldView = texture(showPanel())
        val oldCallback = checkNotNull(oldView.surfaceTextureListener)
        available(oldView)
        request(false)
        idle()
        request(true)
        idle()
        val current = available(texture(presentation()))
        clearInvocations(sink)

        oldCallback.onSurfaceTextureAvailable(newTexture(), 1920, 720)

        assertSame("An old window must not displace the current instrument surface",
            current, field(activity, "clusterSurface").get(activity))
        assertFalse("The rejected late Surface wrapper must be released",
            (field(oldView, "output").get(oldView) as Surface).isValid)
        assertTrue(oldCallback.onSurfaceTextureDestroyed(surfaceTextures.last()))
        assertSame(current, field(activity, "clusterSurface").get(activity))
        verifyNoInteractions(sink)
    }

    private fun request(show: Boolean) {
        activity.javaClass.getDeclaredMethod("onClusterPanelRequested", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, show)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun addPanel() {
        assertEquals("The production host deliberately targets display 1", 1,
            ShadowDisplayManager.addDisplay("w1920dp-h720dp"))
    }

    private fun showPanel(): EcarxClusterPresentation {
        addPanel()
        request(true)
        idle()
        return presentation()
    }

    private fun presentation() = checkNotNull(field(activity, "clusterPresentation").get(activity))
        as EcarxClusterPresentation

    private fun texture(window: EcarxClusterPresentation) = checkNotNull(findTexture(window.window!!.decorView))

    private fun findTexture(view: View): ClusterVideoTexture? {
        if (view is ClusterVideoTexture) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findTexture(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun newTexture() = SurfaceTexture(0).also(surfaceTextures::add)

    private fun available(view: TextureView): Surface {
        val before = attached.size
        checkNotNull(view.surfaceTextureListener).onSurfaceTextureAvailable(newTexture(), 1920, 720)
        assertEquals(before + 1, attached.size)
        return attached.last()
    }

    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }

    data class SwitchCall(val screen: Int, val firstPackage: String, val secondPackage: String, val show: Boolean)

    /** Only the reflective OEM receiver; shared test classes are not exported to common. */
    class DimReceiver(private val receive: (Int, String, String, Boolean) -> Unit) {
        fun setPresentationToDimSwitch(screen: Int, firstPackage: String, secondPackage: String, show: Boolean) =
            receive(screen, firstPackage, secondPackage, show)
    }

    /** Keep Robolectric's window implementation, injecting one Android addView refusal when asked. */
    @Implements(className = "android.view.WindowManagerImpl")
    class WindowShadow : ShadowWindowManagerImpl() {
        @Implementation override fun addView(view: View, params: ViewGroup.LayoutParams) {
            attemptedViews += view
            if (rejectNext) {
                rejectNext = false
                events += "window:rejected"
                throw WindowManager.InvalidDisplayException("fixture window refused")
            }
            super.addView(view, params)
            events += "window:add"
        }

        @Implementation override fun removeViewImmediate(view: View) {
            events += "window:remove"
            super.removeViewImmediate(view)
        }

        companion object {
            var rejectNext = false
            val events = mutableListOf<String>()
            val attemptedViews = mutableListOf<View>()
        }
    }
}
