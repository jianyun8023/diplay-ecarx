package com.shilapi.xcertplay

import android.graphics.Matrix
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ClusterVideoTextureTest {
    @Test fun mainPictureFitsTheClusterWithoutStretchingOrCropping() {
        val view = ClusterVideoTexture(RuntimeEnvironment.getApplication(), onSurface = {})
        view.layout(0, 0, 1920, 720)
        view.setVideoSize(1920, 944)
        val bounds = RectF(0f, 0f, 1920f, 720f)
        view.getTransform(Matrix()).mapRect(bounds)
        assertEquals(1920f / 944f, bounds.width() / bounds.height(), 0.001f)
        assertEquals(720f, bounds.height(), 0.01f)
        assertEquals(960f, bounds.centerX(), 0.01f)
        view.setVideoSize(1920, 720)
        val native = RectF(0f, 0f, 1920f, 720f)
        view.getTransform(Matrix()).mapRect(native)
        assertEquals(RectF(0f, 0f, 1920f, 720f), native)
        view.close()
    }
}
