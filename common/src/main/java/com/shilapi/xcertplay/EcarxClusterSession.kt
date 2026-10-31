package com.shilapi.xcertplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.hud.NavigationOutputs
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController

/** The route's independent stream111 window belongs to the retained session, never to its Activity. */
internal class EcarxClusterSession(
    context: Context,
    private val controller: CarPlayController,
    private val sink: AndroidMediaSink,
    private var report: (String) -> Unit,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val displays = app.getSystemService(DisplayManager::class.java)
    private var presentation: EcarxClusterPresentation? = null
    private var surface: Surface? = null
    @Volatile private var wanted = false
    @Volatile private var closed = false
    private val stateListener: () -> Unit = ::onSessionChanged
    private val routeListener: (Boolean) -> Unit = ::onRouteRequested
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { if (displayId == DISPLAY_ID) apply() }
        override fun onDisplayRemoved(displayId: Int) { if (displayId == DISPLAY_ID) hide() }
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    init {
        displays?.registerDisplayListener(displayListener, main)
        controller.setClusterOutputStateListener(stateListener)
        NavigationOutputs.setClusterPanelOwner(routeListener)
    }

    /** DIM calls under its own lock: record intent and return without calling back into the bridge. */
    fun onRouteRequested(show: Boolean) {
        if (closed) return
        wanted = show
        main.post(::apply)
    }

    fun onSessionChanged() {
        main.post(::apply)
    }

    fun attachReport(sink: (String) -> Unit) {
        report = sink
    }

    fun diagnostics(): String =
        "sessionActive=${controller.activeAirPlaySession() != null} panelWanted=$wanted " +
            "presentationShowing=${presentation?.isShowing == true} clusterSurfaceValid=${surface?.isValid == true}"

    private fun apply() {
        if (closed) return
        val session = controller.activeAirPlaySession()
        if (!wanted || session?.hasRenderedMainFrame != true) {
            hide()
            return
        }
        surface?.let { sink.setSurface(111, it) }
        if (presentation != null) return
        val display = displays?.getDisplay(DISPLAY_ID) ?: return
        lateinit var panel: EcarxClusterPresentation
        panel = EcarxClusterPresentation(app, display) { next ->
            if (presentation === panel) onSurface(next)
        }
        panel.setVideoSize(WIDTH, HEIGHT)
        presentation = panel
        panel.setOnDismissListener {
            // An old Dialog's asynchronous dismiss must never clear its replacement.
            if (presentation === panel) {
                presentation = null
                onSurface(null)
                NavigationOutputs.requestPresentation(app, false, "cluster window dismissed")
                log("Cluster panel: session window dismissed")
                if (!closed && wanted) main.post(::apply)
            }
        }
        try {
            panel.show()
        } catch (error: Exception) {
            presentation = null
            onSurface(null)
            runCatching { panel.dismiss() }
            log("Cluster panel: show failed ${error.message}")
            return
        }
        // The window exists before enabling the platform's switch onto display1.
        val accepted = NavigationOutputs.requestPresentation(app, true, "cluster stream")
        log("Cluster panel: showing on \"${display.name}\" " +
            "${display.mode.physicalWidth}x${display.mode.physicalHeight} projection=$accepted source=independent map session-owned")
    }

    private fun hide() {
        val panel = presentation ?: return
        presentation = null
        NavigationOutputs.requestPresentation(app, false, "cluster stream")
        onSurface(null)
        runCatching { panel.dismiss() }
        log("Cluster panel: dismissed session-owned")
    }

    private fun onSurface(next: Surface?) {
        val previous = surface
        if (previous === next) return
        if (previous != null) sink.clearSurface(111, previous)
        surface = next
        if (next != null && !closed && wanted && controller.activeAirPlaySession()?.hasRenderedMainFrame == true) {
            sink.setSurface(111, next)
        }
        if (next == null) log("Cluster surface: detached ${diagnostics()}")
    }

    fun close() {
        if (closed) return
        closed = true
        wanted = false
        controller.clearClusterOutputStateListener(stateListener)
        NavigationOutputs.clearClusterPanelOwner(routeListener)
        main.removeCallbacksAndMessages(null)
        displays?.unregisterDisplayListener(displayListener)
        if (Looper.myLooper() == Looper.getMainLooper()) hide() else main.post(::hide)
    }

    private fun log(message: String) {
        Log.i("DiPlay-Cluster", message)
        report(message)
    }

    companion object {
        const val DISPLAY_ID = 1
        const val WIDTH = 1920
        const val HEIGHT = 720
    }
}
