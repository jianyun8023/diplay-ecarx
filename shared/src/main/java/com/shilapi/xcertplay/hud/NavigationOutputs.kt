package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

enum class ClusterMapIntent { NO_ROUTE, HIDDEN, SHOWN }

/**
 * Nonblocking boundary between phone control messages and the head unit's instrument-cluster service.
 *
 * ECARX-only: the one cluster channel is the XUI DIM API (see [EcarxHudBridge]). The unit of work is
 * still a [NavigationOutputWorker] so that a slow or blocked platform call can never stall the iAP2
 * reader thread that feeds [onFrame].
 */
object NavigationOutputs {
    /** Whether identity detection selected ECARX; actual DIM handles belong to the bridge. */
    @Volatile private var useEcarx = false
    @Volatile private var currentSession: (() -> Boolean)? = null

    @Volatile internal var debugLogsEnabled: () -> Boolean = { false }

    @Volatile internal var requestNavigationExit: ((Long) -> Boolean)? = null

    private val diagnosticLock = Any()
    private val diagnosticBacklog = ArrayDeque<String>()
    private var diagnosticSink: ((String) -> Unit)? = null
    private var sessionNumber = 0L
    private var requestNumber = 0L
    private var requestId: Long? = null
    private val requests = linkedMapOf<Long, Pair<Long, Long>>()
    private var terminalSeen = false
    private data class CleanupContext(val session: Long, val requestId: Long?)
    private val cleanupContext = ThreadLocal<CleanupContext>()
    private val processStartedNanos = System.nanoTime()

    fun diagnostic(event: String, id: Long? = null) = synchronized(diagnosticLock) {
        val elapsedMs = (System.nanoTime() - processStartedNanos) / 1_000_000
        val context = cleanupContext.get()
        val request = id ?: if (context != null) context.requestId else requestId
        val origin = requests[request]
        val age = if (origin != null) " requestAgeMs=${(System.nanoTime() - origin.second) / 1_000_000} association=${if (request == requestId) "active" else "ended"}" else ""
        val line = "navigation session=${origin?.first ?: context?.session ?: sessionNumber} request=${request ?: "none"} tMs=$elapsedMs$age $event"
        val sink = diagnosticSink
        if (sink != null) sink(line) else {
            if (diagnosticBacklog.size >= 128) diagnosticBacklog.removeFirst()
            diagnosticBacklog.addLast(line)
        }
    }

    internal fun navigationExitAction(): Long = synchronized(diagnosticLock) {
        if (requestId != null) diagnostic("exit association replaced")
        requestId = ++requestNumber
        if (requests.size >= 128) requests.remove(requests.keys.first())
        requests[requestNumber] = sessionNumber to System.nanoTime()
        terminalSeen = false
        diagnostic("exit action=2")
        requestNumber
    }

    internal fun navigationExitTerminal(state: Int) = synchronized(diagnosticLock) {
        if (requestId != null && !terminalSeen) {
            terminalSeen = true
            diagnostic("exit phoneState=$state")
        }
    }

    internal fun navigationExitFinished(reason: String) = synchronized(diagnosticLock) {
        val context = cleanupContext.get()
        if (requestId != null && (context == null || context.requestId == requestId)) {
            diagnostic("exit association finished reason=$reason terminalSeen=$terminalSeen")
            requestId = null
        }
    }

    private val mqtt = EcarxNavigationMqttObserver({ diagnostic(it) }, debugLogsEnabled = { debugLogsEnabled() })
    private val ecarx = NavigationOutputWorker("diplay-ecarx-output", EcarxHudBridge::clear)

    internal fun scopedClear(clear: () -> Unit = EcarxHudBridge::clear): () -> Unit {
        val context = synchronized(diagnosticLock) { CleanupContext(sessionNumber, requestId) }
        return {
            cleanupContext.set(context)
            try { clear() } finally { cleanupContext.remove() }
        }
    }

    /** Boot recovery must not initialize the cluster display or interrupt active guidance. */
    fun recoverFactoryNavigationIfIdle(context: Context) =
        EcarxHudBridge.recoverFactoryNavigationIfIdle(context.applicationContext)

