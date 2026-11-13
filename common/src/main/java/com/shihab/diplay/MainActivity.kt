package com.shihab.diplay

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shihab.diplay.mfi.MfiProtocolMajorResult
import com.shihab.diplay.mfi.MfiSelfCheck
import com.shihab.diplay.mfi.MfiSelfCheckResult
import com.shihab.diplay.transport.LinuxI2cTransport
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Board I2C diagnostic page, plain views like every other screen. */
class MainActivity : ComponentActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var status: DiagnosticStatus = DiagnosticStatus.Idle
    private var appNight = true
    private var stopObservingAppearance: (() -> Unit)? = null
    private lateinit var root: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var devicePathInput: EditText
    private lateinit var runButton: Button
    private lateinit var statusText: TextView
    private lateinit var noteText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appNight = resolveAppNightNow()
        enableEdgeToEdge()
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        if (resolveAppNightNow() != appNight) {
            appNight = resolveAppNightNow()
            applyPalette()
        }
        stopObservingAppearance?.invoke()
        stopObservingAppearance = AppAppearanceRuntime.observeHost {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    val next = resolveAppNightNow()
                    if (next != appNight) {
                        appNight = next
                        applyPalette()
                    }
                }
            }
        }
    }

    override fun onPause() {
        stopObservingAppearance?.invoke()
        stopObservingAppearance = null
        super.onPause()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildContent(): LinearLayout {
        val padding = dp(24)
        val gap = dp(GAP_DP)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        titleText = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f) }
        root.addView(titleText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        devicePathInput = EditText(this).apply {
            setSingleLine()
            hint = "Linux I2C device"
            setText("/dev/i2c-1")
        }
        root.addView(devicePathInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
        runButton = Button(this).apply {
            text = "Run MFi self-check"
            setOnClickListener { runSelfCheck(devicePathInput.text.toString().trim()) }
        }
        root.addView(runButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
        statusText = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }
        root.addView(statusText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
        noteText = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
        root.addView(noteText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
        titleText.text = "Board I2C diagnostic"
        noteText.text = "CH341 requires deployment-specific VID/PID configuration."
        renderStatus(status)
        applyPalette()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft, view.paddingTop + bars.top,
                view.paddingRight, view.paddingBottom + bars.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }
        return root
    }

    private fun applyPalette() {
        val palette = DiPlayPalette.of(appNight)
        window.setBackgroundDrawable(ColorDrawable(palette.background))
        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar
        root.setBackgroundColor(palette.background)
        titleText.setTextColor(palette.primaryText)
        devicePathInput.setTextColor(palette.primaryText)
        devicePathInput.setHintTextColor(palette.secondaryText)
        statusText.setTextColor(palette.secondaryText)
        noteText.setTextColor(palette.secondaryText)
        runButton.setTextColor(palette.onAccent)
        runButton.background.mutate().setTint(palette.button)
    }

    private fun runSelfCheck(devicePath: String) {
        if (devicePath.isEmpty()) return
        renderStatus(DiagnosticStatus.Running)
        executor.execute {
            val next = try {
                LinuxI2cTransport.open(devicePath).use { MfiSelfCheck(it).run() }
                    .let { DiagnosticStatus.Result(it) }
            } catch (error: LinkageError) {
                DiagnosticStatus.Failure(error.message ?: "I2C native library is unavailable")
            } catch (error: Exception) {
                DiagnosticStatus.Failure(error.message ?: error.javaClass.simpleName)
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) renderStatus(next)
            }
        }
    }

    private fun renderStatus(next: DiagnosticStatus) {
        status = next
        statusText.text = next.message()
        runButton.isEnabled = next !is DiagnosticStatus.Running
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val GAP_DP = 12
    }
}

private sealed class DiagnosticStatus {
    data object Idle : DiagnosticStatus()
    data object Running : DiagnosticStatus()
    data class Result(val selfCheck: MfiSelfCheckResult) : DiagnosticStatus()
    data class Failure(val message: String) : DiagnosticStatus()

    fun message(): String = when (this) {
        Idle -> "Idle"
        Running -> "Running…"
        is Failure -> "Failed: $message"
        is Result -> {
            val chip = selfCheck.chip ?: return if (selfCheck.discovery.interrupted) {
                "MFi scan interrupted"
            } else {
                "Found: none"
            }
            val major = when (val result = chip.protocolMajor) {
                is MfiProtocolMajorResult.Value -> "%d".format(result.major)
                is MfiProtocolMajorResult.MfiFailure -> result.error.message ?: result.error.javaClass.simpleName
                is MfiProtocolMajorResult.TransportFailure -> result.error.message ?: result.error.javaClass.simpleName
            }
            "Found: 0x%02X; device version: 0x%02X; protocol major (raw): %s".format(
                chip.address7Bit,
                chip.deviceVersion,
                major,
            )
        }
    }
}
