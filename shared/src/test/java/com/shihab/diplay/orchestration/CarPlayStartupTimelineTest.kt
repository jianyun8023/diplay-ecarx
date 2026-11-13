package com.shihab.diplay.orchestration

import android.content.Context
import android.os.Process
import android.provider.Settings
import com.shihab.diplay.orchestration.CarPlayStartupTimeline.Stage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayStartupTimelineTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        app.getSharedPreferences("diplay_wireless_startup_timeline", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun onlyCarHotspotAndExistingWifiProduceBoundedSafeTimeline() {
        val modes = listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.EXISTING_WIFI)
        for (i in 0 until 90) CarPlayStartupTimeline.record(app, Stage.NETWORK_BACKEND_READY,
            modes[i % modes.size], generation = i, owner = 21, attempt = 1)
        CarPlayStartupTimeline.record(app, Stage.VIDEO_FIRST_FRAME, WirelessHotspotMode.WIFI_P2P)
        val lines = CarPlayStartupTimeline.report(app).lineSequence().filter { it.startsWith("elapsedSinceBootMs=") }.toList()
        assertEquals(80, lines.size)
        assertTrue(lines.first().contains("generation=10"))
        assertTrue(lines.last().contains("generation=89"))
        assertTrue(lines.none { it.contains("WIFI_P2P") })
        assertTrue(lines.all { it.contains("owner=21") && it.contains("attempt=1") })
    }

    @Test fun formattingUsesOneClockAndOnlyEnumAndNumericFields() {
        val line = CarPlayStartupTimeline.format(1234, Stage.START_SESSION_SENT,
            WirelessHotspotMode.MANUAL, bootCount = 3, process = 42, generation = 7, owner = 31, attempt = 2)
        assertEquals("elapsedSinceBootMs=1234 stage=START_SESSION_SENT mode=MANUAL generation=7 owner=31 attempt=2 bootCount=3 process=42", line)
        assertFalse(line.contains("ssid", ignoreCase = true))
        assertFalse(line.contains("token", ignoreCase = true))
        assertTrue(CarPlayStartupTimeline.report(app).contains("No hotspot/same-LAN startup samples"))
    }

    @Test fun recordedEventsCarrySystemBootAndProcessIdentityIntoTheReport() {
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 12)
        CarPlayStartupTimeline.record(app, Stage.WIRELESS_RUN_START,
            WirelessHotspotMode.MANUAL, generation = 1, owner = 23)
        CarPlayStartupTimeline.record(app, Stage.VIDEO_FIRST_FRAME,
            WirelessHotspotMode.MANUAL, generation = 1, owner = 23)
        val report = CarPlayStartupTimeline.report(app)
        assertTrue(report, report.contains("bootCount=12 process=${Process.myPid()} mode=MANUAL owner=23 generation=1 outcome=FIRST_FRAME"))
    }

    private fun event(stage: Stage, time: Long, boot: Int = 3, process: Int = 42,
                      owner: Int = 31, generation: Int = 7): String =
        CarPlayStartupTimeline.format(time, stage, WirelessHotspotMode.MANUAL,
            boot, process, generation, owner)

    @Test fun metricsKeepFirstFrameAndAllowStableCheckpointBeforeItsFirstFrameCallback() {
        val result = CarPlayStartupTimeline.metrics(listOf(
            event(Stage.WIRELESS_RUN_START, 40_000),
            event(Stage.STABLE_VIDEO_FRAME, 45_000),
            event(Stage.VIDEO_FIRST_FRAME, 45_001),
            event(Stage.VIDEO_FIRST_FRAME, 48_000),
        ))
        assertTrue(result, result.contains("outcome=STABLE_FRAME runStartMs=40000 firstFrameSinceBootMs=45001 runToFirstFrameMs=5001 runToStableFrameMs=5000"))
    }

    @Test fun metricsNeverJoinDifferentBootsProcessesControllersOrGenerations() {
        val result = CarPlayStartupTimeline.metrics(listOf(
            event(Stage.WIRELESS_RUN_START, 10),
            event(Stage.VIDEO_FIRST_FRAME, 100, boot = 4),
            event(Stage.VIDEO_FIRST_FRAME, 200, process = 43),
            event(Stage.VIDEO_FIRST_FRAME, 300, owner = 32),
            event(Stage.VIDEO_FIRST_FRAME, 400, generation = 8),
        ))
        val runs = result.lines().filter { it.startsWith("bootCount=") }
        assertEquals(5, runs.size)
        assertTrue(runs.all { it.contains("runToFirstFrameMs=unknown") })
        assertTrue(runs.all { it.contains("firstFrameSinceBootMs=unknown") })
        assertTrue(runs.first().contains("outcome=INCOMPLETE"))
    }

    @Test fun failuresFallbackAndMissingHistoryRemainExplicit() {
        val result = CarPlayStartupTimeline.metrics(listOf(
            event(Stage.WIRELESS_RUN_START, 100),
            event(Stage.WIRELESS_FAILED, 200),
            event(Stage.WIRELESS_RUN_START, 300, generation = 8),
            event(Stage.VIDEO_FIRST_FRAME, 500, generation = 8),
            event(Stage.STABLE_VIDEO_FRAME, 800, generation = 9),
        ))
        assertTrue(result, result.contains("outcome=FAILED runStartMs=100 firstFrameSinceBootMs=unknown"))
        assertTrue(result, result.contains("outcome=FIRST_FRAME runStartMs=300 firstFrameSinceBootMs=500 runToFirstFrameMs=200 runToStableFrameMs=unknown"))
        assertTrue(result, result.contains("outcome=STABLE_FRAME runStartMs=unknown firstFrameSinceBootMs=unknown runToFirstFrameMs=unknown runToStableFrameMs=unknown"))
    }

    @Test fun legacyUnknownAndMalformedEventsCannotProduceMetrics() {
        val valid = event(Stage.WIRELESS_RUN_START, 100)
        val result = CarPlayStartupTimeline.metrics(listOf(
            valid.substringBefore(" bootCount="),
            event(Stage.WIRELESS_RUN_START, 100, boot = -1),
            valid.replace("=100 ", "=999999999999999999999999 "),
            valid.replace("generation=7", "generation=9999999999999999999"),
            valid.replace("stage=WIRELESS_RUN_START", "stage=UNRECOGNIZED"),
            valid.replace("mode=MANUAL", "mode=WIFI_P2P"),
        ))
        assertTrue(result, result.contains("No attributable run samples"))
    }

    @Test fun reportIncludesMetricsAndDoesNotTurnReversedTimestampsIntoDurations() {
        val events = listOf(event(Stage.WIRELESS_RUN_START, 200), event(Stage.VIDEO_FIRST_FRAME, 100))
        app.getSharedPreferences("diplay_wireless_startup_timeline", Context.MODE_PRIVATE)
            .edit().putString("events", events.joinToString("\n")).commit()
        val report = CarPlayStartupTimeline.report(app)
        assertTrue(report.contains("Wireless startup metrics"))
        assertTrue(report.contains("runToFirstFrameMs=unknown"))
        assertTrue(report.contains("firstFrameSinceBootMs=unknown"))
        assertTrue(report.contains(events.first()))
    }
}