    /**
     * Initialize the bridge before a phone connects. It clears an old projection request rather
     * than restoring the platform mirror before the host has a CarPlay window (fix37).
     */
    fun onAppOpened(context: Context) {
        val app = context.applicationContext
        recoverFactoryNavigationIfIdle(app)
        if (!EcarxCapabilities.detect(app).identified) return
        EcarxHudBridge.initialize(app)
    }

    fun start(context: Context) {
        val app = context.applicationContext
        useEcarx = EcarxCapabilities.detect(app).identified
        if (useEcarx) {
            ecarx.start(clear = scopedClear()) {
                mqtt.start()
                recoverFactoryNavigationIfIdle(app)
                EcarxHudBridge.initialize(app)
                EcarxHudBridge.setCurrentSession(currentSession)
            }
        }
    }

    /**
     * The window reports source readiness; detaching pauses retries without terminating an active
     * navigation app. OEM callbacks own display intent. Acceptance does not prove physical display.
     */
    fun requestPresentation(context: Context, show: Boolean, origin: String = ""): Boolean {
        EcarxHudBridge.initialize(context)
        return EcarxHudBridge.applyPresentation(show, origin).ok
    }

    fun diagnosticReport(context: Context): String = EcarxHudBridge.diagnostics(context) + "\n" + mqtt.diagnosticReport()

    fun ecarxChanged() {
        if (useEcarx) ecarx.submit { EcarxHudBridge.settingsChanged() }
    }

    /**
     * Routes the cluster bridges' own lines into the session report. Called once the session log
     * exists — see `AndroidMediaSink`'s `onAudioDiagnostic` for the same seam on the audio side.
     *
     * Without it the whole cluster story ("did the call even happen, and on which channel") exists
     * only in logcat, which a car has no way to return.
     */
    fun attachReport(sink: (String) -> Unit) {
        synchronized(diagnosticLock) {
            diagnosticSink = sink
            diagnosticBacklog.forEach(sink)
            diagnosticBacklog.clear()
        }
        EcarxHudBridge.attachReport(sink)
    }

    /**
     * The retained ECARX session owns the instrument-cluster window, independently of Activities.
     * Registration replays the current route intent; `null` unregisters.
     *
     * The owner is called from the DIM worker thread with the bridge's lock held, so it must take
     * the request and return: it is "a route wants the panel", not "the panel is up now". This seam
     * exists because [EcarxHudBridge] is internal to this module while the activity is not.
     */
    fun setClusterPanelOwner(owner: ((ClusterMapIntent) -> Unit)?) {
        EcarxHudBridge.setClusterPanelOwner(owner)
    }

    fun clearClusterPanelOwner(expected: (ClusterMapIntent) -> Unit) {
        EcarxHudBridge.clearClusterPanelOwner(expected)
    }

    internal fun onFrame(frame: Iap2Frame, source: String = "unspecified") {
        if (frame.messageId != RouteGuidanceState.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != RouteGuidanceState.ROUTE_GUIDANCE_MANEUVER_UPDATE &&
            frame.messageId != 0x5204
        ) {
            return
        }
        val owned = frame // Iap2Frame is immutable and defensively copies its payload.
        if (useEcarx) ecarx.submit { EcarxHudBridge.onFrame(owned, source) }
    }

    /** Route-update silence is not a disconnect; the transport owns this liveness check. */
    internal fun onSessionActive(debugLogs: () -> Boolean = { false }, requestExit: ((Long) -> Boolean)? = null, newSession: Boolean = true, replacePrevious: Boolean = false, isCurrent: () -> Boolean) {
        val clearPrevious = if (replacePrevious) scopedClear() else null
        if (newSession) synchronized(diagnosticLock) {
            navigationExitFinished("session-replaced")
            sessionNumber++
            diagnostic("session active")
        }
        debugLogsEnabled = debugLogs
        requestNavigationExit = requestExit
        currentSession = isCurrent
        if (useEcarx) ecarx.submit {
            clearPrevious?.invoke()
            EcarxHudBridge.setCurrentSession(isCurrent)
        }
    }

    /** Best effort while alive; Android does not guarantee callbacks before force-stop. */
    fun endNow() {
        diagnostic("session closing")
        requestNavigationExit = null
        debugLogsEnabled = { false }
        currentSession = null
        ecarx.clear(scopedClear { try { EcarxHudBridge.clear() } finally { mqtt.stop() } })
    }
}
