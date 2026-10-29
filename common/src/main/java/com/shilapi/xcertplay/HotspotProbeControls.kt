package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Rect
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotProbe
import com.shilapi.xcertplay.network.CarHotspotProbe.Action
import com.shilapi.xcertplay.network.CarHotspotProbe.Result
import com.shilapi.xcertplay.network.CarHotspotProbe.Status

/** An explicit diagnostic session; rendering and reading never modify connection preferences. */
internal class HotspotProbeControls(
    private val activity: Activity,
    private val connectionBusy: () -> Boolean,
    private val backend: Backend = AndroidBackend(activity),
    private val labelFactory: (String) -> TextView = { TextView(activity).apply { text = it; textSize = 15f } },
    private val buttonFactory: (String, () -> Unit) -> Button = { title, click ->
        Button(activity).apply { text = title; setOnClickListener { click() } } },
) {
    interface Backend {
        val canRestore: Boolean
        fun run(action: Action, credentials: CarHotspotProbe.Credentials?, cancelled: () -> Boolean): Result
        fun execute(action: () -> Unit)
    }
    private class AndroidBackend(activity: Activity) : Backend {
        private val probe = CarHotspotProbe(activity.applicationContext)
        override val canRestore get() = probe.canRestore
        override fun run(action: Action, credentials: CarHotspotProbe.Credentials?, cancelled: () -> Boolean) =
            probe.run(action, credentials, cancelled)
        override fun execute(action: () -> Unit) { Thread(action, "diplay-hotspot-probe").start() }
    }

    @Volatile private var generation = 0
    @Volatile private var closed = false
    @Volatile var isBusy = false
        private set
    private var name: EditText? = null
    private var key: EditText? = null
    private var output: TextView? = null
    private var latest = ""
    private val buttons = mutableListOf<Pair<Action, Button>>()

    fun build(): LinearLayout {
        buttons.clear()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            fun attach(view: View) = addView(view, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(CONTROL_GAP_DP)
            })
            attach(labelFactory(activity.getString(R.string.settings_hotspot_probe_description)))
            output = labelFactory(latest.ifEmpty { activity.getString(R.string.settings_hotspot_probe_not_run) }).apply {
                setTextIsSelectable(true)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }.also(::attach)
            for (action in listOf(Action.INSPECT, Action.READ)) attach(actionButton(action))
            name = EditText(activity).apply {
                hint = activity.getString(R.string.hotspot_name)
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT
            }.also(::attach)
            key = EditText(activity).apply {
                hint = activity.getString(R.string.hotspot_password)
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }.also(::attach)
            attach(CheckBox(activity).apply {
                text = activity.getString(R.string.show_password)
                setOnCheckedChangeListener { _, checked ->
                    key?.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                }
            })
            for (action in listOf(Action.WRITE, Action.RESTORE, Action.START_PLATFORM, Action.STOP_PLATFORM, Action.START_OEM, Action.STOP_OEM)) {
                attach(actionButton(action))
            }
            updateEnabled()
        }
    }

    private fun actionButton(action: Action): Button = buttonFactory(activity.getString(title(action))) {
        if (!closed && !isBusy) {
            if (connectionBusy()) {
                showResult(activity.getString(R.string.settings_manual_hotspot_disconnect))
            } else if (action == Action.INSPECT || action == Action.READ) begin(action)
            else activity.appDialogBuilder().setTitle(title(action))
                .setMessage(if (action == Action.WRITE || action == Action.RESTORE)
                    R.string.settings_hotspot_probe_confirm_write else R.string.settings_hotspot_probe_confirm_radio)
                .setPositiveButton(title(action)) { _, _ -> begin(action) }
                .setNegativeButton(R.string.cancel, null).show()
        }
    }.also { buttons += action to it }

    private fun begin(action: Action) {
        if (closed || isBusy || connectionBusy()) return
        val token = generation
        val credentials = if (action == Action.WRITE) CarHotspotProbe.Credentials(name?.text.toString(), key?.text.toString()) else null
        isBusy = true
        updateEnabled()
        output?.text = activity.getString(R.string.settings_manual_hotspot_working)
        SettingsActionDiagnostics.record(activity, SettingsActionDiagnostics.Action.HOTSPOT_PROBE, "phase=begin operation=$action")
        backend.execute {
            val result = backend.run(action, credentials) { closed || token != generation || connectionBusy() }
            SettingsActionDiagnostics.record(activity, SettingsActionDiagnostics.Action.HOTSPOT_PROBE,
                "phase=finished outcome=${result.status} ${result.evidence}")
            val read = result.credentials
            if (read != null) {
                SettingsActionDiagnostics.record(activity, SettingsActionDiagnostics.Action.HOTSPOT_PROBE,
                    "nameMatchesSaved=${read.name == AirPlayPersistence.loadManualHotspotSsid(activity)} " +
                        "keyMatchesSaved=${read.key == AirPlayPersistence.loadManualHotspotPassphrase(activity)}")
            }
            activity.runOnUiThread {
                isBusy = false
                if (!closed && token == generation) {
                    showResult(activity.getString(status(result.status)) + "\n" + result.evidence)
                    if (read != null) {
                        name?.setText(read.name)
                        key?.setText(read.key)
                    }
                }
                if (!closed) updateEnabled()
            }
        }
    }

    private fun showResult(value: String) {
        latest = value
        output?.apply {
            text = value
            requestRectangleOnScreen(Rect(0, 0, width, height), true)
        }
    }

    private fun updateEnabled() {
        name?.isEnabled = !isBusy && backend.canRestore
        key?.isEnabled = !isBusy && backend.canRestore
        for ((action, button) in buttons) button.isEnabled = !isBusy &&
            ((action != Action.WRITE && action != Action.RESTORE) || backend.canRestore)
    }

    fun cancelPending() { generation++ }
    fun close() { closed = true; cancelPending(); name = null; key = null; output = null; buttons.clear() }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun title(action: Action): Int = when (action) {
        Action.INSPECT -> R.string.settings_hotspot_probe_inspect
        Action.READ -> R.string.settings_hotspot_probe_read
        Action.WRITE -> R.string.settings_hotspot_probe_write
        Action.RESTORE -> R.string.settings_hotspot_probe_restore
        Action.START_PLATFORM -> R.string.settings_hotspot_probe_start_platform
        Action.STOP_PLATFORM -> R.string.settings_hotspot_probe_stop_platform
        Action.START_OEM -> R.string.settings_hotspot_probe_start_oem
        Action.STOP_OEM -> R.string.settings_hotspot_probe_stop_oem
    }
    private fun status(value: Status): Int = when (value) {
        Status.OBSERVED -> R.string.settings_hotspot_probe_observed
        Status.VERIFIED -> R.string.settings_hotspot_probe_verified
        Status.REJECTED -> R.string.settings_hotspot_probe_rejected
        Status.DENIED -> R.string.settings_hotspot_probe_denied
        Status.UNSUPPORTED -> R.string.settings_hotspot_probe_unsupported
        Status.FAILED -> R.string.settings_manual_hotspot_failed
        Status.TIMED_OUT -> R.string.settings_manual_hotspot_timeout
        Status.CANCELLED -> R.string.settings_manual_hotspot_cancelled
        Status.BUSY -> R.string.settings_manual_hotspot_working
    }
    private companion object { const val CONTROL_GAP_DP = 8 }
}
