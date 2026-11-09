package com.shilapi.xcertplay

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.graphics.Color
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.shilapi.xcertplay.host.R

/**
 * The instrument cluster as what it actually is on this head unit: a second Android display.
 *
 * Proved on the car (fix35, report 6 / 10-24):
 *
 * ```
 * displays at init count=2
 *   | #0 "内置屏幕"  1920x1080@60Hz flags=0x3
 *   | #1 "HDMI 屏幕" 1920x720@60Hz  flags=0xb      <- protectedBuffers + secure + presentation
 * ```
 *
 * `0xb` carries `FLAG_PRESENTATION` and — the part that decides this whole approach — **not**
 * `FLAG_PRIVATE`. A private display would only ever show its owner's windows, which is why every
 * attempt to influence the mirror from the outside failed; a public presentation display accepts a
 * window from any process that can hold one, and this app is signed with the platform key.
 *
 * By default the window shows CarPlay's instrument-cluster stream (type 111), sized by
 * [com.shilapi.xcertplay.airplay.CarPlayClusterDisplay] to the panel's native 8:3 — not a scaled
 * copy of the 1920x944 centre screen, which is what the platform's mirror paints there and why the
 * picture looked wrong: 944 → 720 is a 24 % squeeze.
 *
 * The presentation owns no lifecycle of its own. [EcarxClusterSession] retains it across Activity
 * recreation and decides when the route/session allows `show()` / `dismiss()`; this class only
 * hands the decoder a surface and takes it away again before the window goes down.
 */
internal class EcarxClusterPresentation(
    context: Context,
    display: Display,
    private val onSurface: (Surface?) -> Unit,
    private val onPresented: (Surface) -> Unit = {},
) : Presentation(context, display) {

    private var texture: ClusterVideoTexture? = null
    private var videoSize = 1920 to 720
    private var calibration: ImageView? = null
    private var calibrationVisible = false

    fun setCalibrationVisible(visible: Boolean) {
        calibrationVisible = visible
        calibration?.let { image ->
            if (visible && image.drawable == null) image.setImageResource(R.drawable.cluster_calibration_grid)
            image.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    fun setVideoSize(width: Int, height: Int) {
        if (videoSize.first == width && videoSize.second == height) return
        videoSize = width to height
        texture?.setVideoSize(width, height)
    }

    /** View sizes are layout observations; neither the decoded stream nor final cluster visibility. */
    fun videoGeometryDiagnostics(): String {
        val mode = display.mode
        val decor = window?.peekDecorView()
        val content = texture?.parent as? View
        val sizes = listOf(
            "displayMode" to (mode.physicalWidth to mode.physicalHeight),
            "windowDecor" to (decor?.width to decor?.height),
            "contentLayout" to (content?.width to content?.height),
            "textureLayout" to (texture?.width to texture?.height),
            "surfaceCallback" to (texture?.surfaceCallbackSize?.first to texture?.surfaceCallbackSize?.second),
            "expectedVideo" to videoSize,
        )
        return sizes.joinToString(" ") { (name, size) ->
            val (width, height) = size
            "$name=" + if (width != null && height != null && width > 0 && height > 0) "${width}x$height" else "unknown"
        } + " decodedVideo=unobserved"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The cluster takes no input. Focusable, it took the system's one focused window from the
        // CarPlay screen when it appeared after it, and Android 9 then showed the head unit's status
        // bar over CarPlay: the bars follow the focused window's flags (car test 2026-11-07).
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val view = ClusterVideoTexture(context, onPresented = onPresented, onSurface = onSurface)
        texture = view
        view.setVideoSize(videoSize.first, videoSize.second)
        setContentView(FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
            addView(view, FrameLayout.LayoutParams(matchParent, matchParent))
            // Match the independent 8:3 texture's fit within the presentation window.
            calibration = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                .also { addView(it, FrameLayout.LayoutParams(matchParent, matchParent)) }
            setCalibrationVisible(calibrationVisible)
        })
    }

    override fun onStop() {
        // `close()` releases the Surface wrapper, which calls back with null first — so the decoder
        // stops being pointed at a window that is about to disappear, rather than at a dead surface.
        texture?.close()
        texture = null
        calibration = null
        super.onStop()
    }
}
