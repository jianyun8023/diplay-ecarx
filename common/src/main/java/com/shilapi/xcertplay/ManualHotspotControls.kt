package com.shilapi.xcertplay

import android.app.Activity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.ManualHotspotControl

/** A one-shot user action, separate from automatic hotspot startup. Rendering never writes. */
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
        fun observeWifi(): ManualHotspotControl.State
        fun wifiCapability(): ManualHotspotControl.Capability
        fun requestWifi(enabled: Boolean, cancelled: () -> Boolean): ManualHotspotControl.Result
        fun execute(block: () -> Unit)
    }
    private class AndroidBackend(activity: Activity) : Backend {
        private val control = ManualHotspotControl(activity.applicationContext) {
            SettingsActionDiagnostics.record(activity.applicationContext,
                SettingsActionDiagnostics.Action.MANUAL_HOTSPOT, it)
        }
        override fun observe() = control.observe()
        override fun capability() = control.capability()
        override fun request(enabled: Boolean, cancelled: () -> Boolean) = control.request(enabled, cancelled)
        private val wifiControl = ManualHotspotControl(activity.applicationContext) {
            SettingsActionDiagnostics.record(activity.applicationContext,
                SettingsActionDiagnostics.Action.MANUAL_WIFI, it)
        }
        override fun observeWifi() = wifiControl.observeWifi()
        override fun wifiCapability() = wifiControl.wifiCapability()
        override fun requestWifi(enabled: Boolean, cancelled: () -> Boolean) = wifiControl.requestWifi(enabled, cancelled)
        override fun execute(block: () -> Unit) { Thread(block, "diplay-manual-hotspot").start() }
    }

    @Volatile private var closed = false
    @Volatile private var generation = 0
    @Volatile var isBusy = false
        private set
    private var root: LinearLayout? = null
    private var outcome: ManualHotspotControl.Result? = null
    private var outcomeWifi = false

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
        else outcome?.let { text(resultText(it, outcomeWifi)) }
        val capability = runCatching { backend.capability() }.getOrDefault(ManualHotspotControl.Capability.UNSUPPORTED)
        when (capability) {
            ManualHotspotControl.Capability.PERMISSION_REQUIRED -> button(R.string.settings_write_settings_allow) {
                if (!closed && !isBusy) requestPermission()
            }
            ManualHotspotControl.Capability.UNSUPPORTED -> text(R.string.settings_manual_hotspot_unsupported)
            ManualHotspotControl.Capability.AVAILABLE -> {
                val stable = state == ManualHotspotControl.State.ON || state == ManualHotspotControl.State.OFF
                button(R.string.settings_manual_hotspot_on, !isBusy && stable && state == ManualHotspotControl.State.OFF) { begin(true) }
                button(R.string.settings_manual_hotspot_off, !isBusy && stable && state == ManualHotspotControl.State.ON) { confirmOff() }
            }
        }
        val wifiState = runCatching { backend.observeWifi() }.getOrDefault(ManualHotspotControl.State.UNKNOWN)
        text(stateText(wifiState, wifi = true))
        text(R.string.settings_manual_wifi_description)
        when (runCatching { backend.wifiCapability() }.getOrDefault(ManualHotspotControl.Capability.UNSUPPORTED)) {
            ManualHotspotControl.Capability.AVAILABLE -> {
                button(R.string.settings_manual_wifi_on, !isBusy && wifiState == ManualHotspotControl.State.OFF) { begin(true, wifi = true) }
                button(R.string.settings_manual_wifi_off, !isBusy && wifiState == ManualHotspotControl.State.ON) { begin(false, wifi = true) }
            }
            ManualHotspotControl.Capability.PERMISSION_REQUIRED -> text(R.string.settings_manual_wifi_permission)
            ManualHotspotControl.Capability.UNSUPPORTED -> text(R.string.settings_manual_wifi_unsupported)
        }
        button(R.string.settings_manual_hotspot_refresh) { if (!closed && !isBusy) { outcome = null; display() } }
        button(R.string.open_car_hotspot_settings) { if (!closed && !isBusy) openSettings() }
    }

    private fun blocked(action: SettingsActionDiagnostics.Action = SettingsActionDiagnostics.Action.MANUAL_HOTSPOT): Boolean {
        if (closed || isBusy) return true
        if (!connectionBusy()) return false
        SettingsActionDiagnostics.record(activity.applicationContext,
            action, "phase=blocked connectionBusy=true")
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

    private fun begin(enabled: Boolean, wifi: Boolean = false) {
        val action = if (wifi) SettingsActionDiagnostics.Action.MANUAL_WIFI else SettingsActionDiagnostics.Action.MANUAL_HOTSPOT
        if (blocked(action)) return
        fun observe() = runCatching { if (wifi) backend.observeWifi() else backend.observe() }.getOrNull()
        SettingsActionDiagnostics.record(activity.applicationContext,
            action, "phase=begin enabled=$enabled before=${observe()}")
        val token = generation
        isBusy = true
        outcome = null
        outcomeWifi = wifi
        display()
        try { backend.execute {
            fun cancelled() = closed || token != generation || connectionBusy()
            val result = if (cancelled()) ManualHotspotControl.Result.CANCELLED else try {
                if (wifi) backend.requestWifi(enabled, ::cancelled) else backend.request(enabled, ::cancelled)
            } catch (_: Exception) { ManualHotspotControl.Result.FAILED }
            SettingsActionDiagnostics.record(activity.applicationContext,
                action, "phase=finished enabled=$enabled result=$result after=${observe()}")
            activity.runOnUiThread {
                isBusy = false
                if (!closed) {
                    if (token == generation) outcome = result
                    display()
                }
            }
        } } catch (_: Exception) {
            SettingsActionDiagnostics.record(activity.applicationContext,
                action, "phase=worker-rejected enabled=$enabled")
            isBusy = false
            if (!closed) { outcome = ManualHotspotControl.Result.FAILED; display() }
        }
    }

    /** Keep busy until the old worker exits, so resuming cannot queue a second write. */
    fun cancelPending() { generation++ }
    fun close() { closed = true; cancelPending(); root = null }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun stateText(state: ManualHotspotControl.State, wifi: Boolean = false): Int = when (state) {
        ManualHotspotControl.State.ON -> if (wifi) R.string.settings_manual_wifi_state_on else R.string.settings_manual_hotspot_state_on
        ManualHotspotControl.State.OFF -> if (wifi) R.string.settings_manual_wifi_state_off else R.string.settings_manual_hotspot_state_off
        ManualHotspotControl.State.STARTING -> if (wifi) R.string.settings_manual_wifi_state_starting else R.string.settings_manual_hotspot_state_starting
        ManualHotspotControl.State.STOPPING -> if (wifi) R.string.settings_manual_wifi_state_stopping else R.string.settings_manual_hotspot_state_stopping
        ManualHotspotControl.State.FAILED -> if (wifi) R.string.settings_manual_wifi_failed else R.string.settings_manual_hotspot_state_failed
        ManualHotspotControl.State.UNKNOWN -> if (wifi) R.string.settings_manual_wifi_state_unknown else R.string.settings_manual_hotspot_state_unknown
    }
    private fun resultText(result: ManualHotspotControl.Result, wifi: Boolean): Int = when (result) {
        ManualHotspotControl.Result.ON -> stateText(ManualHotspotControl.State.ON, wifi)
        ManualHotspotControl.Result.OFF -> stateText(ManualHotspotControl.State.OFF, wifi)
        ManualHotspotControl.Result.PERMISSION_REQUIRED -> if (wifi) R.string.settings_manual_wifi_permission else R.string.settings_manual_hotspot_permission
        ManualHotspotControl.Result.UNSUPPORTED -> if (wifi) R.string.settings_manual_wifi_unsupported else R.string.settings_manual_hotspot_unsupported
        ManualHotspotControl.Result.TIMED_OUT -> R.string.settings_manual_hotspot_timeout
        ManualHotspotControl.Result.CANCELLED -> R.string.settings_manual_hotspot_cancelled
        ManualHotspotControl.Result.BUSY -> R.string.settings_manual_hotspot_working
        ManualHotspotControl.Result.FAILED -> if (wifi) R.string.settings_manual_wifi_failed else R.string.settings_manual_hotspot_failed
    }
    private companion object {
        const val BLOCK_GAP_DP = 12
        const val CONTROL_GAP_DP = 8
    }
}
