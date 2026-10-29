package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface

/** Main-thread display lifecycle. The host retains the authoritative stream and sink state. */
internal class CenterMapLifecycle(
    private val context: Context,
    private val available: () -> Boolean,
    private val streamActive: () -> Boolean,
    private val mirrorSink: (String, Surface?) -> Unit,
    private val onMirrorsChanged: () -> Unit,
    private val onTap: () -> Unit,
    private val log: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var hostStarted = false
    private var homeMonitor: HomeScreenMonitor? = null
    private var homeScreenVisible: Boolean? = null
    private val requestShow: () -> Unit = ::show
    private val mirrorsChanged: () -> Unit = {
        onMirrorsChanged()
        if (MapMirrors.launcherShowsMap) CenterMapOverlay.hide()
    }
    private val hideIdle = Runnable {
        if (!streamActive()) CenterMapOverlay.hide()
    }

    fun attach() {
        CenterMapOverlay.requestShow = requestShow
        MapMirrors.sink = mirrorSink
        MapMirrors.onChanged = mirrorsChanged
    }

    fun onHostStarted() {
        hostStarted = true
        CenterMapOverlay.onDiPlayScreenShown()
        homeMonitor?.stop()
        homeScreenVisible = null
    }

    fun onHostStopped(schedule: Boolean) {
        hostStarted = false
        if (schedule) CenterMapOverlay.scheduleShow()
    }

    fun onStreamChanged(active: Boolean) {
        MapMirrors.setStreamActive(active)
        if (active) {
            main.removeCallbacks(hideIdle)
            if (!CenterMapOverlay.shown) CenterMapOverlay.scheduleShow()
        } else {
            // Preserve the card across short reconnects; navigation end hides it after 3 seconds.
            main.postDelayed(hideIdle, 3_000L)
        }
    }

    fun show() {
        if (!available() || hostStarted) return
        if (!AirPlayPersistence.loadCenterMapOverlay(context)) return
        if (!AirPlayPersistence.loadCenterMapFollowsDashboard(context)) return
        if (MapMirrors.launcherShowsMap) return
        if (!streamActive()) return
        if (!CenterMapOverlay.permitted(context)) {
            log("Centre map: no permission to draw over other apps")
            return
        }
        // Without foreground access, or with auto-hide off, retain display over any app.
        if (AirPlayPersistence.loadCenterMapAutoHide(context) && HomeScreenMonitor.hasAccess(context)) {
            val monitor = homeMonitor ?: HomeScreenMonitor(context, ::onHomeScreenVisible).also { homeMonitor = it }
            if (!monitor.running) {
                monitor.start()
                return
            }
            if (homeScreenVisible != true) {
                CenterMapOverlay.hide()
                return
            }
        }
        if (CenterMapOverlay.shown) return
        val shown = CenterMapOverlay.show(context.applicationContext, MapMirrors.streamAspect, { surface ->
            MapMirrors.set(MapMirrors.CARD, surface)
            log(if (surface != null) "Centre map: mirroring the dashboard stream" else "Centre map: mirror stopped")
        }, onTap)
        log("Centre map: card ${if (shown) "shown" else "failed"} streamActive=${streamActive()}")
    }

    private fun onHomeScreenVisible(visible: Boolean) {
        homeScreenVisible = visible
        log("Centre map: home screen ${if (visible) "in front" else "not in front"}")
        if (!AirPlayPersistence.loadCenterMapAutoHide(context)) {
            homeMonitor?.stop()
            if (!hostStarted && !CenterMapOverlay.diPlayInFront()) show()
        } else if (!visible) CenterMapOverlay.hide()
        else if (!hostStarted && !CenterMapOverlay.diPlayInFront()) show()
    }

    fun close() {
        main.removeCallbacks(hideIdle)
        homeMonitor?.stop()
        CenterMapOverlay.hide()
        if (CenterMapOverlay.requestShow === requestShow) CenterMapOverlay.requestShow = null
        if (MapMirrors.sink === mirrorSink) {
            MapMirrors.sink = null
            MapMirrors.setStreamActive(false)
        }
        if (MapMirrors.onChanged === mirrorsChanged) MapMirrors.onChanged = null
    }
}
