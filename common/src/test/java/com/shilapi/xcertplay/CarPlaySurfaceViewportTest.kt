package com.shilapi.xcertplay

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CarPlaySurfaceViewportTest {
    @Test fun pictureAndSurfaceUseIdenticalBoundsWithoutRequestingASecondLayout() {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        var notifications = 0
        lateinit var viewport: CarPlaySurfaceViewport
        viewport = CarPlaySurfaceViewport(activity, { w, h -> CarPlayVideoLayout.fit(1920, 944, w, h) }) { _, _ ->
            notifications++
            viewport.updateContent() // The host's callback is allowed to request the same content.
        }
        val video = View(activity)
        val picture = ImageView(activity)
        viewport.addView(video, FrameLayout.LayoutParams(-1, -1))
        viewport.addView(picture, FrameLayout.LayoutParams(-1, -1))
        for ((w, h) in listOf(1920 to 944, 1920 to 880, 1000 to 700, 880 to 1920)) {
            layout(viewport, w, h)
            val expected = CarPlaySurfaceBounds.from(CarPlayVideoLayout.fit(1920, 944, w, h))
            for (child in listOf(video, picture)) {
                assertEquals(expected.left, child.left)
                assertEquals(expected.top, child.top)
                assertEquals(expected.width, child.width)
                assertEquals(expected.height, child.height)
                assertEquals(child.width, child.measuredWidth)
                assertEquals(child.height, child.measuredHeight)
                assertEquals(-1, child.layoutParams.width)
            }
            assertFalse(viewport.isLayoutRequested)
        }
        assertEquals(4, notifications)
        layout(viewport, 880, 1920)
        assertEquals(4, notifications)
    }

    @Test fun changedSessionGeometryRelayoutsBothLayersEvenWithoutAWindowResize() {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        var canvasHeight = 944
        val viewport = CarPlaySurfaceViewport(activity,
            { w, h -> CarPlayVideoLayout.fit(1920, canvasHeight, w, h) }) { _, _ -> }
        val video = View(activity)
        val picture = ImageView(activity)
        viewport.addView(video)
        viewport.addView(picture)
        layout(viewport, 1920, 880)
        assertEquals(65, video.left)
        canvasHeight = 880
        viewport.updateContent()
        assertTrue(viewport.isLayoutRequested)
        layout(viewport, 1920, 880)
        assertEquals(0, video.left)
        assertEquals(1920, video.width)
        assertEquals(video.width, picture.width)
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
}
