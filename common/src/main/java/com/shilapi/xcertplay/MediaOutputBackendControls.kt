package com.shilapi.xcertplay

import android.app.Activity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.mediacenter.MediaCenterProbe
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only MediaCenter diagnostics; probing never registers a session. */
internal class MediaOutputBackendControls(
    private val activity: Activity,
    private val backend: Backend = AndroidBackend(activity),
    private val labelFactory: (String) -> TextView = { TextView(activity).apply { text = it; textSize = 15f } },
    private val buttonFactory: (String, () -> Unit) -> Button = { title, click ->
        Button(activity).apply { text = title; setOnClickListener { click() } } },
) {
    interface Backend {
        fun diagnostics(): String
        fun probe(): String
        fun executeProbe(block: () -> Unit): Boolean
    }
    private class AndroidBackend(private val activity: Activity) : Backend {
        override fun diagnostics() = CarPlayMediaOutput.diagnostics()
        override fun probe() = MediaCenterProbe.report(activity.applicationContext)
        override fun executeProbe(block: () -> Unit) = ProbeExecutor.submit(block)
    }

    private var root: LinearLayout? = null
    private var closed = false
    private var generation = 0
    private var probeBusy = false
    private var probeReport: String? = null
    private var probeRejected = false

    fun build(): LinearLayout = LinearLayout(activity).also {
        it.orientation = LinearLayout.VERTICAL
        root = it
        display()
    }

    private fun display() {
        val view = root ?: return
        view.removeAllViews()
        fun text(value: String) = view.addView(labelFactory(value),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(BLOCK_GAP_DP) })
        fun button(value: String, enabled: Boolean = true, action: () -> Unit) =
            view.addView(buttonFactory(value, action).apply { isEnabled = enabled },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(CONTROL_GAP_DP) })
        text(activity.getString(R.string.settings_media_backend_description))
        text(activity.getString(R.string.settings_media_backend_permission))
        text(activity.getString(R.string.settings_media_backend_diagnostics) + "\n" +
            runCatching { backend.diagnostics() }.getOrDefault("UNAVAILABLE"))
        button(activity.getString(R.string.settings_media_backend_refresh)) { if (!closed) display() }
        button(activity.getString(R.string.settings_media_backend_probe), !probeBusy) { probe() }
        if (probeBusy) text(activity.getString(R.string.settings_media_backend_probe_running))
        if (probeRejected) text(activity.getString(R.string.settings_media_backend_probe_busy))
        probeReport?.let { text(it) }
    }

    private fun probe() {
        if (closed || probeBusy) return
        val token = generation
        probeBusy = true
        probeRejected = false
        display()
        val accepted = runCatching { backend.executeProbe {
            val result = runCatching { backend.probe() }.getOrDefault("READ_ONLY_PROBE_FAILED")
            activity.runOnUiThread {
                probeBusy = false
                if (!closed && generation == token) { probeReport = result; display() }
            }
        } }.getOrDefault(false)
        if (!accepted) { probeBusy = false; probeRejected = true; display() }
    }

    /** Never cancels or unregisters the real media backend. Only invalidates this view's probe result. */
    fun close() { closed = true; generation++; root = null }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private companion object {
        const val BLOCK_GAP_DP = 12
        const val CONTROL_GAP_DP = 8
    }
}

/** A stuck read-only SDK inspection cannot create an unbounded queue or new threads after rotation. */
private object ProbeExecutor {
    private val busy = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diplay-media-probe").apply { isDaemon = true }
    }
    fun submit(block: () -> Unit): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        return try {
            executor.execute { try { block() } finally { busy.set(false) } }
            true
        } catch (_: RuntimeException) { busy.set(false); false }
    }
}
