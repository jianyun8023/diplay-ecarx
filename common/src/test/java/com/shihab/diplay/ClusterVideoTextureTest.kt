package com.shihab.diplay

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.View
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ClusterVideoTextureTest {
    @Test fun firstTextureUpdateSeparatesDisplayLayoutAndExpectedSizesFromUnknownDecoderSize() {
        val app = RuntimeEnvironment.getApplication()
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        var observed = ""
        val presentedSurfaces = mutableListOf<android.view.Surface>()
        lateinit var panel: EcarxClusterPresentation
        panel = EcarxClusterPresentation(app, display, onSurface = {}, onPresented = { surface ->
            presentedSurfaces += surface
            observed = panel.videoGeometryDiagnostics()
        })
        panel.setVideoSize(1920, 720)
        val beforeLayout = panel.videoGeometryDiagnostics()
        assertTrue(beforeLayout.contains("displayMode=${display.mode.physicalWidth}x${display.mode.physicalHeight}"))
        assertTrue(beforeLayout.contains("windowDecor=unknown contentLayout=unknown textureLayout=unknown surfaceCallback=unknown"))
        panel.show()
        val texture = ReflectionHelpers.getField<ClusterVideoTexture>(panel, "texture")
        panel.window!!.decorView.layout(0, 0, 1600, 600)
        (texture.parent as View).layout(0, 0, 1500, 550)
        texture.layout(0, 0, 1400, 500)
        val surfaceTexture = SurfaceTexture(0)
        val listener = texture.surfaceTextureListener!!
        listener.onSurfaceTextureAvailable(surfaceTexture, 1200, 450)
        assertTrue(panel.videoGeometryDiagnostics().contains("surfaceCallback=1200x450"))
        listener.onSurfaceTextureSizeChanged(surfaceTexture, 1300, 480)
        listener.onSurfaceTextureUpdated(surfaceTexture)
        assertTrue(observed.contains("windowDecor=1600x600 contentLayout=1500x550 textureLayout=1400x500"))
        assertTrue(observed.contains("surfaceCallback=1300x480 expectedVideo=1920x720 decodedVideo=unobserved"))
        val replacementTexture = SurfaceTexture(0)
        listener.onSurfaceTextureAvailable(replacementTexture, 1350, 490)
        listener.onSurfaceTextureUpdated(surfaceTexture)
        assertEquals("An old texture cannot report a frame for the replacement Surface", 1, presentedSurfaces.size)
        listener.onSurfaceTextureUpdated(replacementTexture)
        assertEquals(2, presentedSurfaces.size)
        org.junit.Assert.assertNotSame(presentedSurfaces[0], presentedSurfaces[1])
        listener.onSurfaceTextureDestroyed(replacementTexture)
        listener.onSurfaceTextureUpdated(replacementTexture)
        assertEquals(2, presentedSurfaces.size)
        assertTrue(panel.videoGeometryDiagnostics().contains("surfaceCallback=unknown"))
        surfaceTexture.release()
        replacementTexture.release()
        panel.dismiss()
    }

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
