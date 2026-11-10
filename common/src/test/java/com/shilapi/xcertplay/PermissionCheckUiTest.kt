package com.shilapi.xcertplay

import android.Manifest
import android.os.Looper
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.Assert.*
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
class PermissionCheckUiTest {
    @Test fun diagnosticsShowsLivePermissionsAndRefreshesWithoutGrantingThem() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val packages = shadowOf(activity.packageManager)
        val required = listOf(Manifest.permission.WRITE_SECURE_SETTINGS, "android.permission.MANAGE_APP_OPS_MODES",
            "android.permission.MANAGE_USB", "android.permission.CONTROL_VPN", "android.permission.CONNECTIVITY_INTERNAL")
        val info = activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().toList().containsAll(required))
        @Suppress("DEPRECATION") // PermissionInfo fixtures need the constructor available on API 28.
        for (name in required) packages.addPermissionInfo(PermissionInfo().apply { this.name = name; packageName = "android" })
        shadowOf(activity.application).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            val settings = LinearLayout(activity)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "diagnosticsSettings",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, settings))
            val entry = views(settings).filterIsInstance<TextView>()
                .single { it.text.toString() == activity.getString(R.string.settings_permission_check) }
            entry.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val decor = requireNotNull(dialog.window).decorView
            fun permissionStatus(name: String): String {
                val row = views(decor).filterIsInstance<TextView>().single { it.text.toString() == name }.parent as LinearLayout
                return (row.getChildAt(1) as TextView).text.toString()
            }
            assertEquals(activity.getString(R.string.settings_permission_denied), permissionStatus("WRITE_SECURE_SETTINGS"))
            for (name in required) assertTrue(views(decor).filterIsInstance<TextView>()
                .any { it.text.toString() == name.removePrefix("android.permission.") })
            assertEquals(PackageManager.PERMISSION_DENIED, activity.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS))
            decor.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 1920, 1080)
            val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            try {
                decor.draw(Canvas(bitmap))
                val output = File("build/reports/permission-check-ui/current-head-unit.png")
                requireNotNull(output.parentFile).mkdirs()
                output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            shadowOf(activity.application).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
            assertTrue(dialog.isShowing)
            assertEquals(activity.getString(R.string.settings_permission_granted), permissionStatus("WRITE_SECURE_SETTINGS"))
            dialog.dismiss()
        } finally { activity.finish() }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
