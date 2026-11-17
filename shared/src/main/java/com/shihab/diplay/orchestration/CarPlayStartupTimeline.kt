package com.shihab.diplay.orchestration

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * Low-rate, privacy-safe checkpoints for car-hotspot and existing-Wi-Fi cold/warm starts.
 * elapsedRealtime includes sleep; never compare it directly with System.nanoTime timestamps.
 * The first decoded frame/callback is not proof that the physical display has presented it.
 */
object CarPlayStartupTimeline {
    enum class Stage {
        BOOT_BROADCAST,
        /** The boot receiver opened the CarPlay screen itself, skipping the DiPlay home screen. */
        BOOT_DIRECT_HOST,
        /**
         * The same, at user unlock right after LOCKED_BOOT_COMPLETED: a head unit without a lock-screen
         * credential unlocks about 10 s before BOOT_COMPLETED reaches the app (v129: 38.4 s vs 48.4 s).
         */
        UNLOCK_DIRECT_HOST,
        /**
         * ECARX: the launcher's home screen resumed and began starting the head unit's services
         * (`EAS_LAUNCH_BAT_0ST`); the unlock launch waits for it so CarPlay does not cover it first.
         */
        LAUNCHER_FIRST_BATCH,
        /** The same wait ended without that broadcast, after the 5 s wait. */
        LAUNCHER_FIRST_BATCH_MISSED,
        /**
         * The first-post-boot gate stopped waiting for the car hotspot before it opened the CarPlay
         * screen. Carries `waitedMs=`, `reason=ready|timeout|cancelled|unobservable|error`,
         * `apEnabled=` and `address=`.
         */
        BOOT_READY_GATE,
        LAUNCHER_CREATE,
        LAUNCHER_RESUME,
        AUTO_CONNECT_DISPATCH,
        HOTSPOT_PREPARE_BEGIN,
        HOTSPOT_PREPARE_END,
        PROJECTION_REQUEST,
        HOST_CREATE,
        HOST_START,
        HOST_STOP,
        CONTROLLER_START,
        LOCAL_MFI_READY,
        WIRELESS_RUN_START,
        /** The head unit's Bluetooth radio was confirmed ON; RFCOMM cannot start before this. */
        BLUETOOTH_READY,
        NETWORK_BACKEND_READY,
        ENDPOINT_READY,
        AIRPLAY_LISTENING,
        BONJOUR_STARTED,
        RFCOMM_CONNECTED,
        IAP2_CSM_READY,
        IPHONE_MFI_ACCEPTED,
        START_SESSION_SENT,
        AIRPLAY_TCP_ACCEPTED,
        AIRPLAY_SESSION_ACTIVE,
        VIDEO_FIRST_FRAME,
        VIDEO_DECODER_FIRST_FRAME,
        /** One explicitly requested native output sample after the tunnel command; not a stability claim. */
        TUNNEL_VIDEO_FRAME,
        UI_FIRST_FRAME_CALLBACK,
        /** CarPlayStartSession (0x4301) answered over the Wi-Fi iAP2 tunnel. */
        TUNNEL_START_SESSION_SENT,
        /** Legacy decoder-restart marker; excluded from metrics because foreground reentry contaminated it. */
        STABLE_VIDEO_FRAME,
        WIRELESS_FAILED,
    }

    private const val TAG = "DiPlay-Startup"
    private const val PREFS = "diplay_wireless_startup_timeline"
    private const val KEY = "events"
    private const val LIMIT = 80

    fun isMeasuredMode(mode: WirelessHotspotMode): Boolean =
        mode == WirelessHotspotMode.MANUAL || mode == WirelessHotspotMode.EXISTING_WIFI

    /** The boot count cannot change within a process; read it once, retrying only while unknown. */
    @Volatile private var cachedBootCount = -1

