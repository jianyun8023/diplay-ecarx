package com.shilapi.xcertplay

import android.os.Handler
import android.content.pm.ApplicationInfo
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class SidePanelLayoutTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var root: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var video: TextureView
    private val sink = AndroidMediaSink()

    @Before fun setup() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.applicationInfo.flags = activity.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        activity.applicationInfo.targetSdkVersion = 29
        root = FrameLayout(activity)
        panel = LinearLayout(activity).also { root.addView(it) }
        video = TextureView(activity)
        set("sidePanel", panel)
        set("videoView", video)
        set("sidePanelShown", true)
    }

    @After fun cleanup() {
        (get("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (get("teardownExecutor") as ExecutorService).shutdownNow()
        (get("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.clear()
        sink.close()
    }

    @Test fun currentHeadUnitPanelStaysOnThePassengerSide() {
        for (rtl in listOf(false, true)) for (rightHandDrive in listOf(false, true)) {
            root.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            val areas = CarPlayViewAreas.build(1920, 1080, CarPlayDock.AUTOMATIC, null,
                sidePanel = true, rightHandDrive = rightHandDrive)!!
            areas.use(areas.sidePanel()!!)
            set("sessionDisplay", CarPlaySessionDisplay(1920, 1080, Surface.ROTATION_0,
                true, true, 1920, 1080, areas))
            place(1920, 1080)
            root.measure(exact(1920), exact(1080))
            root.layout(0, 0, 1920, 1080)
            assertEquals(if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR, root.layoutDirection)
            assertEquals("rtl=$rtl rightHandDrive=$rightHandDrive", if (rightHandDrive) 0 else 1280, panel.left)
            assertEquals(0, panel.top)
            assertEquals(640, panel.width)
            assertEquals(1080, panel.height)
        }
    }

    @Test fun adoptingAnOpenSidePanelRestoresItsViewAndRefreshWithoutChangingTheArea() {
        val areas = CarPlayViewAreas.build(1920, 1080, CarPlayDock.AUTOMATIC, null, sidePanel = true)!!
        val selected = areas.sidePanel()!!
        areas.use(selected)
        val display = CarPlaySessionDisplay(1920, 1080, Surface.ROTATION_0, true, true, 1920, 1080, areas)
        val controller = mock(CarPlayController::class.java)
        CarPlayBackgroundSession.store(controller, sink, 1920, 1080, Any(), display) {}
        set("sidePanelShown", false)
        panel.visibility = View.GONE
        assertEquals(true, activity.javaClass.getDeclaredMethod("adoptBackgroundSession")
            .apply { isAccessible = true }.invoke(activity))
        assertEquals(selected, areas.current)
        assertEquals(true, get("sidePanelShown"))
        assertEquals(View.VISIBLE, panel.visibility)
        assertTrue((get("mainHandler") as Handler).hasCallbacks(get("sidePanelTick") as Runnable))
    }

    private fun place(width: Int, height: Int) {
        activity.javaClass.getDeclaredMethod("placeSidePanel", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, width, height)
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun get(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun set(name: String, value: Any?) = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(activity, value)
}
