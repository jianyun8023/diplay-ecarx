package com.shihab.diplay

import android.content.Context
import com.shihab.diplay.media.AndroidMediaSink
import com.shihab.diplay.orchestration.CarPlayController
import com.shihab.diplay.orchestration.CarPlayStatus

internal data class CarPlaySessionDisplay(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val hideTopBar: Boolean,
    val hideBottomBar: Boolean,
    // Compare unscaled startup window dimensions, not the scaled video canvas.
    val windowWidth: Int,
    val windowHeight: Int,
    /** The view areas this session declared, or null for the whole screen as one area. */
    val viewAreas: CarPlayViewAreas? = null,
    /** Whether the status bar was on screen when the session started, or null when unknown. */
    val statusBarShown: Boolean? = null,
)

/**
 * The driver keeps the status bar, and another window (the head unit's reversing camera) hid it: the window
 * grew by the bar only for a while. A new session at that size stayed scaled down below the returning bar (#643).
 */
internal fun statusBarHiddenByAnotherWindow(display: CarPlaySessionDisplay, width: Int, height: Int,
    statusBarShown: Boolean?): Boolean =
    !display.hideTopBar && display.statusBarShown == true && statusBarShown == false &&
        width == display.windowWidth && height > display.windowHeight

/** Process-local hand-off for keeping the CarPlay session alive while no Activity is visible. */
internal object CarPlayBackgroundSession {
    /** USB temporarily takes over; the saved wireless preference belongs to the driver. */
    @Volatile var usbTakeover = false
    @Volatile var active = false
    @Volatile var callPopupReady = false
    /** The running session's hotspot: a host that adopts the session never saw its HotspotReady. */
    @Volatile var hotspotReady: CarPlayStatus.HotspotReady? = null
    private var stopAction: (((() -> Unit)) -> Unit)? = null
    private var stopping = false
    private var owner: Any? = null
    @Synchronized fun isOwner(candidate: Any): Boolean = owner === candidate
    @Synchronized fun hasSession(): Boolean = stopAction != null || stopping
    private val stopWaiters = mutableListOf<() -> Unit>()

    fun stop(completion: () -> Unit = {}) {
        // A stop is the driver ending CarPlay (the host's own rebuild does not come here): a later
        // connect with Bluetooth off must say so instead of waiting for the car's radios.
        com.shihab.diplay.network.CarRadioOutage.clear()
        val action: (((() -> Unit)) -> Unit)?
        synchronized(this) {
            if (stopping) { stopWaiters.add(completion); return }
            action = stopAction
            if (action != null) { stopping = true; stopWaiters.add(completion) }
        }
        if (action == null) { completion(); return }
        action.invoke {
            val callbacks = synchronized(this) {
                stopping = false
                stopWaiters.toList().also { stopWaiters.clear() }
            }
            callbacks.forEach { it() }
        }
    }

    data class Snapshot(
        val controller: CarPlayController,
        val sink: AndroidMediaSink,
        val width: Int,
        val height: Int,
        val display: CarPlaySessionDisplay,
    )

    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var clusterOutput: EcarxClusterSession? = null
    private var width = 0
    private var height = 0
    private var display: CarPlaySessionDisplay? = null

    @Synchronized
    fun snapshot(): Snapshot? {
        val currentController = controller ?: return null
        val currentSink = sink ?: return null
        val currentDisplay = display ?: return null
        return Snapshot(currentController, currentSink, width, height, currentDisplay)
    }

    @Synchronized
    fun store(controller: CarPlayController, sink: AndroidMediaSink, width: Int, height: Int,
        owner: Any, display: CarPlaySessionDisplay, stop: (() -> Unit) -> Unit) {
        if (this.controller !== controller || this.sink !== sink) {
            clusterOutput?.close()
            clusterOutput = null
        }
        if (this.controller !== controller) {
            callPopupReady = false
            hotspotReady = null
        }
        this.stopAction = stop
        this.owner = owner
        this.controller = controller
        this.sink = sink
        this.width = width
        this.height = height
        this.display = display
    }

    @Synchronized
    fun bindClusterOutput(context: Context, expected: CarPlayController) {
        if (controller !== expected) return
        if (clusterOutput != null) return
        if (!com.shihab.diplay.hud.EcarxCapabilities.detect(context).identified) return
        clusterOutput = EcarxClusterSession(context.applicationContext, expected, sink ?: return)
    }

    @Synchronized
    fun clusterDiagnostics(expected: CarPlayController?): String =
        if (controller === expected) clusterOutput?.diagnostics() ?: "clusterOwner=false" else "clusterOwner=another-session"

    @Synchronized
    fun clear(expected: CarPlayController? = null, keepOwner: Boolean = false) {
        if (expected != null && controller !== expected) return
        clusterOutput?.close()
        clusterOutput = null
        controller = null
        sink = null
        if (!keepOwner) { stopAction = null; owner = null; usbTakeover = false }
        active = false
        callPopupReady = false
        hotspotReady = null
        width = 0
        height = 0
        display = null
    }
}

/**
 * Appends one line to the session report, for producers that are not the media sink — currently the
 * instrument-cluster bridge, which otherwise exists only in logcat.
 *
 * A named class rather than a lambda on purpose. Kotlin numbers `CarPlayHostActivity`'s lambda
 * classes by their position in the file, so one more lambda in there renames an unrelated one and
 * the class-set regression check lights up; named classes sit outside that numbering. It also gets
 * the bounded, serialised writer in [AsyncDiagnosticLog], which the bridge's own ticker thread needs.
 */
internal class HudReportSink(private val target: SessionLogFile?) : (String) -> Unit {
    override fun invoke(message: String) {
        AsyncDiagnosticLog.append(target, message)
    }
}
