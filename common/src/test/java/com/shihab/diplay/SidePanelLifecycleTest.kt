package com.shihab.diplay

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class SidePanelLifecycleTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var handler: Handler
    private lateinit var panel: View

    @Before fun setup() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        handler = get("mainHandler") as Handler
        panel = LinearLayout(activity).also { set("sidePanel", it) }
        set("sidePanelShown", true)
    }

    @After fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        (get("teardownExecutor") as ExecutorService).shutdownNow()
        (get("airPlayCommandExecutor") as ExecutorService).shutdownNow()
    }

    @Test fun destroyingAHostWithThePanelOpenHidesIt() {
        activity.javaClass.getDeclaredMethod("onDestroy").apply { isAccessible = true }.invoke(activity)
        assertPanelStopped()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertPanelStopped()
    }

    @Test fun shutdownHidesThePanelBeforeTheActivityIsDestroyed() {
        activity.javaClass.getDeclaredMethod("shutdown", Boolean::class.javaPrimitiveType,
            String::class.java, Function0::class.java).apply { isAccessible = true }
            .invoke(activity, false, "side panel cleanup test", {})
        assertPanelStopped()
        assertTrue((get("teardownExecutor") as ExecutorService).awaitTermination(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertPanelStopped()
    }

    private fun assertPanelStopped() {
        assertEquals(false, get("sidePanelShown"))
        assertEquals(View.GONE, panel.visibility)
    }

    private fun owner(name: String): Any = if (name.startsWith("sidePanel"))
        activity.javaClass.getDeclaredField("sidePanelUi").apply { isAccessible = true }.get(activity) else activity

    private fun get(name: String): Any? = owner(name).let { target ->
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }

    private fun set(name: String, value: Any?) {
        val target = owner(name)
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
