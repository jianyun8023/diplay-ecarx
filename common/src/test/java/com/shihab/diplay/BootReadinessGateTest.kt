package com.shihab.diplay

import android.content.Context
import android.os.Looper
import com.shihab.diplay.network.CarHotspotReady
import com.shihab.diplay.network.CarHotspotReady.Outcome
import com.shihab.diplay.network.CarHotspotReady.Reason
import com.shihab.diplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BootReadinessGateTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun resetTimeline() {
        app.getSharedPreferences("diplay_wireless_startup_timeline", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun outcome(reason: Reason, waitedMs: Long = 0, apEnabled: Boolean? = null,
        address: String? = null): Outcome = Outcome(reason, waitedMs, apEnabled, address)

    /** Runs the gate and returns the outcomes it delivered on the main thread. */
    private fun runGate(
        mode: WirelessHotspotMode = WirelessHotspotMode.MANUAL,
        isCurrent: () -> Boolean = { true },
        await: (Context, () -> Boolean) -> Outcome,
    ): List<Outcome> {
        val delivered = mutableListOf<Outcome>()
        val done = CountDownLatch(1)
        BootReadinessGate.awaitAsync(app, mode, isCurrent, await) {
            delivered += it
            done.countDown()
        }
        // The worker thread posts its result to the main looper; pump both until it lands.
        val deadline = System.currentTimeMillis() + 5_000
        while (done.count > 0L && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("the gate must deliver its outcome", done.await(1, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        return delivered
    }

    private fun timelineLines(): List<String> =
        app.getSharedPreferences("diplay_wireless_startup_timeline", Context.MODE_PRIVATE)
            .getString("events", "").orEmpty().lineSequence().filter { it.contains("stage=BOOT_READY_GATE") }.toList()

    @Test fun onlyACarHotspotBootLaunchWaits() {
        assertTrue(BootReadinessGate.waits(WirelessHotspotMode.MANUAL))
        // Existing Wi-Fi has no AP to wait for, and every other backend cannot take the direct path.
        assertFalse(BootReadinessGate.waits(WirelessHotspotMode.EXISTING_WIFI))
        WirelessHotspotMode.entries.filter { it != WirelessHotspotMode.MANUAL }
            .forEach { assertFalse(it.name, BootReadinessGate.waits(it)) }
    }

    @Test fun aReadyHotspotOpensAtOnce() {
        var workerThread = true
        val delivered = runGate(await = { _, _ ->
            workerThread = Looper.myLooper() == Looper.getMainLooper()
            outcome(Reason.READY, waitedMs = 250, apEnabled = true, address = "wlan0/192.168.43.1")
        })
        assertEquals(1, delivered.size)
        assertEquals(Reason.READY, delivered.single().reason)
        assertTrue(BootReadinessGate.opens(delivered.single()))
        // The wait never runs on the receiver's thread.
        assertFalse("the gate must wait off the main thread", workerThread)
    }

    @Test fun aHotspotThatNeverComesUpStillOpensAtTheCap() {
        val delivered = runGate(await = { _, _ ->
            Thread.sleep(50)
            outcome(Reason.TIMEOUT, waitedMs = BootReadinessGate.BOOT_READY_GATE_MILLIS, apEnabled = false)
        })
        assertEquals(1, delivered.size)
        assertEquals(20_000L, BootReadinessGate.BOOT_READY_GATE_MILLIS)
        assertEquals(Reason.TIMEOUT, delivered.single().reason)
        assertEquals(BootReadinessGate.BOOT_READY_GATE_MILLIS, delivered.single().waitedMs)
        // Fail-open: the timeout must not keep the screen closed.
        assertTrue(BootReadinessGate.opens(delivered.single()))
    }

    @Test fun anUnobservableHotspotDoesNotBlockTheLaunch() {
        val delivered = runGate(await = { _, _ -> outcome(Reason.UNOBSERVABLE) })
        assertEquals(Reason.UNOBSERVABLE, delivered.single().reason)
        assertEquals(0L, delivered.single().waitedMs)
        assertTrue(BootReadinessGate.opens(delivered.single()))
    }

    @Test fun aCancelledWaitLeavesTheScreenToWhoeverOwnsIt() {
        val delivered = runGate(isCurrent = { false }, await = { _, current -> outcome(if (current()) Reason.READY else Reason.CANCELLED) })
        assertEquals(Reason.CANCELLED, delivered.single().reason)
        assertFalse(BootReadinessGate.opens(delivered.single()))
    }

    @Test fun aFailingWaitOpensAnyway() {
        val delivered = runGate(await = { _, _ -> throw IllegalStateException("sampling failed") })
        assertEquals(Reason.ERROR, delivered.single().reason)
        assertTrue(BootReadinessGate.opens(delivered.single()))
    }

    @Test fun onlyOneTimelineLinePerBootLaunch() {
        // Two boot callbacks can race (unlock and BOOT_COMPLETED); each gate run still records one line.
        runGate(await = { _, _ -> outcome(Reason.READY, 250, true, "wlan0/192.168.43.1") })
        runGate(await = { _, _ -> outcome(Reason.TIMEOUT, BootReadinessGate.BOOT_READY_GATE_MILLIS, false) })
        val lines = timelineLines()
        assertEquals(lines.toString(), 2, lines.size)
        assertTrue(lines[0], lines[0].contains("reason=ready"))
        assertTrue(lines[1], lines[1].contains("reason=timeout"))
        assertTrue(lines[1], lines[1].contains("address=none"))
        assertTrue(lines[1], lines[1].contains("apEnabled=false"))
    }
}
