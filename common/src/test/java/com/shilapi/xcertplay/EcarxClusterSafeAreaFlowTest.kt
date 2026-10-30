package com.shilapi.xcertplay

import android.app.Dialog
import android.content.pm.PackageInfo
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Settings button -> real editor -> stored mapping -> host config -> phone's type-111 declaration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1920dp-h1080dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class EcarxClusterSafeAreaFlowTest {
    private lateinit var settings: DiPlayActivity
    private val hosts = mutableListOf<CarPlayHostActivity>()
    private val original = SafeAreaRect(300, 100, 1500, 620)
    private val defaultInsets = AirPlayInsets(top = 115, bottom = 180, left = 672, right = 691)

    @Before fun setUp() {
        settings = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        settings.setTheme(android.R.style.Theme_Material_NoActionBar)
        settings.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        shadowOf(settings.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.ecarx.xui.service"
        })
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
    }

    @After fun tearDown() {
        ReflectionHelpers.getField<Dialog?>(settings, "clusterSafeAreaDialog")?.dismiss()
        (ReflectionHelpers.getField<Handler>(settings, "handler")).removeCallbacksAndMessages(null)
        hosts.forEach { host ->
            ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
            ReflectionHelpers.getField<ExecutorService>(host, "teardownExecutor").shutdownNow()
            ReflectionHelpers.getField<ExecutorService>(host, "airPlayCommandExecutor").shutdownNow()
        }
        shadowOf(settings.packageManager).removePackage("com.ecarx.xui.service")
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
        settings.finish()
    }

    @Test fun navigationCardOpensThePhysicalDefaultEditorWithoutChangingPreferences() {
        val entry = entry()
        assertTrue(entry.text.toString().endsWith(settings.getString(R.string.settings_ecarx_cluster_safe_area_default)))
        entry.performClick()
        assertTrue(dialog().isShowing)
        assertEquals(SafeAreaRect(672, 115, 1229, 540), editor().currentRectForSource())
        assertNull(AirPlayPersistence.loadClusterSafeAreaRect(settings))
        assertEquals(defaultInsets, config().cluster!!.safeArea)
    }

    @Test fun mirrorSettingWaitsForReconnectAndRemovesTheIndependentDisplayDeclaration() {
        val owner = backgroundSession()
        val current = config()
        assertFalse(AirPlayPersistence.loadClusterMirrorMain(settings))
        val toggle = views(navigationSettings()).filterIsInstance<Switch>().single {
            it.contentDescription.toString() == settings.getString(R.string.settings_ecarx_cluster_mirror_main)
        }
        toggle.isChecked = true
        assertTrue(AirPlayPersistence.loadClusterMirrorMain(settings))
        assertTrue(PendingReconnect.isPending(owner))
        assertNotNull(current.cluster)
        verifyNoInteractions(owner)
        val next = config()
        assertNull(next.cluster)
        val displays = AirPlayInfoPlist.build(next)["displays"] as List<*>
        assertEquals(listOf(110), displays.map { (it as Map<*, *>)["type"] })
        toggle.isChecked = false
        assertNotNull(config().cluster)
    }

    @Test fun draggingThenCancellingKeepsTheSavedMappingAndSessionUntouched() {
        AirPlayPersistence.saveClusterSafeAreaRect(settings, original)
        val owner = backgroundSession()
        entry().performClick()
        dragLeft(editor(), from = 150f, to = 100f)
        assertEquals(SafeAreaRect(200, 100, 1500, 620), editor().currentRectForSource())
        assertEquals(original, AirPlayPersistence.loadClusterSafeAreaRect(settings))

        action(R.string.cancel).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(original, AirPlayPersistence.loadClusterSafeAreaRect(settings))
        assertFalse(PendingReconnect.isPending(owner))
        assertNull(ReflectionHelpers.getField<Dialog?>(settings, "clusterSafeAreaDialog"))
        assertSame(owner, CarPlayBackgroundSession.snapshot()!!.controller)
        verifyNoInteractions(owner)
    }

    @Test fun savingMarksReconnectAndANewHostAdvertisesTheEditedRectangleOnType111() {
        AirPlayPersistence.saveClusterSafeAreaRect(settings, original)
        val owner = backgroundSession()
        val currentSessionConfig = config()
        val entry = entry()
        entry.performClick()
        dragLeft(editor(), from = 150f, to = 100f)
        action(R.string.save).performClick()

        assertEquals(SafeAreaRect(200, 100, 1500, 620), AirPlayPersistence.loadClusterSafeAreaRect(settings))
        assertTrue(entry.text.toString().endsWith(settings.getString(R.string.settings_ecarx_cluster_safe_area_custom)))
        assertTrue(PendingReconnect.isPending(owner))
        assertSame(owner, CarPlayBackgroundSession.snapshot()!!.controller)
        assertEquals(AirPlayInsets(top = 100, bottom = 100, left = 300, right = 420),
            currentSessionConfig.cluster!!.safeArea)
        verifyNoInteractions(owner)

        val next = config()
        assertEquals(1920, next.cluster!!.widthPixels)
        assertEquals(720, next.cluster!!.heightPixels)
        assertEquals(AirPlayInsets(top = 100, bottom = 100, left = 200, right = 420), next.cluster!!.safeArea)
        val displays = AirPlayInfoPlist.build(next)["displays"] as List<*>
        val cluster = displays.map { it as Map<*, *> }.single { it["type"] == 111 }
        val area = (cluster["viewAreas"] as List<*>).single() as Map<*, *>
        assertEquals(mapOf("widthPixels" to 1300, "heightPixels" to 520,
            "originXPixels" to 200, "originYPixels" to 100, "drawUIOutsideSafeArea" to true), area["safeArea"])
        assertNull(shadowOf(settings).nextStartedActivity)
    }

    @Test fun resetIsOnlyADraftUntilSavedAndRestoresTheExactOriginalDefault() {
        AirPlayPersistence.saveClusterSafeAreaRect(settings, original)
        val owner = backgroundSession()
        val entry = entry()
        entry.performClick()
        action(R.string.reset).performClick()
        assertEquals(SafeAreaRect(672, 115, 1229, 540), editor().currentRectForSource())
        assertEquals(original, AirPlayPersistence.loadClusterSafeAreaRect(settings))
        dialog().cancel()
        assertEquals(original, AirPlayPersistence.loadClusterSafeAreaRect(settings))
        assertFalse(PendingReconnect.isPending(owner))

        entry.performClick()
        action(R.string.reset).performClick()
        action(R.string.save).performClick()
        assertNull(AirPlayPersistence.loadClusterSafeAreaRect(settings))
        assertEquals(defaultInsets, config().cluster!!.safeArea)
        assertTrue(PendingReconnect.isPending(owner))
        assertTrue(entry.text.toString().endsWith(settings.getString(R.string.settings_ecarx_cluster_safe_area_default)))
        verifyNoInteractions(owner)
    }

    @Test fun editingAfterResetSavesTheNewDraftInsteadOfClearingIt() {
        AirPlayPersistence.saveClusterSafeAreaRect(settings, original)
        entry().performClick()
        action(R.string.reset).performClick()
        dragLeft(editor(), from = 336f, to = 300f)
        action(R.string.save).performClick()
        assertEquals(SafeAreaRect(600, 116, 1230, 540), AirPlayPersistence.loadClusterSafeAreaRect(settings))
        assertEquals(600, config().cluster!!.safeArea!!.left)
    }

    @Test fun mainMappingAndWindowDimensionsCannotChangeThePhysicalClusterMapping() {
        AirPlayPersistence.saveSafeAreaRect(settings, 1920, 720, SafeAreaRect(20, 30, 1900, 700))
        AirPlayPersistence.saveClusterSafeAreaRect(settings, original)
        val sameSize = config(width = 1920, height = 720)
        assertEquals(AirPlayInsets(top = 30, bottom = 20, left = 20, right = 20), sameSize.main.safeArea)
        assertEquals(AirPlayInsets(top = 100, bottom = 100, left = 300, right = 420), sameSize.cluster!!.safeArea)

        val resized = config(width = 1280, height = 480)
        assertEquals(1280, resized.main.widthPixels)
        assertEquals(480, resized.main.heightPixels)
        assertEquals(sameSize.cluster, resized.cluster)
        assertEquals(SafeAreaRect(20, 30, 1900, 700), AirPlayPersistence.loadSafeAreaRect(settings, 1920, 720))
    }

    @Test fun editorResizingPreservesSourceCoordinatesAndPhysicalAspect() {
        AirPlayPersistence.saveClusterSafeAreaRect(settings, original)
        entry().performClick()
        val view = editor()
        val preview = view.parent as ViewGroup
        for ((width, height) in listOf(960 to 500, 320 to 400, 1200 to 250)) {
            preview.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            preview.layout(0, 0, width, height)
            assertEquals(original, view.currentRectForSource())
            assertEquals(view.width * 720 / 1920, view.height)
            assertTrue(view.width <= width && view.height <= height)
        }
        action(R.string.save).performClick()
        assertEquals(original, AirPlayPersistence.loadClusterSafeAreaRect(settings))
        assertEquals(AirPlayInsets(top = 100, bottom = 100, left = 300, right = 420), config().cluster!!.safeArea)
    }

    @Test fun storedBoundsAreClampedAndOddSizedAreasUseTheExistingEvenAlignment() {
        AirPlayPersistence.saveClusterSafeAreaRect(settings, SafeAreaRect(1900, 700, 3000, 2000))
        assertEquals(AirPlayInsets(top = 700, bottom = 0, left = 1900, right = 0), config().cluster!!.safeArea)
        AirPlayPersistence.saveClusterSafeAreaRect(settings, SafeAreaRect(301, 101, 1502, 620))
        assertEquals(AirPlayInsets(top = 101, bottom = 99, left = 301, right = 417), config().cluster!!.safeArea)
        settings.getSharedPreferences("xcertplay_airplay", 0).edit()
            .putString("cluster_safe_area_1920x720", "not a rectangle").commit()
        assertEquals(defaultInsets, config().cluster!!.safeArea)
    }

    @Test fun nonEcarxHeadUnitHasNoEditorAndKeepsItsVirtualStreamDefaults() {
        shadowOf(settings.packageManager).removePackage("com.ecarx.xui.service")
        AirPlayPersistence.saveClusterSafeAreaRect(settings, original)
        AirPlayPersistence.saveClusterMapEnabled(settings, true)
        assertFalse(views(navigationSettings()).filterIsInstance<Button>().any {
            it.text.toString().startsWith(settings.getString(R.string.settings_ecarx_cluster_safe_area))
        })
        val virtual = config().cluster!!
        assertEquals(1280, virtual.widthPixels)
        assertEquals(720, virtual.heightPixels)
        assertEquals(AirPlayInsets(top = 57, bottom = 57, left = 102, right = 102), virtual.safeArea)
    }

    private fun navigationSettings(): LinearLayout {
        ReflectionHelpers.setField(settings, "page", "settings")
        ReflectionHelpers.setField(settings, "settingsSectionFilter",
            SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.NAVIGATION).toSet())
        return LinearLayout(settings).apply {
            orientation = LinearLayout.VERTICAL
            settings.javaClass.getDeclaredMethod("allSettingsSections", LinearLayout::class.java)
                .apply { isAccessible = true }.invoke(settings, this)
        }
    }

    private fun entry() = views(navigationSettings()).filterIsInstance<Button>().single {
        it.text.toString().startsWith(settings.getString(R.string.settings_ecarx_cluster_safe_area) + " · ")
    }

    private fun dialog() = checkNotNull(ReflectionHelpers.getField<Dialog?>(settings, "clusterSafeAreaDialog"))
    private fun editor() = views(dialog().window!!.decorView).filterIsInstance<SafeAreaEditorView>().single()
    private fun action(id: Int) = views(dialog().window!!.decorView).filterIsInstance<Button>()
        .single { it.text.toString() == settings.getString(id) }

    private fun dragLeft(editor: SafeAreaEditorView, from: Float, to: Float) {
        editor.layout(0, 0, 960, 360)
        for ((action, x) in listOf(MotionEvent.ACTION_DOWN to from, MotionEvent.ACTION_MOVE to to,
            MotionEvent.ACTION_UP to to)) {
            val event = MotionEvent.obtain(0, 0, action, x, 180f, 0)
            editor.onTouchEvent(event)
            event.recycle()
        }
    }

    private fun backgroundSession(): CarPlayController {
        val owner = mock(CarPlayController::class.java)
        CarPlayBackgroundSession.store(owner, mock(AndroidMediaSink::class.java), 1920, 720, Any(),
            CarPlaySessionDisplay(1920, 720, Surface.ROTATION_0, false, false, 1920, 720)) {
            fail("Saving settings must wait for the driver's explicit reconnect")
        }
        return owner
    }

    private fun config(width: Int = 1920, height: Int = 720): AirPlayConfig {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get().also(hosts::add)
        ReflectionHelpers.setField(host, "airPlayIdentity", AirPlayIdentity.generate())
        ReflectionHelpers.setField(host, "hevcEnabled", false)
        ReflectionHelpers.setField(host, "blackCornerRadiusDp", 0)
        ReflectionHelpers.setField(host, "mainDisplayDisableSafeArea", false)
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(width, height)
        return host.javaClass.getDeclaredMethod("createAirPlayConfig", sizeClass)
            .apply { isAccessible = true }.invoke(host, size) as AirPlayConfig
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
