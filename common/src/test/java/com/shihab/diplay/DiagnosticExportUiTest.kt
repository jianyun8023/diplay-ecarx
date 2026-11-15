package com.shihab.diplay

import android.content.ActivityNotFoundException
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityOptionsCompat
import com.shihab.diplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-w1920dp-h1080dp-land-mdpi", shadows = [FileProviderPathTestShadow::class])
class DiagnosticExportUiTest {
    @Test fun exportKeepsCurrentAndLegacyIdentitySeparateWithCaptureHealth() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val context = controller.get().applicationContext
        val folder = File(context.filesDir, "logs").apply { mkdirs() }
        val legacy = File(folder, "previous.log")
        legacy.writeText("DiPlay log started 2026-11-14 13:33:05.000 pid=21672\n13:33:10.000  old connection timeout\n")
        val current = File(folder, "diplay.log")
        current.writeText("DiPlay log started 2026-11-15 14:17:46.233 pid=7042 version=0.2-v151 revision=c16beb57\n14:20:00.000  current connected\n")
        try {
            val task = FutureTask { diagnosticReportText(context, setupReady = false) }
            Thread(task).start()
            val report = task.get(5, TimeUnit.SECONDS)
            assertTrue(report.indexOf("--- diplay.log ---") < report.indexOf("--- previous.log ---"))
            val history = report.substringAfter("--- previous.log ---")
            assertTrue(history.contains("scope=history"))
            assertTrue(history.contains("version=unknown revision=unknown (legacy file; report version does not apply)"))
            assertTrue(report.contains("version=0.2-v151 revision=c16beb57"))
            assertTrue(report.contains("old connection timeout"))
            assertTrue(report.contains("Log capture observedAtEpochMs="))
            assertTrue(report.contains("Log export finishedAtEpochMs="))
        } finally {
            current.delete()
            legacy.delete()
            controller.pause().stop().destroy()
        }
    }

    @Test fun missingPickerSavesAReportAndProvidesSelectableTextInsideDiPlay() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val context = activity.applicationContext
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "ecarx.naviservice"
            applicationInfo = ApplicationInfo().apply { packageName = "ecarx.naviservice"; enabled = true }
        })
        val authority = "${context.packageName}.diagnostic-reports"
        val info = context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        ShadowContentResolver.registerProviderInternal(authority, DiagnosticReportProvider().apply { attachInfo(context, info) })
        val missingPicker = object : ActivityResultLauncher<String>() {
            override fun launch(input: String, options: ActivityOptionsCompat?) {
                throw ActivityNotFoundException("No DocumentsUI")
            }
            override fun unregister() = Unit
            override fun getContract() = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
        }
        ReflectionHelpers.setField(activity, "export", missingPicker)
        SettingsActionDiagnostics.record(context, SettingsActionDiagnostics.Action.MANUAL_HOTSPOT,
            "phase=finished enabled=true result=TIMED_OUT after=OFF")
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "chooseReportDestination")
            val deadline = System.nanoTime() + 5_000_000_000L
            while (ShadowAlertDialog.getLatestAlertDialog() == null && System.nanoTime() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            val saved = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            val reports = File(context.getExternalFilesDir(null)!!, "diagnostic-reports")
            val file = reports.listFiles()!!.single()
            assertTrue(file.name.endsWith(".txt"))
            assertTrue(descendants(saved.window!!.decorView).filterIsInstance<TextView>()
                .any { it.text.contains(file.absolutePath) })
            assertTrue(file.readText().contains("Android 9 / API 28"))
            val report = file.readText()
            assertTrue(report.contains("Report createdAtEpochMs="))
            assertTrue(report.contains("Media integration savedMode="))
            assertTrue(report.contains("Wheel Siri enabled="))
            assertTrue(report.contains("Android media session:"))
            assertTrue(report.contains("CarPlay media dispatch since process start:"))
            assertTrue(report.contains("Cluster artwork:"))
            assertTrue(report.contains("Navigation MQTT status="))
            assertTrue(report.contains("ihu/info/phoneprojection count="))
            assertTrue(report.contains("ihu/heartbeat/navigation count="))
            assertTrue(report.contains("Factory HiCar:"))
            assertTrue(report.contains("Platform signature check="))
            assertTrue(report.contains("USB devices:"))
            assertTrue(report.contains("USB setup permissions:"))
            assertTrue(report.contains("Permission BLUETOOTH_PRIVILEGED="))
            assertTrue(report.contains("Bluetooth settings:"))
            assertTrue(report.contains("Manual hotspot: capability="))
            assertTrue(report.contains("Firmware: product="))
            assertTrue(report.contains("action=MANUAL_HOTSPOT phase=finished enabled=true result=TIMED_OUT after=OFF"))
            saved.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val viewer = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(descendants(viewer.window!!.decorView).filterIsInstance<TextView>()
                .any { it.isTextSelectable && it.text.contains("Android 9 / API 28") })
            viewer.dismiss()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
