package com.shilapi.xcertplay

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.graphics.Color
import android.view.Display
import android.view.Surface
import android.view.ViewGroup
import android.widget.FrameLayout

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
) : Presentation(context, display) {

    private var texture: ClusterVideoTexture? = null
    private var videoSize = 1920 to 720

    fun setVideoSize(width: Int, height: Int) {
        if (videoSize.first == width && videoSize.second == height) return
        videoSize = width to height
        texture?.setVideoSize(width, height)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // `onPresented` is left at its default: nothing here needs a per-frame callback, and an
        // empty lambda at this call site would add a class to the dex for no behaviour at all.
        val view = ClusterVideoTexture(context, onSurface = onSurface)
        texture = view
        view.setVideoSize(videoSize.first, videoSize.second)
        setContentView(FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
    }

    override fun onStop() {
        // `close()` releases the Surface wrapper, which calls back with null first — so the decoder
        // stops being pointed at a window that is about to disappear, rather than at a dead surface.
        texture?.close()
        texture = null
        super.onStop()
    }
}
