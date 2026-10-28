package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.EcarxHicarControl
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w1920dp-h1080dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FactoryHicarSettingsTest {
    private lateinit var activity: DiPlayActivity
    private val application get() = RuntimeEnvironment.getApplication()
    private val packageName = EcarxHicarControl.PACKAGE_NAME

    @Before fun setup() {
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        shadowOf(activity.packageManager).installPackage(PackageInfo().apply {
            packageName = this@FactoryHicarSettingsTest.packageName
            applicationInfo = ApplicationInfo().apply { enabled = true }
        })
        shadowOf(application).grantPermissions(EcarxHicarControl.PERMISSION)
        ReflectionHelpers.setField(activity, "page", "settings")
        ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.VEHICLE)
        ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.WHEEL_KEYS))
    }

    @After fun cleanup() {
        shadowOf(activity.packageManager).removePackage(packageName)
        shadowOf(application).denyPermissions(EcarxHicarControl.PERMISSION)
        activity.finish()
    }

    private fun settings(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        activity.javaClass.getDeclaredMethod("allSettingsSections", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, this)
    }

    private fun control(root: View): Switch = views(root).filterIsInstance<Switch>().single {
        it.contentDescription == activity.getString(R.string.settings_disable_factory_hicar)
    }

    @Test fun realVehicleSwitchDisablesAndRestoresThePackage() {
        assertTrue(SettingsSection.WHEEL_KEYS in SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.VEHICLE))
        val initial = control(settings())
        assertFalse(initial.isChecked)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            activity.packageManager.getApplicationEnabledSetting(packageName))
        (initial.parent as View).performClick()
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            activity.packageManager.getApplicationEnabledSetting(packageName))
        val disabled = control(settings())
        assertTrue(disabled.isChecked)
        (disabled.parent as View).performClick()
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            activity.packageManager.getApplicationEnabledSetting(packageName))
        assertFalse(control(settings()).isChecked)
        val report = SettingsActionDiagnostics.report(activity)
        assertTrue(report.contains("phase=begin targetDisabled=true before=false canChange=true"))
        assertTrue(report.contains("phase=finished targetDisabled=true applied=true after=true"))
        assertTrue(report.contains("phase=finished targetDisabled=false applied=true after=false"))
    }

    @Test fun externalPackageChangesAreReadWhenSettingsReopens() {
        for (state in listOf(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)) {
            activity.packageManager.setApplicationEnabledSetting(packageName, state, 0)
            assertTrue(control(settings()).isChecked)
        }
        activity.packageManager.setApplicationEnabledSetting(packageName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0)
        assertFalse(control(settings()).isChecked)
    }

    @Test fun missingPermissionShowsActualStateButCannotChangeIt() {
        activity.packageManager.setApplicationEnabledSetting(packageName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
        shadowOf(application).denyPermissions(EcarxHicarControl.PERMISSION)
        val root = settings()
        val control = control(root)
        assertTrue(control.isChecked)
        assertFalse(control.isEnabled)
        (control.parent as View).performClick()
        assertFalse(EcarxHicarControl.setDisabled(activity, false))
        assertTrue(EcarxHicarControl.state(activity)!!.disabled)
        assertTrue(views(root).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.settings_factory_hicar_permission_required)
        })
    }

    @Test fun devicesWithoutFactoryHicarHaveNoSwitch() {
        shadowOf(activity.packageManager).removePackage(packageName)
        assertNull(EcarxHicarControl.state(activity))
        assertFalse(EcarxHicarControl.setDisabled(activity, true))
        assertFalse(views(settings()).filterIsInstance<Switch>().any {
            it.contentDescription == activity.getString(R.string.settings_disable_factory_hicar)
        })
    }

    @Test fun rejectedAndUnappliedWritesAreNotReportedAsSuccess() {
        val manager = mock(PackageManager::class.java)
        `when`(manager.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS))
            .thenReturn(ApplicationInfo().apply { enabled = true })
        val context = object : ContextWrapper(activity) {
            override fun getPackageManager() = manager
        }
        doThrow(SecurityException("ROM rejected state change")).`when`(manager)
            .setApplicationEnabledSetting(packageName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
        val failures = mutableListOf<String>()
        assertFalse(EcarxHicarControl.setDisabled(context, true, failures::add))
        assertEquals(listOf("SecurityException"), failures)
        doNothing().`when`(manager)
            .setApplicationEnabledSetting(packageName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
        assertFalse(EcarxHicarControl.setDisabled(context, true))
        assertFalse(EcarxHicarControl.state(context)!!.disabled)
    }

    @Test fun currentHeadUnitScreenFitsHicarControl() {
        val root = settings().apply { setBackgroundColor(DiPlayPalette.DARK.surface) }
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 1080)
        val row = control(root).parent as View
        val bounds = Rect(0, 0, row.width, row.height)
        root.offsetDescendantRectToMyCoords(row, bounds)
        assertTrue("HiCar control must fit the 1920×1080 head-unit screen",
            Rect(0, 0, 1920, 1080).contains(bounds))
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            val file = File("build/reports/factory-hicar-ui/current-head-unit.png").apply { parentFile.mkdirs() }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
