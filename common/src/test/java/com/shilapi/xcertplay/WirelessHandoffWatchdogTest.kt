package com.shilapi.xcertplay

import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessHandoffWatchdogTest {
    private lateinit var controller: CarPlayController
    private lateinit var session: AirPlaySession
    private lateinit var bootstrap: BluetoothSocket
    private val statuses = mutableListOf<CarPlayStatus>()
    private val diagnostics = mutableListOf<String>()
    private var hotspotCloses = 0
    private var confirmations = 0
    private lateinit var proof: Any

    @Before fun setUp() {
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
        val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")
        val listener = object : AirPlaySessionListener {
            override fun onDebugLog(message: String) { diagnostics += message }
        }
        controller = CarPlayController(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = false
        }, CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
            // A measured hotspot mode, so failure checkpoints reach the startup timeline.
            wirelessHotspotMode = WirelessHotspotMode.MANUAL, manualHotspotSsid = "test", manualHotspotPassphrase = "password1",
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            config, identity, PairingStore(), listener, object : AirPlayMediaHandler {}, statuses::add)
        session = AirPlaySession(Socket(), config, identity, PairingStore(), null, listener, object : AirPlayMediaHandler {})
        val phase = controller.javaClass.getDeclaredField("phase").apply { isAccessible = true }
        phase.set(controller, phase.type.enumConstants!!.first { it.toString() == "WIRELESS" })
        controller.wirelessStateCallForTest("requestHandoff")
        ReflectionHelpers.setField(controller, "activeSession", session)
        proof = ReflectionHelpers.getField(controller.wirelessRunForTest(), "wirelessConnectionProof")
        proofCall("begin", 0, { confirmations++ })
        proofCall("activate", 0, session)
        bootstrap = mock(BluetoothSocket::class.java)
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "bluetoothSocket", bootstrap)
        ReflectionHelpers.setField(controller.wirelessRunForTest(), "hotspot", object : WirelessHotspotManager {
            override fun start(timeoutMillis: Long): WirelessHotspotInfo = error("Not started")
            override fun close() { hotspotCloses++ }
        })
    }

    @After fun tearDown() {
        controller.close()
        controller.awaitClosed(2_000)
        session.close()
    }

    @Test fun establishedSessionWithoutVideoStillFailsAndCleansUp() {
        timeout()
        assertFalse(controller.wirelessStateCallForTest("getActiveReported") as Boolean)
        assertEquals(1, hotspotCloses)
        assertTrue(statuses.single() is CarPlayStatus.Failed)
        verify(bootstrap).close()
        assertEquals(0, confirmations)
        // The handoff timeout fails the run outside runWireless; the metrics still see a failure.
        val events = RuntimeEnvironment.getApplication()
            .getSharedPreferences("diplay_wireless_startup_timeline", Context.MODE_PRIVATE).getString("events", "").orEmpty()
        assertTrue(events, events.contains("stage=WIRELESS_FAILED mode=MANUAL generation=0"))
        assertEquals(1, events.lineSequence().count { it.contains("stage=WIRELESS_FAILED") })
    }

    @Test fun tunnelControlFailureWhileTheSessionRendersIsShownWithoutEndingTheRun() {
        val run = controller.wirelessRunForTest()
        val onStatus = run.javaClass.getDeclaredMethod("onStatus", CarPlayStatus::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        onStatus.invoke(run, CarPlayStatus.Failed("Wireless iAP2 tunnel closed"), 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.single() is CarPlayStatus.Failed)
        // The AirPlay session is still alive: its end and transport callbacks must keep arriving.
        assertTrue(controller.wirelessStateCallForTest("getRunning") as Boolean)
        assertEquals(0, hotspotCloses)
        verify(bootstrap, never()).close()
        // Without a live session the same status is terminal for the run.
        ReflectionHelpers.setField(controller, "activeSession", null)
        onStatus.invoke(run, CarPlayStatus.Failed("Wireless iAP2 tunnel closed after the session ended"), 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.wirelessStateCallForTest("getRunning") as Boolean)
        assertEquals(2, statuses.size)
    }

    @Test fun renderedVideoWithoutTunnelPreservesBootstrapAndReportsFallbackOnce() {
        proofCall("rendered", 0, session)
        timeout()
        timeout()
        assertTrue(controller.wirelessStateCallForTest("getActiveReported") as Boolean)
        assertEquals(0, hotspotCloses)
        assertSame(session, ReflectionHelpers.getField(controller, "activeSession"))
        assertEquals(listOf(CarPlayStatus.WirelessActiveFallback), statuses)
        verify(bootstrap, never()).close()
        assertTrue(diagnostics.any { it.startsWith("STEP handoff/fallback:") && it.contains("tunnel iAP2 unavailable") })
        assertFalse(diagnostics.any { it.startsWith("STEP handoff/complete:") })
        assertEquals(0, confirmations)
    }

    @Test fun delayedTunnelReadinessWinsOverFallback() {
        val closed = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer { closed.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); null }.`when`(bootstrap).close()
        controller.wirelessRunForTest().javaClass.getDeclaredMethod("onWirelessTunnelReady", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller.wirelessRunForTest(), 0)
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        val handoff = Thread.getAllStackTraces().keys.single { it.name == "xcertplay-wireless-handoff" }
        release.countDown()
        handoff.join(2_000)
        assertFalse("Normal handoff did not finish", handoff.isAlive)
        timeout()
        assertEquals(0, hotspotCloses)
        assertEquals(listOf(CarPlayStatus.WirelessActive), statuses)
        verify(bootstrap, times(1)).close()
    }

    @Test fun aLateTunnelPromotesFallbackAndClosesBluetoothOnlyOnce() {
        proofCall("rendered", 0, session)
        timeout()
        assertEquals(listOf(CarPlayStatus.WirelessActiveFallback), statuses)
        val closing = CountDownLatch(1)
        doAnswer { closing.countDown(); null }.`when`(bootstrap).close()
        tunnelReady(0)
        tunnelReady(0)
        assertTrue(closing.await(2, TimeUnit.SECONDS))
        awaitHandoff()
        assertEquals(listOf(CarPlayStatus.WirelessActiveFallback, CarPlayStatus.WirelessActive), statuses)
        verify(bootstrap, times(1)).close()
        assertFalse(controller.wirelessStateCallForTest("getHandoff").toString() == "BLUETOOTH_FALLBACK")
        assertEquals(0, hotspotCloses)
    }

    @Test fun aQueuedTunnelCompletionCannotCloseTheNextGenerationsBootstrap() {
        val replacement = mock(BluetoothSocket::class.java)
        val lock = ReflectionHelpers.getField<Any>(controller, "wirelessResourceLock")
        synchronized(lock) {
            tunnelReady(0)
            // Force replacement to win before the queued completion can claim its resources.
            controller.replaceWirelessRunForTest(1)
            ReflectionHelpers.setField(controller.wirelessRunForTest(), "bluetoothSocket", replacement)
        }
        awaitHandoff()
        verify(bootstrap, never()).close()
        verify(replacement, never()).close()
        assertTrue(statuses.isEmpty())
    }

    @Test fun tunnelReadinessWithoutAHandoffRequestDoesNotReleaseBluetooth() {
        controller.resetWirelessStateForTest()
        tunnelReady(0)
        awaitHandoff()
        verify(bootstrap, never()).close()
        assertTrue(statuses.isEmpty())
        assertTrue(controller.wirelessStateCallForTest("getTunnelReady") as Boolean)
    }

    @Test fun onlyTheCurrentRenderedSessionMayKeepTheBluetoothControlLoopAlive() {
        assertFalse(keepControlAlive(0))
        proofCall("rendered", 0, session)
        assertTrue(keepControlAlive(0))
        assertFalse(keepControlAlive(1))
        proofCall("end", 0, session)
        assertFalse(keepControlAlive(0))
        proofCall("activate", 0, session)
        proofCall("rendered", 0, session)
        controller.wirelessStateCallForTest("fail")
        assertFalse(keepControlAlive(0))
    }

    @Test fun endedSessionCannotUseItsOldRenderedFrame() {
        proofCall("rendered", 0, session)
        proofCall("end", 0, session)
        ReflectionHelpers.setField(controller, "activeSession", null)
        timeout()
        assertTrue(statuses.single() is CarPlayStatus.Failed)
        assertEquals(1, hotspotCloses)
    }

    @Test fun oldGenerationCannotCloseReplacementResources() {
        proofCall("rendered", 0, session)
        controller.wirelessStateCallForTest("retire")
        timeout()
        assertFalse(controller.wirelessStateCallForTest("getActiveReported") as Boolean)
        assertEquals(0, hotspotCloses)
        assertTrue(statuses.isEmpty())
        verify(bootstrap, never()).close()
    }

    @Test fun missingHandoffRequestLeavesSessionAndBootstrapUntouched() {
        proofCall("rendered", 0, session)
        controller.resetWirelessStateForTest()
        timeout()
        assertEquals(0, hotspotCloses)
        assertTrue(statuses.isEmpty())
        verify(bootstrap, never()).close()
    }

    @Test fun priorServiceFailureCannotBeReplacedByVideoFallback() {
        proofCall("rendered", 0, session)
        controller.javaClass.getDeclaredMethod("fail", Throwable::class.java, Int::class.javaObjectType)
            .apply { isAccessible = true }.invoke(controller, java.io.IOException("AirPlay service disconnected"), 0)
        assertTrue(controller.wirelessStateCallForTest("getFailedOnce") as Boolean)
        timeout()
        assertTrue(statuses.single() is CarPlayStatus.Failed)
        assertFalse(controller.wirelessStateCallForTest("getActiveReported") as Boolean)
        assertEquals(0, hotspotCloses)
        verify(bootstrap, never()).close()
    }

    private fun timeout() {
        controller.wirelessRunForTest().javaClass.getDeclaredMethod("handleWirelessHandoffTimeout", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller.wirelessRunForTest(), 0)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun tunnelReady(generation: Int) {
        controller.wirelessRunForTest().javaClass.getDeclaredMethod("onWirelessTunnelReady", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller.wirelessRunForTest(), generation)
    }
    private fun keepControlAlive(generation: Int): Boolean =
        controller.wirelessRunForTest().javaClass.getDeclaredMethod("keepBluetoothControlAlive", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller.wirelessRunForTest(), generation) as Boolean
    private fun awaitHandoff() {
        for (thread in Thread.getAllStackTraces().keys.filter { it.name == "xcertplay-wireless-handoff" }) {
            thread.join(2_000)
            assertFalse("Handoff worker did not finish", thread.isAlive)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun proofCall(name: String, vararg args: Any) {
        proof.javaClass.declaredMethods.single { it.name == name }.apply { isAccessible = true }.invoke(proof, *args)
    }
}
