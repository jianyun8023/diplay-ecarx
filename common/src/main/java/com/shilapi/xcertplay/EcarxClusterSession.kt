package com.shilapi.xcertplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.hud.ClusterMapIntent
import com.shilapi.xcertplay.hud.NavigationOutputs
import com.shilapi.xcertplay.hud.EcarxOutputSettings
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController

/** The cluster window and its selected video source belong to the retained session, never an Activity. */
internal class EcarxClusterSession(
    context: Context,
    private val controller: CarPlayController,
    private val sink: AndroidMediaSink,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val displays = app.getSystemService(DisplayManager::class.java)
    private var presentation: EcarxClusterPresentation? = null
    private var surface: Surface? = null
    private var outputSession: AirPlaySession? = null
    private var mirrorMain = false
    private var binding = 0
    private var firstFrameObserved = false
    private var projectionRequested = false
    private var showFailure: String? = null
    @Volatile private var intent = ClusterMapIntent.NO_ROUTE
    private val wanted get() = intent == ClusterMapIntent.SHOWN
    @Volatile private var closed = false
    private val stateListener: () -> Unit = ::onSessionChanged
    private val routeListener: (ClusterMapIntent) -> Unit = ::onRouteRequested
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
    fun onRouteRequested(next: ClusterMapIntent) {
        main.post {
            if (closed) return@post
            if (intent != next) log("intent map=$next source=route-and-option")
            intent = next
            apply()
        }
    }

    fun onSessionChanged() {
        main.post(::apply)
    }

    fun diagnostics(): String =
        "sessionActive=${controller.activeAirPlaySession() != null} panelWanted=$wanted " +
            "presentationShowing=${presentation?.isShowing == true} clusterSurfaceValid=${surface?.isValid == true} " +
            "source=${if (mirrorMain) "main-mirror" else "independent-map"} " +
            "binding=$binding firstFrameObserved=$firstFrameObserved projectionRequested=$projectionRequested physicalDisplay=unobserved"

    private fun apply() {
        if (closed) return
        val session = controller.activeAirPlaySession()
        if (session !== outputSession) {
            hide()
            outputSession = session
            mirrorMain = EcarxOutputSettings.mirrorMainScreen(app)
        }
        if (session?.hasRenderedMainFrame != true || intent == ClusterMapIntent.NO_ROUTE || (!wanted && mirrorMain)) {
            hide()
            return
        }
        // Creating a hidden route's window can make OEM firmware display the map anyway.
        if (!wanted && presentation == null) return
        // DIM controls map visibility. The route owns the independent window and its video source.
        if (!wanted) projectionRequested = false
        surface?.takeIf { it.isValid }?.let {
            if (mirrorMain) sink.setMirrorSurface(110, MIRROR_KEY, it) else sink.setSurface(111, it)
            // The phone must draw before DIM selects this window; waiting for DIM here would deadlock first-frame gating.
            controller.setClusterOutputVisible(!mirrorMain)
            if (wanted && (mirrorMain || firstFrameObserved) && !projectionRequested) {
                projectionRequested = NavigationOutputs.requestPresentation(app, true,
                    if (mirrorMain) "cluster mirror surface ready" else "cluster first texture frame")
            }
        }
        val calibrationVisible = wanted && !mirrorMain && AirPlayPersistence.loadClusterCalibration(app)
        presentation?.let {
            it.setCalibrationVisible(calibrationVisible)
            return
        }
        val display = displays?.getDisplay(DISPLAY_ID) ?: return
        lateinit var panel: EcarxClusterPresentation
        panel = EcarxClusterPresentation(app, display, onSurface = { next ->
            if (presentation === panel) onSurface(next)
        }, onPresented = { observedSurface ->
            if (!closed && presentation === panel && controller.activeAirPlaySession() === outputSession &&
                surface === observedSurface && observedSurface.isValid && !mirrorMain && !firstFrameObserved) {
                firstFrameObserved = true
                log("video first-frame stream=111 binding=$binding observation=texture-updated " +
                    "${panel.videoGeometryDiagnostics()} physicalDisplay=unobserved")
                main.post(::apply)
            }
        })
        val video = CarPlayBackgroundSession.snapshot()
        if (mirrorMain && video != null) panel.setVideoSize(video.width, video.height)
        else panel.setVideoSize(WIDTH, HEIGHT)
        panel.setCalibrationVisible(calibrationVisible)
        presentation = panel
        panel.setOnDismissListener {
            // An old Dialog's asynchronous dismiss must never clear its replacement.
            if (presentation === panel) {
                presentation = null
                onSurface(null)
                log("window dismissed source=window-callback")
                if (!closed) main.post(::apply)
            }
        }
        try {
            panel.show()
        } catch (error: Exception) {
            presentation = null
            onSurface(null)
            runCatching { panel.dismiss() }
            val failure = error.javaClass.simpleName
            if (showFailure != failure) log("window show-failed error=$failure")
            showFailure = failure
            return
        }
        showFailure = null
        log("window shown awaitingSurface=${surface == null} " +
            "source=${if (mirrorMain) "main-mirror" else "independent-map"} " +
            "${panel.videoGeometryDiagnostics()} physicalDisplay=unobserved")
    }

    private fun hide() {
        controller.setClusterOutputVisible(false)
        val panel = presentation ?: return
        presentation = null
        onSurface(null)
        val dismissed = runCatching { panel.dismiss() }
        log("window dismiss-return accepted=${dismissed.isSuccess} showing=${panel.isShowing} source=session-owner")
    }

    private fun onSurface(next: Surface?) {
        val previous = surface
        if (previous === next) return
        if (previous != null) {
            if (next != null && projectionRequested) NavigationOutputs.requestPresentation(app, false, "cluster binding replaced")
            if (mirrorMain) sink.setMirrorSurface(110, MIRROR_KEY, null) else sink.clearSurface(111, previous)
        }
        surface = next
        firstFrameObserved = false
        projectionRequested = false
        if (next != null) {
            binding++
            log("surface attached binding=$binding valid=${next.isValid} source=${if (mirrorMain) "main-mirror" else "independent-map"} " +
                "${presentation?.videoGeometryDiagnostics()}")
            apply()
        } else {
            controller.setClusterOutputVisible(false)
            NavigationOutputs.requestPresentation(app, false, "cluster surface detached")
            log("surface detached binding=$binding")
        }
    }

    fun close() {
        if (closed) return
        closed = true
        controller.clearClusterOutputStateListener(stateListener)
        NavigationOutputs.clearClusterPanelOwner(routeListener)
        main.removeCallbacksAndMessages(null)
        displays?.unregisterDisplayListener(displayListener)
        if (Looper.myLooper() == Looper.getMainLooper()) hide() else main.post(::hide)
    }

    private fun log(message: String) {
        NavigationOutputs.diagnostic("panel $message")
    }

    companion object {
        private const val MIRROR_KEY = "ecarx-cluster-main"
        const val DISPLAY_ID = 1
        const val WIDTH = 1920
        const val HEIGHT = 720
    }
}
