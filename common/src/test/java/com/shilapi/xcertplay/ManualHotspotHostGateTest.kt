package com.shilapi.xcertplay

import android.os.Handler
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.network.ManualHotspotControl
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast

/** Drives the actual Host entry point, with transport construction mocked and no system writes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ManualHotspotHostGateTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var controllers: MockedConstruction<CarPlayController>
    private val cancelled = AtomicBoolean()
    private val releaseWrite = CountDownLatch(1)
    private var worker: Thread? = null
    private val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")

    @Before fun setUp() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        AirPlayPersistence.saveAdaptPipResolution(activity, false)
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.USB_CH341)
        controllers = mockConstruction(CarPlayController::class.java) { controller, _ ->
            `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        }
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        setField("teardownExecutor", PausedExecutorService())
        setField("activeDisplaySize", size())
        // Ephemeral test identity only; no real authentication or transport executes.
        setField("airPlayIdentity", AirPlayIdentity.generate())
        setField("vpnReady", true)
        setField("microphonePermissionResolved", true)
    }

    @After fun tearDown() {
        cancelled.set(true)
        releaseWrite.countDown()
        worker?.join(5_000)
        (field("shuttingDown") as AtomicBoolean).set(true)
        (field("mainHandler") as Handler).removeCallbacksAndMessages(null)
        AirPlayPersistence.overlaySettingsListener = null
        (field("controller") as? CarPlayController)?.let(CarPlayMediaSession::detach)
        (field("sink") as? AndroidMediaSink)?.let { it.close(); it.awaitVideoReleased(5_000) }
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        (field("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.clear()
        controllers.close()
    }

    @Test fun hostRejectsDuringManualRequestWithToastThenStartsAfterCancellationReleasesGate() {
        val enteredWrite = CountDownLatch(1)
        val writes = AtomicInteger()
        val control = fakeControl {
            writes.incrementAndGet()
            enteredWrite.countDown()
            check(releaseWrite.await(5, TimeUnit.SECONDS)) { "test did not release fake request" }
        }
        val outcome = AtomicReference<ManualHotspotControl.Result>()
        worker = Thread { outcome.set(control.request(true) { cancelled.get() }) }.apply { start() }
        assertTrue("manual request reached fake platform", enteredWrite.await(5, TimeUnit.SECONDS))
        assertTrue(ManualHotspotControl.isBusy())
        startHost()
        assertEquals(0, controllers.constructed().size)
        assertNull(field("controller"))
        assertFalse(CarPlayBackgroundSession.hasSession())
        assertEquals(activity.getString(R.string.settings_manual_hotspot_working), ShadowToast.getTextOfLatestToast())

        cancelled.set(true)
        releaseWrite.countDown()
        worker!!.join(5_000)
        assertFalse("worker terminated", worker!!.isAlive)
        assertEquals(ManualHotspotControl.Result.CANCELLED, outcome.get())
        assertFalse(ManualHotspotControl.isBusy())
        assertEquals("cancellation never sends a compensating write", 1, writes.get())

        // No automatic retry: only this next explicit Host invocation creates a connection owner.
        assertEquals(0, controllers.constructed().size)
        startHost()
        assertEquals(1, controllers.constructed().size)
        assertSame(controllers.constructed().single(), field("controller"))
        assertTrue(CarPlayBackgroundSession.isOwner(activity))
        assertTrue(CarPlayBackgroundSession.hasSession())
    }

    @Test fun hostOwnerRegisteredFirstCancelsManualRequestBeforeAnyPlatformWrite() {
        startHost()
        assertTrue(CarPlayBackgroundSession.hasSession())
        val controller = field("controller")
        val writes = AtomicInteger()
        val control = fakeControl { writes.incrementAndGet() }
        val result = control.request(false) { CarPlayBackgroundSession.hasSession() }
        assertEquals(ManualHotspotControl.Result.CANCELLED, result)
        assertEquals(0, writes.get())
        assertSame(controller, field("controller"))
        assertTrue(CarPlayBackgroundSession.isOwner(activity))
        assertFalse(ManualHotspotControl.isBusy())
    }

    /** Reflect only the shared-module internal test seam, rather than exposing it in production API. */
    private fun fakeControl(write: () -> Unit): ManualHotspotControl {
        val type = Class.forName("com.shilapi.xcertplay.network.ManualHotspotControl\$Platform")
        val platform = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "getSdk" -> 28
                "permitted" -> true
                "state" -> ManualHotspotControl.State.OFF
                "request" -> { write(); null }
                "now" -> System.nanoTime() / 1_000_000L
                "pause" -> { Thread.sleep(1); null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "test hotspot platform"
                else -> error("Unexpected platform method ${method.name}")
            }
        }
        return ManualHotspotControl::class.java.getDeclaredConstructor(type).apply { isAccessible = true }
            .newInstance(platform)
    }
    private fun size(): Any = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(1920, 990)
    private fun startHost() { activity.javaClass.getDeclaredMethod("startCarPlay", sizeClass)
        .apply { isAccessible = true }.invoke(activity, size()) }
    private fun field(name: String): Any? = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun setField(name: String, value: Any?) { activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value) }
}
