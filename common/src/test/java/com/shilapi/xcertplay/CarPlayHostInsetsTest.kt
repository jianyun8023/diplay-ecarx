package com.shilapi.xcertplay

import android.graphics.Rect
import android.view.DisplayCutout
import android.view.WindowInsets
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CarPlayHostInsetsTest {
    private fun insets(statusVisible: Boolean = true, navigationVisible: Boolean = true,
        status: Insets = Insets.of(0, 64, 0, 0), navigation: Insets = Insets.of(0, 0, 0, 136),
        cutout: DisplayCutout? = null, imeBottom: Int = 0): WindowInsetsCompat {
        val current = Insets.max(if (statusVisible) status else Insets.NONE,
            if (navigationVisible) navigation else Insets.NONE)
        val stable = Insets.max(status, navigation)
        val constructor = WindowInsets::class.java.getDeclaredConstructor(Rect::class.java,
            Rect::class.java, Rect::class.java, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, DisplayCutout::class.java).apply { isAccessible = true }
        val native = constructor.newInstance(Rect(current.left, current.top, current.right,
            maxOf(current.bottom, imeBottom)), null,
            Rect(stable.left, stable.top, stable.right, stable.bottom), false, false, cutout) as WindowInsets
        return WindowInsetsCompat.toWindowInsetsCompat(native).also {
            // ViewCompat provides this root context for attached views on API 28. These tests avoid
            // starting CarPlayHostActivity (which would start a real connection) just to attach it.
            WindowInsetsCompat::class.java.getDeclaredMethod("setRootWindowInsets", WindowInsetsCompat::class.java)
                .apply { isAccessible = true }.invoke(it, it)
        }
    }

    private fun dispatch(root: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val platformWrapper = root.getTag(androidx.core.R.id.tag_on_apply_window_listener)
        val listener = platformWrapper.javaClass.declaredFields.first {
            OnApplyWindowInsetsListener::class.java.isAssignableFrom(it.type)
        }.apply { isAccessible = true }.get(platformWrapper) as OnApplyWindowInsetsListener
        return listener.onApplyWindowInsets(root, insets)
    }

    @Test fun requestedVisibleBarsReserveTheSameSpaceThroughNineteenHideRestoreCycles() {
        val shown = insets()
        val hidden = insets(false, false)
        repeat(19) {
            assertEquals(Insets.of(0, 64, 0, 136), carPlayHostContentInsets(shown, false, false))
            assertEquals(carPlayHostContentInsets(shown, false, false),
                carPlayHostContentInsets(hidden, false, false))
        }
    }

    @Test fun eachPersistentBarPreferenceAndTemporaryRevealUseRequestedGeometry() {
        for (hideTop in listOf(false, true)) for (hideBottom in listOf(false, true)) {
            val expected = Insets.of(0, if (hideTop) 0 else 64, 0, if (hideBottom) 0 else 136)
            assertEquals(expected, carPlayHostContentInsets(insets(), hideTop, hideBottom))
            assertEquals(expected, carPlayHostContentInsets(insets(false, false), hideTop, hideBottom))
        }
    }

    @Test fun navigationOnTheSideIsNotMistakenForBottomOrHardcodedHeight() {
        val rotated = insets(status = Insets.of(0, 32, 0, 0), navigation = Insets.of(0, 0, 80, 0))
        assertEquals(Insets.of(0, 32, 80, 0), carPlayHostContentInsets(rotated, false, false))
        assertEquals(Insets.of(0, 32, 0, 0), carPlayHostContentInsets(rotated, false, true))
    }

    @Test fun hostConsumesItsReservationAndKeepsVideoAndTouchInTheSameSafeSpace() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        set(activity, "hideTopBar", false)
        set(activity, "hideBottomBar", false)
        val root = activity.javaClass.getDeclaredMethod("buildContentView").apply { isAccessible = true }
            .invoke(activity) as FrameLayout
        val result = dispatch(root, insets())
        assertTrue(result.isConsumed)
        assertEquals(64, root.paddingTop)
        assertEquals(136, root.paddingBottom)
        val video = field(activity, "videoView") as View
        val touch = field(activity, "gestureOverlay") as View
        layout(root, 1920, 1080)
        assertEquals(1920, video.width)
        assertEquals(880, video.height)
        assertEquals(video.top, touch.top)
        assertEquals(video.height, touch.height)
        dispatch(root, insets(false, false))
        layout(root, 1920, 1080)
        assertEquals(880, video.height)
        val preparation = field(activity, "connectionPanel") as View
        assertEquals(0, preparation.paddingTop)
        assertEquals(0, preparation.paddingBottom)
        // A real outer-window resize still changes the content immediately; no old viewport lock.
        layout(root, 1200, 1080)
        assertEquals(1200, video.width)
        assertEquals(880, video.height)
    }

    @Test fun harmanKeepsItsOwnInsetsPathAndRemovesAnOldReservation() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        set(activity, "hideTopBar", false)
        set(activity, "hideBottomBar", false)
        val root = activity.javaClass.getDeclaredMethod("buildContentView").apply { isAccessible = true }
            .invoke(activity) as FrameLayout
        dispatch(root, insets())
        assertEquals(64, root.paddingTop)
        set(activity, "harmanFullscreenEnabled", true)
        assertFalse(dispatch(root, insets()).isConsumed)
        assertEquals(0, root.paddingTop)
        assertEquals(0, root.paddingBottom)
    }

    class MultiWindowHost : CarPlayHostActivity() {
        override fun isInMultiWindowMode() = true
    }

    @Test fun multiWindowKeepsPlatformFittingWithoutASecondInsetReservation() {
        val activity = Robolectric.buildActivity(MultiWindowHost::class.java).get()
        set(activity, "hideTopBar", false)
        set(activity, "hideBottomBar", false)
        val root = CarPlayHostActivity::class.java.getDeclaredMethod("buildContentView")
            .apply { isAccessible = true }.invoke(activity) as FrameLayout
        assertFalse(dispatch(root, insets()).isConsumed)
        assertEquals(0, root.paddingTop)
        assertEquals(0, root.paddingBottom)
        layout(root, 1000, 880)
        val video = field(activity, "videoView") as View
        assertEquals(1000, video.width)
        assertEquals(880, video.height)
    }

    @Test fun api28NativeStableInsetsSurviveActualBarVisibilityChanges() {
        // API 28 has no WindowInsets.Builder; use its native constructor rather than Compat's
        // synthetic per-type overrides. ViewCompat supplies the root insets when dispatching.
        val constructor = WindowInsets::class.java.getDeclaredConstructor(Rect::class.java,
            Rect::class.java, Rect::class.java, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, DisplayCutout::class.java).apply { isAccessible = true }
        fun native(top: Int, bottom: Int): WindowInsetsCompat {
            val value = constructor.newInstance(Rect(0, top, 0, bottom), null,
                Rect(0, 64, 0, 136), false, false, null) as WindowInsets
            val compat = WindowInsetsCompat.toWindowInsetsCompat(value)
            WindowInsetsCompat::class.java.getDeclaredMethod("setRootWindowInsets", WindowInsetsCompat::class.java)
                .apply { isAccessible = true }.invoke(compat, compat)
            return compat
        }
        assertEquals(Insets.of(0, 64, 0, 136), carPlayHostContentInsets(native(64, 136), false, false))
        assertEquals(Insets.of(0, 64, 0, 136), carPlayHostContentInsets(native(0, 136), false, false))
        assertEquals(Insets.of(0, 64, 0, 136), carPlayHostContentInsets(native(0, 0), false, false))
    }

    @Test fun stableLayoutFlagsDoNotHideBarsTheDriverKeeps() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val apply = CarPlayHostActivity::class.java.getDeclaredMethod("applyFullscreenMode")
            .apply { isAccessible = true }
        for (hideTop in listOf(false, true)) for (hideBottom in listOf(false, true)) {
            set(activity, "hideTopBar", hideTop)
            set(activity, "hideBottomBar", hideBottom)
            apply.invoke(activity)
            val flags = activity.window.decorView.systemUiVisibility
            assertTrue(flags and View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN != 0)
            assertTrue(flags and View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION != 0)
            assertEquals(hideTop, flags and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
            assertEquals(hideBottom, flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
        }
    }

    @Test fun cutoutAndKeyboardUseMaxInsetsRatherThanDoubleSubtractingBars() {
        val source = insets(cutout = DisplayCutout(Rect(20, 80, 0, 0), emptyList()), imeBottom = 400)
        assertEquals(Insets.of(20, 80, 0, 400), carPlayHostContentInsets(source, false, false))
    }

    @Test fun enteringBothHiddenRemovesTheVisibleBarReservation() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        set(activity, "hideTopBar", false)
        set(activity, "hideBottomBar", false)
        val root = CarPlayHostActivity::class.java.getDeclaredMethod("buildContentView")
            .apply { isAccessible = true }.invoke(activity) as FrameLayout
        dispatch(root, insets())
        assertEquals(64, root.paddingTop)
        set(activity, "hideTopBar", true)
        set(activity, "hideBottomBar", true)
        assertFalse(dispatch(root, insets(false, false)).isConsumed)
        assertEquals(0, root.paddingTop)
        assertEquals(0, root.paddingBottom)
        set(activity, "hideBottomBar", false)
        assertTrue(dispatch(root, insets()).isConsumed)
        assertEquals(0, root.paddingTop)
        assertEquals(136, root.paddingBottom)
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
    private fun field(activity: CarPlayHostActivity, name: String): Any? =
        CarPlayHostActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun set(activity: CarPlayHostActivity, name: String, value: Any) {
        CarPlayHostActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
}
