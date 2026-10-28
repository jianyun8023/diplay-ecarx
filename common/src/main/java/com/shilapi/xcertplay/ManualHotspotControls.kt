package com.shilapi.xcertplay

import android.app.Activity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.network.ManualHotspotControl

/** Manual controls use the same startup and recovery as wireless CarPlay. Rendering never writes. */
internal class ManualHotspotControls(
    private val activity: Activity,
    private val connectionBusy: () -> Boolean,
    private val openSettings: () -> Unit,
    private val requestPermission: () -> Unit,
    private val backend: Backend = AndroidBackend(activity),
    private val labelFactory: (String) -> TextView = { TextView(activity).apply { text = it; textSize = 15f } },
    private val buttonFactory: (String, () -> Unit) -> Button = { title, click ->
        Button(activity).apply { text = title; setOnClickListener { click() } } },
) {
    interface Backend {
        fun observe(): ManualHotspotControl.State
        fun capability(): ManualHotspotControl.Capability
        fun request(enabled: Boolean, cancelled: () -> Boolean): ManualHotspotControl.Result
        fun execute(block: () -> Unit)
    }
    private class AndroidBackend(private val activity: Activity) : Backend {
        private val control = ManualHotspotControl(activity.applicationContext) {
            SettingsActionDiagnostics.record(activity, SettingsActionDiagnostics.Action.MANUAL_HOTSPOT, it)
        }
        override fun observe() = control.observe()
        override fun capability() = control.capability()
        override fun request(enabled: Boolean, cancelled: () -> Boolean): ManualHotspotControl.Result {
            if (!enabled) return control.request(false, cancelled)
            return when (CarHotspotTethering.enable(activity.applicationContext,
                cancelled, timeoutMillis = 8_000L,
                log = { SettingsActionDiagnostics.record(activity, SettingsActionDiagnostics.Action.MANUAL_HOTSPOT, it) })) {
                CarHotspotTethering.Result.READY -> ManualHotspotControl.Result.ON
                CarHotspotTethering.Result.PERMISSION_REQUIRED -> ManualHotspotControl.Result.PERMISSION_REQUIRED
                CarHotspotTethering.Result.UNSUPPORTED -> ManualHotspotControl.Result.UNSUPPORTED
                CarHotspotTethering.Result.TIMED_OUT -> ManualHotspotControl.Result.TIMED_OUT
                CarHotspotTethering.Result.CANCELLED -> ManualHotspotControl.Result.CANCELLED
                CarHotspotTethering.Result.FAILED -> ManualHotspotControl.Result.FAILED
            }
        }
        override fun execute(block: () -> Unit) { Thread(block, "diplay-manual-hotspot").start() }
    }

    @Volatile private var closed = false
    @Volatile private var generation = 0
    @Volatile var isBusy = false
        private set
    private var root: LinearLayout? = null
    private var outcome: ManualHotspotControl.Result? = null

    fun build(): LinearLayout = LinearLayout(activity).also {
        it.orientation = LinearLayout.VERTICAL
        root = it
        display()
    }

    private fun display() {
        val view = root ?: return
        view.removeAllViews()
        fun text(id: Int) = view.addView(labelFactory(activity.getString(id)),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(BLOCK_GAP_DP) })
        fun button(id: Int, enabled: Boolean = !isBusy, action: () -> Unit) =
            view.addView(buttonFactory(activity.getString(id), action).apply { isEnabled = enabled },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(CONTROL_GAP_DP) })
        text(R.string.settings_manual_hotspot_title)
        text(R.string.settings_manual_hotspot_description)
        val state = runCatching { backend.observe() }.getOrDefault(ManualHotspotControl.State.UNKNOWN)
        text(stateText(state))
        if (isBusy) text(R.string.settings_manual_hotspot_working)
        else outcome?.let { text(resultText(it)) }
        val capability = runCatching { backend.capability() }.getOrDefault(ManualHotspotControl.Capability.UNSUPPORTED)
        when (capability) {
            ManualHotspotControl.Capability.PERMISSION_REQUIRED -> button(R.string.settings_write_settings_allow) {
                if (!closed && !isBusy) requestPermission()
            }
            ManualHotspotControl.Capability.UNSUPPORTED -> text(R.string.settings_manual_hotspot_unsupported)
            ManualHotspotControl.Capability.AVAILABLE -> {
                val stable = state == ManualHotspotControl.State.ON || state == ManualHotspotControl.State.OFF
                val failed = state == ManualHotspotControl.State.FAILED
                button(if (failed) R.string.settings_manual_hotspot_recover else R.string.settings_manual_hotspot_on,
                    !isBusy && (state == ManualHotspotControl.State.OFF || failed)) {
                    if (!failed) begin(true) else if (!blocked()) {
                        activity.appDialogBuilder().setTitle(R.string.settings_manual_hotspot_recover)
                            .setMessage(R.string.settings_manual_hotspot_confirm_recover)
                            .setPositiveButton(R.string.settings_manual_hotspot_recover) { _, _ -> begin(true) }
                            .setNegativeButton(R.string.cancel, null).show()
                    }
                }
                button(R.string.settings_manual_hotspot_off, !isBusy && stable && state == ManualHotspotControl.State.ON) { confirmOff() }
            }
        }
        button(R.string.settings_manual_hotspot_refresh) { if (!closed && !isBusy) { outcome = null; display() } }
        button(R.string.open_car_hotspot_settings) { if (!closed && !isBusy) openSettings() }
    }

    private fun blocked(): Boolean {
        if (closed || isBusy) return true
        if (!connectionBusy()) return false
        SettingsActionDiagnostics.record(activity, SettingsActionDiagnostics.Action.MANUAL_HOTSPOT, "phase=blocked connectionBusy=true")
        activity.appDialogBuilder().setMessage(R.string.settings_manual_hotspot_disconnect)
            .setPositiveButton(android.R.string.ok, null).show()
        return true
    }

    private fun confirmOff() {
        if (blocked()) return
        activity.appDialogBuilder().setTitle(R.string.settings_manual_hotspot_off)
            .setMessage(R.string.settings_manual_hotspot_confirm_off)
            .setPositiveButton(R.string.settings_manual_hotspot_off) { _, _ -> begin(false) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun begin(enabled: Boolean) {
        if (blocked()) return
        val action = if (enabled && backend.observe() == ManualHotspotControl.State.FAILED)
            SettingsActionDiagnostics.Action.HOTSPOT_RECOVERY else SettingsActionDiagnostics.Action.MANUAL_HOTSPOT
        SettingsActionDiagnostics.record(activity, action, "phase=begin enabled=$enabled before=${backend.observe()}")
        val token = generation
        isBusy = true
        outcome = null
        display()
        try { backend.execute {
            fun cancelled() = closed || token != generation || connectionBusy()
            val result = if (cancelled()) ManualHotspotControl.Result.CANCELLED else try {
                backend.request(enabled, ::cancelled)
            } catch (_: Exception) { ManualHotspotControl.Result.FAILED }
            SettingsActionDiagnostics.record(activity, action,
                "phase=finished enabled=$enabled result=$result after=${backend.observe()}")
            activity.runOnUiThread {
                isBusy = false
                if (!closed) {
                    if (token == generation) outcome = result
                    display()
                }
            }
        } } catch (_: Exception) {
            SettingsActionDiagnostics.record(activity, action, "phase=worker-rejected enabled=$enabled")
            isBusy = false
            if (!closed) { outcome = ManualHotspotControl.Result.FAILED; display() }
        }
    }

    /** Keep busy until the old worker exits, so resuming cannot queue a second write. */
    fun cancelPending() { generation++ }
    fun close() { closed = true; cancelPending(); root = null }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun stateText(state: ManualHotspotControl.State): Int = when (state) {
        ManualHotspotControl.State.ON -> R.string.settings_manual_hotspot_state_on
        ManualHotspotControl.State.OFF -> R.string.settings_manual_hotspot_state_off
        ManualHotspotControl.State.STARTING -> R.string.settings_manual_hotspot_state_starting
        ManualHotspotControl.State.STOPPING -> R.string.settings_manual_hotspot_state_stopping
        ManualHotspotControl.State.FAILED -> R.string.settings_manual_hotspot_state_failed
        ManualHotspotControl.State.UNKNOWN -> R.string.settings_manual_hotspot_state_unknown
    }
    private fun resultText(result: ManualHotspotControl.Result): Int = when (result) {
        ManualHotspotControl.Result.ON -> R.string.settings_manual_hotspot_state_on
        ManualHotspotControl.Result.OFF -> R.string.settings_manual_hotspot_state_off
        ManualHotspotControl.Result.PERMISSION_REQUIRED -> R.string.settings_manual_hotspot_permission
        ManualHotspotControl.Result.UNSUPPORTED -> R.string.settings_manual_hotspot_unsupported
        ManualHotspotControl.Result.TIMED_OUT -> R.string.settings_manual_hotspot_timeout
        ManualHotspotControl.Result.CANCELLED -> R.string.settings_manual_hotspot_cancelled
        ManualHotspotControl.Result.BUSY -> R.string.settings_manual_hotspot_working
        ManualHotspotControl.Result.FAILED -> R.string.settings_manual_hotspot_failed
    }
    private companion object {
        const val BLOCK_GAP_DP = 12
        const val CONTROL_GAP_DP = 8
    }
}
