package com.shilapi.xcertplay

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.view.View
import android.view.ViewGroup
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotProbe
import com.shilapi.xcertplay.network.CarHotspotProbe.Action
import com.shilapi.xcertplay.network.CarHotspotProbe.Result
import com.shilapi.xcertplay.network.CarHotspotProbe.Status
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h1080dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HotspotProbeControlsTest {
    private lateinit var activity: DiPlayActivity
    private lateinit var controls: HotspotProbeControls
    private lateinit var root: LinearLayout
    private var connected = false
    private var defer = false
    private var pending: (() -> Unit)? = null
    private val actions = mutableListOf<Action>()
    private val backend = object : HotspotProbeControls.Backend {
        override var canRestore = false
        override fun run(action: Action, credentials: CarHotspotProbe.Credentials?, cancelled: () -> Boolean): Result {
            if (cancelled()) return Result(Status.CANCELLED, "beforeCall=true")
            actions += action
            if (action == Action.READ) {
                canRestore = true
                return Result(Status.OBSERVED, "configRead=true", CarHotspotProbe.Credentials("Private-Car", "private-key"))
            }
            return Result(Status.VERIFIED, "state=ON")
        }
        override fun execute(action: () -> Unit) { if (defer) pending = action else action() }
    }

    @Before fun setup() {
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        controls = HotspotProbeControls(activity, { connected }, backend)
        root = controls.build()
    }

    @After fun cleanup() { controls.close(); activity.finish() }

    @Test fun readingNeverWritesOrChangesTheCarPlayCredentials() {
        val beforeName = AirPlayPersistence.loadManualHotspotSsid(activity)
        val beforeKey = AirPlayPersistence.loadManualHotspotPassphrase(activity)
        assertTrue(actions.isEmpty())
        assertFalse(button(R.string.settings_hotspot_probe_write).isEnabled)
        button(R.string.settings_hotspot_probe_read).performClick()
        assertEquals(listOf(Action.READ), actions)
        assertEquals("Private-Car", views(root).filterIsInstance<EditText>().first().text.toString())
        assertNotNull(views(root).filterIsInstance<EditText>().last().transformationMethod)
        assertEquals(beforeName, AirPlayPersistence.loadManualHotspotSsid(activity))
        assertEquals(beforeKey, AirPlayPersistence.loadManualHotspotPassphrase(activity))
        assertTrue(button(R.string.settings_hotspot_probe_write).isEnabled)
        val report = SettingsActionDiagnostics.report(activity)
        assertTrue(report.contains("HOTSPOT_PROBE"))
        assertFalse(report.contains("Private-Car"))
        assertFalse(report.contains("private-key"))
    }

    @Test fun writesAndRadioChangesRequireExplicitConfirmation() {
        button(R.string.settings_hotspot_probe_read).performClick()
        button(R.string.settings_hotspot_probe_write).performClick()
        val cancelled = ShadowAlertDialog.getLatestAlertDialog()
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(Action.READ), actions)
        button(R.string.settings_hotspot_probe_write).performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(Action.READ, Action.WRITE), actions)
        button(R.string.settings_hotspot_probe_start_oem).performClick()
        assertEquals(2, actions.size)
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Action.START_OEM, actions.last())
    }

    @Test fun activeConnectionAndCancellationPreventNewCalls() {
        connected = true
        button(R.string.settings_hotspot_probe_read).performClick()
        assertTrue(actions.isEmpty())
        connected = false
        defer = true
        button(R.string.settings_hotspot_probe_read).performClick()
        assertTrue(controls.isBusy)
        assertFalse(button(R.string.settings_hotspot_probe_inspect).isEnabled)
        controls.cancelPending()
        pending!!.invoke()
        assertTrue(actions.isEmpty())
        assertFalse(controls.isBusy)
    }

    @Test fun actualDiagnosticPanelFitsTheCurrentHeadUnit() {
        val production = HotspotProbeControls(activity, { false }, backend,
            labelFactory = { text ->
                activity.javaClass.getDeclaredMethod("label", String::class.java, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(activity, text, 15, 0xffdddddd.toInt(), false, false) as android.widget.TextView
            },
            buttonFactory = { title, click ->
                activity.javaClass.getDeclaredMethod("button", String::class.java, Boolean::class.javaPrimitiveType,
                    kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
                    .invoke(activity, title, false, click) as Button
            })
        ReflectionHelpers.setField(activity, "hotspotProbeControls", production)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "openHotspotProbe")
        val dialog = ReflectionHelpers.getField<Dialog>(activity, "hotspotProbeDialog")
        val decor = dialog.window!!.decorView
        val width = dialog.window!!.attributes.width
        val height = dialog.window!!.attributes.height
        assertTrue(width <= 1920 && height <= 1080)
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, width, height)
        assertTrue(actions.isEmpty())
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(DiPlayPalette.DARK.surface)
        canvas.translate(((1920 - width) / 2).toFloat(), ((1080 - height) / 2).toFloat())
        decor.draw(canvas)
        val file = File("build/reports/hotspot-probe-ui/current-head-unit.png").apply { parentFile.mkdirs() }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        assertTrue(views(decor).filterIsInstance<Button>().any { it.text == activity.getString(R.string.close) && it.height > 0 })
        dialog.dismiss()
        production.close()
    }

    private fun button(title: Int) = views(root).filterIsInstance<Button>().single { it.text == activity.getString(title) }
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
