package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1920dp-h1080dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostMenuGestureTest {
    private lateinit var activity: CarPlayHostActivity

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
    }

    @After fun tearDown() {
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        ReflectionHelpers.getField<ExecutorService>(activity, "teardownExecutor").shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
    }

    @Test fun threeFingerSwipeOpensMeasuredMenuAfterTouchDispatch() {
        val root = ReflectionHelpers.callInstanceMethod<FrameLayout>(activity, "buildContentView")
        val gesture = ReflectionHelpers.getField<View>(activity, "gestureOverlay")
        val originalMenu = ReflectionHelpers.getField<View>(activity, "settingsMenu")
        ReflectionHelpers.setField(activity, "gestureFingerCount", 3)
        ReflectionHelpers.setField(activity, "gestureSequenceActive", true)
        ReflectionHelpers.setField(activity, "gestureTracking", true)
        ReflectionHelpers.setField(activity, "gestureStartX", 100f)
        ReflectionHelpers.setField(activity, "gestureStartY", 100f)
        val pointers = Array(3) { index -> MotionEvent.PointerProperties().apply { id = index } }
        val positions = Array(3) { index -> MotionEvent.PointerCoords().apply {
            x = 100f + index * 20f
            y = 300f
        } }
        val move = MotionEvent.obtain(0, 1, MotionEvent.ACTION_MOVE, 3, pointers, positions,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            assertTrue(ReflectionHelpers.callInstanceMethod(activity, "onHostTouch",
                ClassParameter.from(View::class.java, gesture),
                ClassParameter.from(MotionEvent::class.java, move)))
        } finally {
            move.recycle()
        }
        assertFalse(ReflectionHelpers.getField(activity, "menuOpen"))
        assertTrue(ReflectionHelpers.getField(activity, "gestureSequenceActive"))
        assertEquals(View.GONE, originalMenu.visibility)

        shadowOf(Looper.getMainLooper()).idle()
        val menu = ReflectionHelpers.getField<View>(activity, "settingsMenu")
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 1080)
        assertTrue(ReflectionHelpers.getField(activity, "menuOpen"))
        assertNotSame(originalMenu, menu)
        assertEquals(View.VISIBLE, menu.visibility)
        assertEquals(View.GONE, gesture.visibility)
        assertEquals(1920, menu.width)
        assertEquals(1080, menu.height)
        assertTrue((menu as FrameLayout).getChildAt(0).width > 0)
    }
}
