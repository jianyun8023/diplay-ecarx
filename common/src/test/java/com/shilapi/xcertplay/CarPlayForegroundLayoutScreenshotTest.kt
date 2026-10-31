package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Synthetic frame, real layout, current 1920x1080 head-unit size. Not a vehicle screenshot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1920dp-h1080dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarPlayForegroundLayoutScreenshotTest {
    @Test fun visibleAndTemporarilyHiddenBarsHaveTheSameFullPicture() {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        val frame = Bitmap.createBitmap(1920, 944, Bitmap.Config.ARGB_8888)
        Canvas(frame).apply {
            drawColor(Color.rgb(24, 43, 65))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN; strokeWidth = 4f }
            for (x in 0..1920 step 160) drawLine(x.toFloat(), 0f, x.toFloat(), 944f, paint)
            for (y in 0..944 step 118) drawLine(0f, y.toFloat(), 1920f, y.toFloat(), paint)
            paint.color = Color.YELLOW
            for (x in listOf(0f, 1840f)) for (y in listOf(0f, 864f)) drawRect(x, y, x + 80, y + 80, paint)
            paint.color = Color.WHITE
            paint.textSize = 44f
            drawText("Synthetic retained CarPlay frame: all four corners visible", 180f, 470f, paint)
        }
        val viewport = CarPlaySurfaceViewport(activity,
            { w, h -> CarPlayVideoLayout.fit(1920, 944, w, h) }) { _, _ -> }
        viewport.addView(ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(frame)
        })
        val root = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            addView(viewport, FrameLayout.LayoutParams(-1, -1))
        }
        fun capture(visible: Boolean): Bitmap {
            val status = Insets.of(0, 64, 0, 0)
            val navigation = Insets.of(0, 0, 0, 136)
            val insets = WindowInsetsCompat.Builder()
                .setStableInsets(Insets.max(status, navigation))
                .setSystemWindowInsets(if (visible) Insets.max(status, navigation) else Insets.NONE)
                .build().also {
                    WindowInsetsCompat::class.java.getDeclaredMethod("setRootWindowInsets", WindowInsetsCompat::class.java)
                        .apply { isAccessible = true }.invoke(it, it)
                }
            val safe = carPlayHostContentInsets(insets, false, false)
            root.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 1080)
            assertEquals(880, viewport.height)
            return Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888).also { image ->
                root.draw(Canvas(image))
            }
        }
        val before = capture(true)
        val transition = capture(false)
        val after = capture(true)
        assertTrue(before.sameAs(transition))
        assertTrue(before.sameAs(after))
        val folder = File("build/reports/foreground-layout-ui").apply { mkdirs() }
        for ((name, bitmap) in listOf("bars-visible" to before, "bars-temporarily-hidden" to transition)) {
            File(folder, "$name-1920x1080.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        before.recycle()
        transition.recycle()
        after.recycle()
        frame.recycle()
    }
}