    private fun bootCount(context: Context): Int {
        val known = cachedBootCount
        if (known >= 0) return known
        return runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1).also { if (it >= 0) cachedBootCount = it }
    }

    /**
     * Enum fields and numeric IDs only. Do not add SSID, BSSID, passwords, phone addresses,
     * private authentication material or exception messages to this persisted trace.
     *
     * [detail] is the single documented exception: the boot gate's own wait outcome
     * (`waitedMs=…`, enum `reason=…`, `apEnabled=…` and the head unit's own hotspot
     * `address=`). It never carries anything about the phone.
     */
    @Synchronized
    fun record(
        context: Context,
        stage: Stage,
        mode: WirelessHotspotMode,
        generation: Int? = null,
        owner: Int? = null,
        attempt: Int? = null,
        elapsedMs: Long = SystemClock.elapsedRealtime(),
        decoderId: Int? = null,
        decoderEpoch: Long? = null,
        detail: String? = null,
    ) {
        if (!isMeasuredMode(mode)) return
        val line = format(elapsedMs, stage, mode, bootCount(context),
            Process.myPid(), generation, owner, attempt, detail) +
            if (decoderId != null && decoderEpoch != null) " decoderId=$decoderId decoderEpoch=$decoderEpoch" else ""
        Log.i(TAG, line)
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val recent = prefs.getString(KEY, "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
            prefs.edit().putString(KEY, (recent.takeLast(LIMIT - 1) + line).joinToString("\n")).apply()
        }
    }

    /**
     * One line for the first-post-boot gate's wait: [reason] is the gate's own outcome name
     * (`ready`, `timeout`, `cancelled`, `unobservable`, `error`).
     */
    fun recordBootReadyGate(
        context: Context,
        mode: WirelessHotspotMode,
        waitedMs: Long,
        reason: String,
        apEnabled: Boolean?,
        address: String?,
    ) = record(
        context,
        Stage.BOOT_READY_GATE,
        mode,
        detail = "waitedMs=$waitedMs reason=$reason apEnabled=${apEnabled ?: "unobservable"} " +
            "address=${address ?: "none"}",
    )

    internal fun format(
        elapsedMs: Long,
        stage: Stage,
        mode: WirelessHotspotMode,
        bootCount: Int,
        process: Int,
        generation: Int? = null,
        owner: Int? = null,
        attempt: Int? = null,
        detail: String? = null,
    ): String = buildString {
        append("elapsedSinceBootMs=").append(elapsedMs)
        append(" stage=").append(stage)
        append(" mode=").append(mode)
        generation?.let { append(" generation=").append(it) }
        owner?.let { append(" owner=").append(it) }
        attempt?.let { append(" attempt=").append(it) }
        append(" bootCount=").append(bootCount)
        append(" process=").append(process)
        detail?.let { append(" ").append(it) }
    }

    internal fun metrics(lines: List<String>): String {
        data class Run(val boot: Int, val process: Int, val mode: String, val owner: Int, val generation: Int)
        data class Checkpoint(val stage: Stage, val elapsedMs: Long)
        val runs = linkedMapOf<Run, MutableList<Checkpoint>>()
        // Old or incomplete identities cannot safely join events across boots or process restarts.
        val measuredModes = WirelessHotspotMode.values().filter(::isMeasuredMode).joinToString("|")
        val pattern = Regex("elapsedSinceBootMs=(\\d+) stage=(WIRELESS_RUN_START|VIDEO_FIRST_FRAME|VIDEO_DECODER_FIRST_FRAME|TUNNEL_START_SESSION_SENT|TUNNEL_VIDEO_FRAME|HOST_START|HOST_STOP|WIRELESS_FAILED) " +
            "mode=($measuredModes) generation=(\\d+) owner=(-?\\d+)(?: attempt=\\d+)? bootCount=(\\d+) process=(\\d+)(?: decoderId=-?\\d+ decoderEpoch=\\d+)?")
        for (line in lines) {
            val values = pattern.matchEntire(line)?.groupValues ?: continue
            val elapsed = values[1].toLongOrNull() ?: continue
            val generation = values[4].toIntOrNull() ?: continue
            val owner = values[5].toIntOrNull() ?: continue
            val boot = values[6].toIntOrNull() ?: continue
            val process = values[7].toIntOrNull() ?: continue
            val run = Run(boot, process, values[3], owner, generation)
            runs.getOrPut(run) { mutableListOf() }.add(Checkpoint(Stage.valueOf(values[2]), elapsed))
        }
        return buildString {
            appendLine("--- Wireless startup metrics (retained runs; decoded frames, not physical display) ---")
            if (runs.isEmpty()) appendLine("No attributable run samples; legacy events and unknown boot IDs are excluded.")
            for ((run, checkpoints) in runs) {
                val start = checkpoints.firstOrNull { it.stage == Stage.WIRELESS_RUN_START }?.elapsedMs
                val first = checkpoints.firstOrNull { it.stage == Stage.VIDEO_FIRST_FRAME }?.elapsedMs
                val tunnel = checkpoints.firstOrNull { it.stage == Stage.TUNNEL_START_SESSION_SENT }?.elapsedMs
                val afterTunnel = checkpoints.firstOrNull { it.stage == Stage.TUNNEL_VIDEO_FRAME }?.elapsedMs
                val interrupted = checkpoints.any { it.stage == Stage.HOST_STOP &&
                    start != null && it.elapsedMs >= start && (afterTunnel == null || it.elapsedMs <= afterTunnel) }
                val resume = checkpoints.lastOrNull { it.stage == Stage.HOST_START &&
                    checkpoints.any { before -> before.stage == Stage.HOST_STOP && before.elapsedMs <= it.elapsedMs } }?.elapsedMs
                val restored = checkpoints.firstOrNull { it.stage in listOf(Stage.VIDEO_DECODER_FIRST_FRAME, Stage.TUNNEL_VIDEO_FRAME) &&
                    resume != null && it.elapsedMs >= resume }?.elapsedMs
                val outcome = when {
                    checkpoints.any { it.stage == Stage.WIRELESS_FAILED } -> "FAILED"
                    afterTunnel != null && !interrupted -> "FRAME_AFTER_TUNNEL"
                    first != null -> "FIRST_FRAME"
                    else -> "INCOMPLETE"
                }
                append("bootCount=${run.boot} process=${run.process} mode=${run.mode} owner=${run.owner} generation=${run.generation}")
                append(" outcome=$outcome runStartMs=${start ?: "unknown"}")
                append(" firstFrameSinceBootMs=${if (start != null && first != null && first >= start) first else "unknown"}")
                append(" runToFirstFrameMs=${if (start != null && first != null && first >= start) first - start else "unknown"}")
                append(" tunnelToFrameMs=${if (!interrupted && tunnel != null && afterTunnel != null && afterTunnel >= tunnel) afterTunnel - tunnel else "unknown"}")
                append(" foregroundInterrupted=$interrupted")
                appendLine(" resumeToFrameMs=${if (resume != null && restored != null) restored - resume else "unknown"}")
            }
        }.trimEnd()
    }

    fun report(context: Context): String = runCatching {
        val events = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        "--- Wireless startup timeline (last $LIMIT; elapsedSinceBootMs, not ignition time) ---\n" +
            events.ifBlank { "No hotspot/same-LAN startup samples recorded." } + "\n" + metrics(events.lines())
    }.getOrDefault("Wireless startup timeline unavailable.")
}
