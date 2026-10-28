package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1920dp-h1080dp-land-mdpi")
class HomeLayoutTest {
    @Test fun currentHeadUnitKeepsWirelessAndUsbVisible() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        try {
            val root = activity.window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 1080)
            val labels = descendants(root).filterIsInstance<TextView>().toList()
            for (id in listOf(R.string.connect_phone, R.string.connect_with_usb, R.string.car_home)) {
                val label = labels.single { it.text == activity.getString(id) }
                val bounds = android.graphics.Rect()
                assertTrue(label.getGlobalVisibleRect(bounds))
                assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= 1920 && bounds.bottom <= 1080)
            }
            assertTrue(labels.any { it.text == activity.getString(R.string.a_familiar_drive) })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
