package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.hud.EcarxVehicleSignalProbe
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What happened to the screen, the car's power and door signals, and CarPlay's media commands when
 * the head unit goes dark, for the diagnostic report. Opening the door after the engine is off may
 * drop CarPlay (which stops the music) or only turn the screen off (which does not); a report
 * needs both sides on one timeline to tell them apart.
 *
 * Kept in memory for the whole process, because the host activity is destroyed and recreated each
 * time the screen goes dark, and also written to the host's session log while one is attached.
 * Diagnostic only, except that the door and usage-mode readings also feed [MusicPause].
 */
internal object MediaPowerDiagnostics {
    private const val TAG = "DiPlayMediaPower"
    internal const val ENTRIES = 160

    private val entries = ArrayDeque<String>()
    private var sink: ((String) -> Unit)? = null
    private var appContext: Context? = null
    private var probe: EcarxVehicleSignalProbe? = null
    private var lastDisplayState: Int? = null
    private val main = Handler(Looper.getMainLooper())

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            note("Screen broadcast ${intent.action?.substringAfterLast('.')} ${screenState(context)}")
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val context = appContext ?: return
            val state = defaultDisplay(context)?.state
            // Brightness and mode changes also arrive here; only the power state matters.
            if (state == lastDisplayState) return
            lastDisplayState = state
            note("Screen display changed ${screenState(context)}")
        }
    }

    /** Once per process; later calls only refresh nothing. */
    @Synchronized
    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        runCatching {
            ContextCompat.registerReceiver(app, screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_EXPORTED)
        }.onFailure { note("Screen broadcast register failed: ${it.javaClass.simpleName}") }
        runCatching {
            lastDisplayState = defaultDisplay(app)?.state
            app.getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, main)
        }.onFailure { note("Screen display listener failed: ${it.javaClass.simpleName}") }
        note("Media power diagnostics started ${screenState(app)}")
        if (EcarxVehicleSignalProbe.supported(app)) {
            val pause = MusicPauseSettings.policy(app, ::note)
            probe = EcarxVehicleSignalProbe(app, ::note) { id, value ->
                when (id) {
                    EcarxVehicleSignalProbe.USAGE_MODE -> (value as? Int)?.let(pause::onUsageMode)
                    EcarxVehicleSignalProbe.DOOR_DRIVER ->
                        MusicPause.doorOpen(value as? ByteArray)?.let(pause::onDriverDoor)
                }
            }.also { it.start() }
        } else {
            note("Vehicle signal probe not started: not an ECARX car service")
        }
    }

    /** The host's session log; replaced by a newer host, cleared only by the one that set it. */
    @Synchronized
    fun attach(log: (String) -> Unit) { sink = log }

    @Synchronized
    fun detach(log: (String) -> Unit) { if (sink === log) sink = null }

    fun note(message: String, nowMillis: Long = System.currentTimeMillis()) {
        Log.i(TAG, message)
        val target = synchronized(this) {
            if (entries.size == ENTRIES) entries.removeFirst()
            entries.addLast("${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))}  $message")
            sink
        }
        target?.let { runCatching { it("Media power: $message") } }
    }

    fun screenState(context: Context?): String {
        val app = context?.applicationContext ?: return "screen=unknown"
        val display = runCatching { defaultDisplay(app)?.state }.getOrNull()
        val interactive = runCatching { app.getSystemService(PowerManager::class.java)?.isInteractive }.getOrNull()
        return "display=${displayStateName(display)} interactive=${interactive ?: "unknown"}"
    }

    @Synchronized
    fun report(): List<String> = buildList {
        add("--- Screen, car power and media commands (last $ENTRIES since DiPlay started) ---")
        add("Now: ${screenState(appContext)} probe=${if (probe != null) "running" else "off"}")
        if (entries.isEmpty()) add("No events recorded") else addAll(entries)
    }

    internal fun displayStateName(state: Int?): String = when (state) {
        null -> "unknown"
        Display.STATE_OFF -> "OFF"
        Display.STATE_ON -> "ON"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_VR -> "VR"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        Display.STATE_UNKNOWN -> "UNKNOWN"
        else -> "state$state"
    }

    @Synchronized
    internal fun resetForTest() {
        entries.clear()
        sink = null
        probe?.stop()
        probe = null
        appContext?.let { app ->
            runCatching { app.unregisterReceiver(screenReceiver) }
            runCatching { app.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener) }
        }
        appContext = null
        lastDisplayState = null
    }

    private fun defaultDisplay(context: Context): Display? =
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
}
