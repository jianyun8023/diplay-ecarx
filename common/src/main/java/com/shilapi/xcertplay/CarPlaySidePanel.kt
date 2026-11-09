package com.shilapi.xcertplay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.ViewConfiguration
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import kotlin.math.roundToInt

/** Owns the side panel's views, gestures and effects for one host lifetime. */
internal class CarPlaySidePanel(
    context: android.content.Context,
    private val mainHandler: Handler,
    private val sessionDisplayProvider: () -> CarPlaySessionDisplay?,
    private val getVideoView: () -> View?,
    private val getFallbackVideoView: () -> SurfaceView?,
    private val getGestureOverlay: () -> View?,
    private val getAppNight: () -> Boolean,
    private val isMultiWindowActive: () -> Boolean,
    private val screenPortrait: () -> Boolean,
    private val contentRect: (Int, Int) -> CarPlayVideoLayout,
    private val showViewArea: (Int) -> Boolean,
    private val updateVideoLayout: (Int, Int) -> Unit,
    private val onHostTouch: (View, MotionEvent) -> Boolean,
    private val appendLog: (String) -> Unit,
) : android.content.ContextWrapper(context) {
    private val sessionDisplay get() = sessionDisplayProvider()
    private val videoView get() = getVideoView()
    private val fallbackVideoView get() = getFallbackVideoView()
    private val gestureOverlay get() = getGestureOverlay()
    private val appNight get() = getAppNight()

    private var sidePanel: LinearLayout? = null
    // Everything on the panel's card, in one layout so the resize blur covers it as a whole.
    private var sidePanelContents: LinearLayout? = null
    private var sidePanelBattery: TextView? = null
    // The side panel's tyres (TPMS), front left, front right, rear left, rear right; hidden without a reading.
    private var sidePanelTyres: LinearLayout? = null
    private var sidePanelTyreTexts: List<TextView> = emptyList()
    // The side panel's whole screen in window pixels and the panel edge that faces CarPlay (Gravity.START when
    // the panel is right of CarPlay, END when left of it, TOP when below it), for dragging that edge.
    private var sidePanelScreen: RectF? = null
    private var sidePanelEdge = Gravity.START
    private var sidePanelDrag: FloatArray? = null
    // The grip on the panel edge shows for a few seconds when the panel opens or its edge is touched, then fades.
    private var sidePanelGrip: Drawable? = null
    private var sidePanelGripAlpha = 255
    private var sidePanelGripAnimator: ValueAnimator? = null
    private val sidePanelGripFade = Runnable { animateSidePanelGrip(0, 600L) }
    // The tab at the screen edge the side panel takes; dragging it out opens the panel (null drag: not dragged yet).
    private var sidePanelTab: View? = null
    private var sidePanelTabDrag: FloatArray? = null
    // Until a touch on the tab shows it is a drag, copies of its events wait here; CarPlay under the tab
    // gets them when it is not (a tap, a scroll along the edge, a long press), and the rest of that touch.
    private val sidePanelTabHeld = mutableListOf<MotionEvent>()
    private var sidePanelTabToCarPlay = false
    private val sidePanelTabLongPress = Runnable {
        val tab = sidePanelTab
        if (tab != null && sidePanelTabDrag?.get(1) == 0f) handSidePanelTabTouchToCarPlay(tab)
    }
    private var sidePanelTabFrom: Int? = null
    // While the panel edge moves: CarPlay's last picture blurred over the video, and the panel's contents blurred.
    // It fades as soon as the finger lifts: in the car CarPlay's own resize transition looked quicker without it held.
    private var sidePanelBlur: ImageView? = null
    private var sidePanelBlurring = false
    private var sidePanelBlurActive = false
    private var sidePanelBlurGeneration = 0
    private val sidePanelBlurEnd = Runnable { finishSidePanelBlur() }
    private fun finishSidePanelBlur() {
        sidePanelBlurActive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sidePanelContents?.setRenderEffect(null)
        }
        sidePanelBlur?.let { overlay ->
            if (overlay.visibility != View.VISIBLE) return@let
            overlay.animate().alpha(0f).setDuration(400L).withEndAction {
                if (sidePanelBlurActive) return@withEndAction
                overlay.visibility = View.GONE
                overlay.setImageDrawable(null)
            }.start()
        }
    }

    // The tab's pill shows for a few seconds when a session starts or the tab is tapped, then fades; the tab still drags without it.
    private var sidePanelTabHintFor: CarPlayViewAreas? = null
    private var sidePanelTabHint = false
    private val sidePanelTabFade = Runnable {
        sidePanelTabHint = false
        sidePanelTab?.background?.let { pill ->
            ObjectAnimator.ofInt(pill, "alpha", pill.alpha, 0).setDuration(600L).start()
        }
    }
    var sidePanelShown = false
        private set
    private val sidePanelTick = object : Runnable {
        override fun run() {
            refreshSidePanel()
            if (sidePanelShown) mainHandler.postDelayed(this, SIDE_PANEL_REFRESH_MILLIS)
        }
    }

    fun attach(root: FrameLayout) {
        // Above the video and gesture layer, below the menus.
        sidePanelBlur = ImageView(this).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_XY
        }.also { root.addView(it, FrameLayout.LayoutParams(0, 0)) }
        sidePanel = buildSidePanel().also { root.addView(it, FrameLayout.LayoutParams(0, 0)) }
        sidePanelTab = buildSidePanelTab().also { root.addView(it, FrameLayout.LayoutParams(0, 0)) }
    }

    fun restore() {
        sidePanelShown = true
        sidePanel?.visibility = View.VISIBLE
        mainHandler.post(::refreshSidePanel)
        sidePanelTick.run()
        showSidePanelGripHint()
    }

    fun applyPalette() {
        sidePanel?.let { panel ->
            panel.background = sidePanelBackground()
            val contents = sidePanelContents ?: return@let
            (contents.getChildAt(0) as? TextView)?.setTextColor(SIDE_PANEL_PRIMARY)
            sidePanelBattery?.setTextColor(SIDE_PANEL_SECONDARY)
            sidePanelTyreTexts.forEach { it.setTextColor(SIDE_PANEL_PRIMARY) }
            (sidePanelTyres?.getChildAt(0) as? TextView)?.setTextColor(SIDE_PANEL_SECONDARY)
        }
    }

    // The side panel card follows the app theme: black when dark, white (for now) when light.
    private val SIDE_PANEL_CARD: Int get() = if (appNight) Color.BLACK else Color.WHITE
    private val SIDE_PANEL_PRIMARY: Int get() = DiPlayPalette.of(appNight).overlayPrimaryText
    private val SIDE_PANEL_SECONDARY: Int get() = DiPlayPalette.of(appNight).overlaySecondaryText

    private fun buildSidePanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            visibility = View.GONE
            background = sidePanelBackground()
        }
        panel.setOnTouchListener { view, event -> onSidePanelTouch(view, event) }
        val contents = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        sidePanelContents = contents
        panel.addView(contents, LinearLayout.LayoutParams(-1, -1))
        contents.addView(android.widget.TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "h:mm"
            textSize = 72f
            setTextColor(SIDE_PANEL_PRIMARY)
            gravity = Gravity.CENTER
        })
        sidePanelBattery = TextView(this).apply {
            textSize = 30f
            setTextColor(SIDE_PANEL_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(32))
        }
        contents.addView(sidePanelBattery)
        contents.addView(buildSidePanelTyres())
        return panel
    }

    // A rounded card like CarPlay's, with a grip on the edge that faces CarPlay.
    private fun sidePanelBackground(): Drawable {
        val card = GradientDrawable().apply {
            setColor(SIDE_PANEL_CARD)
            cornerRadius = dp(28).toFloat()
        }
        val across = sidePanelEdge != Gravity.TOP
        val grip = GradientDrawable().apply {
            // The grip sits in the black gap beside CarPlay, so it stays light in either theme.
            setColor(Color.rgb(170, 180, 190))
            cornerRadius = dp(3).toFloat()
            setSize(if (across) dp(6) else dp(72), if (across) dp(72) else dp(6))
        }
        grip.alpha = sidePanelGripAlpha
        sidePanelGrip = grip
        return LayerDrawable(arrayOf(card, grip)).apply {
            // A gap only towards CarPlay, with the grip in it; beside CarPlay the bottom ends level with its dock.
            val bottom = if (across) dp(6) else 0
            when (sidePanelEdge) {
                Gravity.TOP -> setLayerInset(0, 0, dp(10), 0, 0)
                Gravity.END -> setLayerInset(0, 0, 0, dp(10), bottom)
                else -> setLayerInset(0, dp(10), 0, 0, bottom)
            }
            setLayerInset(1, 0, 0, 0, bottom)
            setLayerGravity(1, when (sidePanelEdge) {
                Gravity.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                Gravity.END -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
                else -> Gravity.LEFT or Gravity.CENTER_VERTICAL
            })
        }
    }

    /**
     * Drags the panel edge that faces CarPlay: the panel follows the finger, and on release CarPlay moves to the
     * declared share nearest to where the edge was let go (two thirds, half or one third of the screen), or to
     * the whole screen, closing the panel, when it was let go nearer the far side.
     */
    private fun onSidePanelTouch(panel: View, event: MotionEvent): Boolean {
        val screen = sidePanelScreen ?: return false
        val params = panel.layoutParams as? FrameLayout.LayoutParams ?: return false
        val vertical = sidePanelEdge == Gravity.TOP
        val raw = if (vertical) event.rawY else event.rawX
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only a touch near that edge drags; the rest of the panel keeps its taps.
                val distance = when (sidePanelEdge) {
                    Gravity.TOP -> event.y
                    Gravity.END -> panel.width - event.x
                    else -> event.x
                }
                if (distance > dp(56)) return false
                showSidePanelGripHint()
                val edge = when (sidePanelEdge) {
                    Gravity.TOP -> params.topMargin.toFloat()
                    Gravity.END -> (params.leftMargin + params.width).toFloat()
                    else -> params.leftMargin.toFloat()
                }
                sidePanelDrag = floatArrayOf(raw, edge)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val drag = sidePanelDrag ?: return false
                // CarPlay keeps at least a quarter of the screen and can take all of it.
                val quarter = sidePanelEdgeAt(screen, 0.25f)
                val whole = sidePanelEdgeAt(screen, 1f)
                val edge = (drag[1] + raw - drag[0]).coerceIn(minOf(quarter, whole), maxOf(quarter, whole))
                startSidePanelBlur()
                panel.alpha = if (sidePanelCarPlayShare(screen, edge) > SIDE_PANEL_CLOSE_SHARE) 0.5f else 1f
                when (sidePanelEdge) {
                    Gravity.TOP -> { params.topMargin = Math.round(edge); params.height = Math.round(screen.bottom - edge) }
                    Gravity.END -> { params.leftMargin = Math.round(screen.left); params.width = Math.round(edge - screen.left) }
                    else -> { params.leftMargin = Math.round(edge); params.width = Math.round(screen.right - edge) }
                }
                panel.layoutParams = params
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val drag = sidePanelDrag ?: return false
                sidePanelDrag = null
                panel.alpha = 1f
                val carPlay = sidePanelCarPlayShare(screen, drag[1] + raw - drag[0])
                if (event.actionMasked == MotionEvent.ACTION_UP && carPlay > SIDE_PANEL_CLOSE_SHARE) {
                    appendLog("Side panel closed by dragging its edge")
                    endSidePanelBlur()
                    showSidePanel(false)
                    if (sidePanelShown) videoView?.let { updateVideoLayout(it.width, it.height) }
                    return true
                }
                val sixths = CarPlayViewAreas.nearestSidePanelSixths(carPlay.toDouble())
                if (event.actionMasked == MotionEvent.ACTION_UP && sixths != SidePanelSettings.sixths(this)) {
                    SidePanelSettings.setSixths(this, sixths)
                    appendLog("Side panel resized: CarPlay $sixths/6 of the screen")
                    endSidePanelBlur()
                    showSidePanel(true)
                } else {
                    endSidePanelBlur()
                    videoView?.let { updateVideoLayout(it.width, it.height) }
                }
                return true
            }
        }
        return false
    }

    private fun showSidePanelGripHint() {
        if (sidePanelGripAlpha < 255) animateSidePanelGrip(255, 200L)
        mainHandler.removeCallbacks(sidePanelGripFade)
        mainHandler.postDelayed(sidePanelGripFade, SIDE_PANEL_TAB_HINT_MILLIS)
    }

    private fun animateSidePanelGrip(to: Int, millis: Long) {
        sidePanelGripAnimator?.cancel()
        sidePanelGripAnimator = ValueAnimator.ofInt(sidePanelGripAlpha, to).setDuration(millis).apply {
            addUpdateListener {
                sidePanelGripAlpha = it.animatedValue as Int
                sidePanelGrip?.alpha = sidePanelGripAlpha
            }
            start()
        }
    }

    // Where the panel edge is when CarPlay has [share] of the screen, and CarPlay's share with the edge at [edge].
    private fun sidePanelEdgeAt(screen: RectF, share: Float): Float = when (sidePanelEdge) {
        Gravity.TOP -> screen.top + screen.height() * share
        Gravity.END -> screen.right - screen.width() * share
        else -> screen.left + screen.width() * share
    }

    private fun sidePanelCarPlayShare(screen: RectF, edge: Float): Float = when (sidePanelEdge) {
        Gravity.TOP -> (edge - screen.top) / screen.height()
        Gravity.END -> (screen.right - edge) / screen.width()
        else -> (edge - screen.left) / screen.width()
    }

    // TPMS as the cluster shows it: front tyres on the top row, rear on the bottom, left and right columns.
    private fun buildSidePanelTyres(): LinearLayout {
        val texts = List(4) { position ->
            TextView(this).apply {
                textSize = 24f
                setTextColor(SIDE_PANEL_PRIMARY)
                gravity = if (position % 2 == 0) Gravity.START else Gravity.END
            }
        }
        sidePanelTyreTexts = texts
        fun row(left: TextView, right: TextView) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            setPadding(0, dp(8), 0, dp(8))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(8), 0, dp(8), dp(28))
            addView(TextView(this@CarPlaySidePanel).apply {
                text = "TPMS"
                textSize = 18f
                setTextColor(SIDE_PANEL_SECONDARY)
                gravity = Gravity.CENTER
            })
            addView(row(texts[0], texts[1]))
            addView(row(texts[2], texts[3]))
        }.also { sidePanelTyres = it }
    }

    // The panel's strip of the stream, mapped through the video's layout in the window.
    fun placeSidePanel(viewWidth: Int, viewHeight: Int) {
        val panel = sidePanel ?: return
        val display = sessionDisplay ?: return
        val strip = display.viewAreas?.let { it.panelRect(it.current) } ?: return
        val content = contentRect(viewWidth, viewHeight)
        val scaleX = content.width / display.width
        val scaleY = content.height / display.height
        val left = Math.round(content.left + strip.originX * scaleX)
        val top = Math.round(content.top + strip.originY * scaleY)
        panel.layoutParams = FrameLayout.LayoutParams(
            Math.round(content.left + (strip.originX + strip.width) * scaleX) - left,
            Math.round(content.top + (strip.originY + strip.height) * scaleY) - top,
            // Stream coordinates are physical pixels, independent of the UI's reading direction.
            Gravity.TOP or Gravity.LEFT,
        ).apply { leftMargin = left; topMargin = top }
        val screen = display.viewAreas?.let { it.layoutArea(it.current) } ?: return
        sidePanelScreen = sidePanelScreenRect(viewWidth, viewHeight)
        sidePanelEdge = when {
            strip.height < screen.height -> Gravity.TOP
            strip.originX > screen.originX -> Gravity.START
            else -> Gravity.END
        }
        panel.background = sidePanelBackground()
    }

    // The whole screen CarPlay lays out by (the panel's strip included), in the window.
    private fun sidePanelScreenRect(viewWidth: Int, viewHeight: Int): RectF? {
        val display = sessionDisplay ?: return null
        val screen = display.viewAreas?.let { it.layoutArea(it.current) } ?: return null
        val content = contentRect(viewWidth, viewHeight)
        val scaleX = content.width / display.width
        val scaleY = content.height / display.height
        return RectF(content.left + screen.originX * scaleX, content.top + screen.originY * scaleY,
            content.left + (screen.originX + screen.width) * scaleX, content.top + (screen.originY + screen.height) * scaleY)
    }

    // A small pill on CarPlay's edge, a little in from the screen edge, whose own back swipe the head unit keeps.
    private fun buildSidePanelTab(): View = View(this).apply {
        visibility = View.GONE
        setOnTouchListener { view, event -> onSidePanelTabTouch(view, event) }
    }

    fun placeSidePanelTab(viewWidth: Int, viewHeight: Int) {
        val tab = sidePanelTab ?: return
        val from = sidePanelOpenEdge()
        val screen = sidePanelScreenRect(viewWidth, viewHeight)
        if (from == null || screen == null || sidePanelTabDrag != null) {
            if (sidePanelTabDrag == null) tab.visibility = View.GONE
            return
        }
        val across = from != Gravity.BOTTOM
        val reach = dp(64)
        val length = dp(160)
        if (from != sidePanelTabFrom) tab.background = sidePanelTabPill(from).apply { alpha = if (sidePanelTabHint) 255 else 0 }
        sidePanelTabFrom = from
        val areas = sessionDisplay?.viewAreas
        if (areas !== sidePanelTabHintFor) {
            sidePanelTabHintFor = areas
            showSidePanelTabHint()
        }
        val params = FrameLayout.LayoutParams(if (across) reach else length, if (across) length else reach,
            Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = Math.round(when (from) {
                Gravity.BOTTOM -> screen.centerX() - length / 2f
                Gravity.LEFT -> screen.left
                else -> screen.right - reach
            })
            topMargin = Math.round(if (across) screen.centerY() - length / 2f else screen.bottom - reach)
        }
        val old = tab.layoutParams as? FrameLayout.LayoutParams
        if (old == null || old.width != params.width || old.height != params.height ||
            old.leftMargin != params.leftMargin || old.topMargin != params.topMargin) tab.layoutParams = params
        tab.visibility = View.VISIBLE
    }

    private fun showSidePanelTabHint() {
        sidePanelTabHint = true
        sidePanelTab?.background?.let { pill ->
            if (pill.alpha < 255) ObjectAnimator.ofInt(pill, "alpha", pill.alpha, 255).setDuration(200L).start()
        }
        mainHandler.removeCallbacks(sidePanelTabFade)
        mainHandler.postDelayed(sidePanelTabFade, SIDE_PANEL_TAB_HINT_MILLIS)
    }

    private fun sidePanelTabPill(from: Int): Drawable {
        val across = from != Gravity.BOTTOM
        val pill = GradientDrawable().apply {
            setColor(0xDDFFFFFF.toInt())
            setStroke(dp(1), 0x66000000)
            cornerRadius = dp(4).toFloat()
            setSize(if (across) dp(8) else dp(88), if (across) dp(88) else dp(8))
        }
        return LayerDrawable(arrayOf(pill)).apply {
            setLayerGravity(0, when (from) {
                Gravity.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                Gravity.LEFT -> Gravity.LEFT or Gravity.CENTER_VERTICAL
                else -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
            })
            when (from) {
                Gravity.BOTTOM -> setLayerInsetBottom(0, dp(20))
                Gravity.LEFT -> setLayerInsetLeft(0, dp(20))
                else -> setLayerInsetRight(0, dp(20))
            }
        }
    }

    /**
     * The tab follows the finger out as the panel itself; on release CarPlay moves to the declared share nearest to
     * where it was let go, and nothing happens when it was let go nearer the screen edge. A tap shows the pill again.
     */
    private fun onSidePanelTabTouch(tab: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            sidePanelTabToCarPlay = false
            dropHeldSidePanelTabTouch()
        }
        if (sidePanelTabToCarPlay) {
            forwardSidePanelTabTouch(tab, event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                sidePanelTabToCarPlay = false
            }
            return true
        }
        val panel = sidePanel ?: return false
        val vertical = sidePanelOpenEdge() == Gravity.BOTTOM
        val position = if (vertical) tab.y + event.y else tab.x + event.x
        val across = if (vertical) tab.x + event.x else tab.y + event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val from = sidePanelOpenEdge() ?: return false
                val view = videoView ?: return false
                sidePanelScreen = sidePanelScreenRect(view.width, view.height) ?: return false
                sidePanelEdge = when (from) {
                    Gravity.BOTTOM -> Gravity.TOP
                    Gravity.LEFT -> Gravity.END
                    else -> Gravity.START
                }
                // The panel comes out from the left towards the right, otherwise towards smaller x or y.
                sidePanelTabDrag = floatArrayOf(position, 0f, across, if (from == Gravity.LEFT) 1f else -1f)
                holdSidePanelTabTouch(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val drag = sidePanelTabDrag ?: return false
                val screen = sidePanelScreen ?: return false
                if (drag[1] == 0f) {
                    when (sidePanelTabIntent((position - drag[0]) * drag[3], Math.abs(across - drag[2]), dp(8).toFloat())) {
                        SidePanelTabIntent.UNDECIDED -> {
                            holdSidePanelTabTouch(event)
                            return true
                        }
                        SidePanelTabIntent.CARPLAY -> {
                            handSidePanelTabTouchToCarPlay(tab, event)
                            return true
                        }
                        SidePanelTabIntent.OPEN_PANEL -> dropHeldSidePanelTabTouch()
                    }
                }
                drag[1] = 1f
                tab.alpha = 0f
                startSidePanelBlur()
                val quarter = sidePanelEdgeAt(screen, 0.25f)
                val whole = sidePanelEdgeAt(screen, 1f)
                val edge = position.coerceIn(minOf(quarter, whole), maxOf(quarter, whole))
                panel.alpha = if (sidePanelCarPlayShare(screen, edge) > SIDE_PANEL_CLOSE_SHARE) 0.5f else 1f
                panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
                    gravity = Gravity.TOP or Gravity.LEFT
                    when (sidePanelEdge) {
                        Gravity.TOP -> { leftMargin = Math.round(screen.left); width = Math.round(screen.width()); topMargin = Math.round(edge); height = Math.round(screen.bottom - edge) }
                        Gravity.END -> { leftMargin = Math.round(screen.left); width = Math.round(edge - screen.left); topMargin = Math.round(screen.top); height = Math.round(screen.height()) }
                        else -> { leftMargin = Math.round(edge); width = Math.round(screen.right - edge); topMargin = Math.round(screen.top); height = Math.round(screen.height()) }
                    }
                }
                if (panel.visibility != View.VISIBLE) {
                    panel.background = sidePanelBackground()
                    refreshSidePanel()
                    panel.visibility = View.VISIBLE
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // More fingers never drag the panel (the settings swipe, a pinch): they go to CarPlay.
                if (sidePanelTabDrag?.get(1) == 0f) handSidePanelTabTouchToCarPlay(tab, event)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val drag = sidePanelTabDrag ?: return false
                if (drag[1] == 0f) {
                    // Not a drag: a tap goes to CarPlay under the tab and shows the pill again.
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        handSidePanelTabTouchToCarPlay(tab, event)
                        sidePanelTabToCarPlay = false
                        showSidePanelTabHint()
                    } else {
                        dropHeldSidePanelTabTouch()
                        sidePanelTabDrag = null
                    }
                    return true
                }
                sidePanelTabDrag = null
                panel.alpha = 1f
                tab.alpha = 1f
                val screen = sidePanelScreen
                val carPlay = if (screen != null) sidePanelCarPlayShare(screen, position) else null
                if (event.actionMasked == MotionEvent.ACTION_UP && carPlay != null && carPlay <= SIDE_PANEL_CLOSE_SHARE) {
                    SidePanelSettings.setSixths(this, CarPlayViewAreas.nearestSidePanelSixths(carPlay.toDouble()))
                    appendLog("Side panel opened from its tab: CarPlay ${SidePanelSettings.sixths(this)}/6 of the screen")
                    showSidePanel(true)
                }
                endSidePanelBlur()
                if (!sidePanelShown) panel.visibility = View.GONE
                videoView?.let { updateVideoLayout(it.width, it.height) }
                return true
            }
        }
        return false
    }

    private fun holdSidePanelTabTouch(event: MotionEvent) {
        if (sidePanelTabHeld.isEmpty()) {
            mainHandler.postDelayed(sidePanelTabLongPress, ViewConfiguration.getLongPressTimeout().toLong())
        }
        sidePanelTabHeld += MotionEvent.obtain(event)
    }

    private fun dropHeldSidePanelTabTouch() {
        mainHandler.removeCallbacks(sidePanelTabLongPress)
        sidePanelTabHeld.forEach(MotionEvent::recycle)
        sidePanelTabHeld.clear()
    }

    /** The touch on the tab is not a drag: CarPlay gets what it held back, [event], and the rest of the touch. */
    private fun handSidePanelTabTouchToCarPlay(tab: View, event: MotionEvent? = null) {
        mainHandler.removeCallbacks(sidePanelTabLongPress)
        sidePanelTabHeld.forEach { held ->
            forwardSidePanelTabTouch(tab, held)
            held.recycle()
        }
        sidePanelTabHeld.clear()
        event?.let { forwardSidePanelTabTouch(tab, it) }
        sidePanelTabDrag = null
        sidePanelTabToCarPlay = true
    }

    /** Delivers a tab touch to the CarPlay touch layer under the tab, in that layer's coordinates. */
    private fun forwardSidePanelTabTouch(tab: View, event: MotionEvent) {
        val layer = gestureOverlay ?: return
        val forwarded = MotionEvent.obtain(event)
        forwarded.offsetLocation(tab.x - layer.x, tab.y - layer.y)
        try {
            onHostTouch(layer, forwarded)
        } finally {
            forwarded.recycle()
        }
    }

    /**
     * Blurs CarPlay and the panel's contents while the panel edge moves, as CarPlay blurs apps whose size changes:
     * CarPlay's last picture, copied small and blurred, covers the video until the finger lifts.
     * Android 12 and later only (RenderEffect).
     */
    private fun startSidePanelBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || sidePanelBlurring) return
        val overlay = sidePanelBlur ?: return
        val view = videoView ?: return
        sidePanelBlurring = true
        sidePanelBlurActive = true
        val generation = ++sidePanelBlurGeneration
        mainHandler.removeCallbacks(sidePanelBlurEnd)
        overlay.animate().cancel()
        val radius = dp(24).toFloat()
        val contents = dp(32).toFloat()
        sidePanelContents?.setRenderEffect(
            RenderEffect.createBlurEffect(contents, contents, Shader.TileMode.DECAL))
        val content = contentRect(view.width, view.height)
        val width = (content.width / 4).toInt().coerceAtLeast(1)
        val height = (content.height / 4).toInt().coerceAtLeast(1)
        val show = { picture: Bitmap ->
            if (generation == sidePanelBlurGeneration && sidePanelBlurActive) {
                overlay.setImageBitmap(picture)
                overlay.layoutParams = FrameLayout.LayoutParams(Math.round(content.width), Math.round(content.height),
                    Gravity.TOP or Gravity.LEFT).apply {
                    leftMargin = Math.round(content.left)
                    topMargin = Math.round(content.top)
                }
                overlay.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
                overlay.alpha = 1f
                overlay.visibility = View.VISIBLE
            }
        }
        val surface = fallbackVideoView
        if (surface != null) {
            val picture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            runCatching {
                PixelCopy.request(surface, picture, { result ->
                    if (result == PixelCopy.SUCCESS) show(picture)
                }, mainHandler)
            }
        } else {
            (view as? TextureView)?.getBitmap(width, height)?.let(show)
        }
    }

    // Lifts the blur when the finger lifts; CarPlay then blurs its own picture while it changes size.
    private fun endSidePanelBlur() {
        if (!sidePanelBlurring) return
        sidePanelBlurring = false
        mainHandler.removeCallbacks(sidePanelBlurEnd)
        mainHandler.post(sidePanelBlurEnd)
    }

    fun showSidePanel(show: Boolean) {
        val areas = sessionDisplay?.viewAreas ?: return
        val view = videoView ?: return
        val split = isMultiWindowActive()
        val portrait = if (split) screenPortrait() else view.height > view.width
        val target = if (show) areas.sidePanel(portrait, SidePanelSettings.sixths(this)) else areas.indexFor(view.width, view.height, split, portrait)
        val sent = target != null && showViewArea(target)
        appendLog("Side panel ${if (show) "shown" else "hidden"}: view area $target sent=$sent")
        if (!sent || target == null) return
        areas.use(target)
        sidePanelShown = show
        sidePanel?.visibility = if (show) View.VISIBLE else View.GONE
        mainHandler.removeCallbacks(sidePanelTick)
        if (show) mainHandler.post(::refreshSidePanel)
        if (show) sidePanelTick.run()
        if (show) showSidePanelGripHint()
        updateVideoLayout(view.width, view.height)
    }

    fun resetSidePanel() {
        sidePanelShown = false
        sidePanel?.visibility = View.GONE
        mainHandler.removeCallbacks(sidePanelTick)
        videoView?.let { placeSidePanelTab(it.width, it.height) }
    }

    // Stops the hints and the blur of a host that goes away.
    fun releaseSidePanelEffects() {
        dropHeldSidePanelTabTouch()
        sidePanelTabToCarPlay = false
        mainHandler.removeCallbacks(sidePanelGripFade)
        mainHandler.removeCallbacks(sidePanelTabFade)
        mainHandler.removeCallbacks(sidePanelBlurEnd)
        sidePanelGripAnimator?.cancel()
        sidePanelBlurring = false
        sidePanelBlurActive = false
        sidePanelBlur?.animate()?.cancel()
        sidePanelBlur?.setImageDrawable(null)
    }

    // The screen edge the side panel would take now (where its tab is), or null when it is shown or unavailable.
    private fun sidePanelOpenEdge(): Int? {
        if (sidePanelShown) return null
        val areas = sessionDisplay?.viewAreas ?: return null
        val view = videoView ?: return null
        val portrait = if (isMultiWindowActive()) screenPortrait() else view.height > view.width
        val index = areas.sidePanel(portrait, SidePanelSettings.sixths(this)) ?: return null
        return when {
            portrait -> Gravity.BOTTOM
            areas.areas[index].originX > 0 -> Gravity.LEFT
            else -> Gravity.RIGHT
        }
    }

    // The vehicle readouts came from a vendor autoservice; this head unit exposes none of them.
    private fun refreshSidePanel() {
        sidePanelBattery?.text = ""
        sidePanelTyres?.visibility = View.GONE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val SIDE_PANEL_REFRESH_MILLIS = 5_000L
        /** Close when the edge is past halfway from two thirds to all of the screen. */
        const val SIDE_PANEL_CLOSE_SHARE = 5f / 6f
        const val SIDE_PANEL_TAB_HINT_MILLIS = 10_000L
    }
}
