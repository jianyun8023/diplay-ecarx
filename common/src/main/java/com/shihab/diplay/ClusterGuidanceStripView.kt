package com.shihab.diplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View

/**
 * An iOS 26/27 "liquid glass" bar for the cluster's guidance line, drawn over the projected map.
 *
 * Android 9 gives a window no way to blur what is behind one of its views — `RenderEffect` is API
 * 31 and taking a bitmap of the video texture every frame would stall the decoder — so the glass is
 * layered translucency rather than a real backdrop blur: a vertical wash that darkens towards the
 * bottom edge where light bends in the glass, a diagonal sheen, a rim that fades towards the
 * bottom-right, and a soft drop shadow that separates the bar from the map. Two palettes keep
 * Apple's rule that glass follows the appearance: light glass with dark ink by day, dark glass with
 * white ink at night.
 *
 * Everything is sized from the panel width, because the cluster window is a second display whose
 * density does not describe its 1920×720 panel. Geometry and shaders are rebuilt when the content,
 * the appearance or the view size changes, never inside [onDraw]: the strip is redrawn once a
 * second, but the cluster shares its GPU with the map's decoder.
 */
internal class ClusterGuidanceStripView(context: Context) : View(context) {
    var content: ClusterGuidanceStrip.Line? = null
        set(value) {
            if (field == value) return
            field = value
            rebuild()
            invalidate()
        }

    /** The appearance the strip is drawn in; the cluster's map follows the head unit's day/night. */
    var night: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            rebuild()
            invalidate()
        }

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val drop = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()
    private val layer = RectF()
    private var label = ""
    private var baseline = 0f
    private var radius = 0f
    private var unit = 1f

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        rebuild()
    }

    private fun rebuild() {
        val line = content ?: return
        if (width <= 0 || height <= 0) return
        unit = width / PANEL_WIDTH
        val palette = if (night) NIGHT_GLASS else DAY_GLASS
        text.color = palette.ink
        text.textSize = TEXT_SIZE * unit
        label = line.text
        val pad = HORIZONTAL_PADDING * unit
        val barHeight = BAR_HEIGHT * unit
        radius = barHeight / 2f
        val right = width - END_MARGIN * unit
        val bottom = height - BOTTOM_MARGIN * unit
        val widthPx = text.measureText(label) + 2 * pad
        bar.set(right - widthPx, bottom - barHeight, right, bottom)
        val metrics = text.fontMetrics
        baseline = bar.centerY() - (metrics.ascent + metrics.descent) / 2f

        wash.shader = LinearGradient(
            bar.left, bar.top, bar.left, bar.bottom,
            intArrayOf(palette.washTop, palette.washMid, palette.washBottom),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        sheen.shader = LinearGradient(
            bar.left, bar.top, bar.left + bar.width() * 0.65f, bar.top + bar.height() * 0.9f,
            palette.sheen, 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
        rim.shader = LinearGradient(
            bar.left, bar.top, bar.right, bar.bottom,
            palette.rimNear, palette.rimFar, Shader.TileMode.CLAMP,
        )
        rim.strokeWidth = 1.5f * unit
    }

    override fun onDraw(canvas: Canvas) {
        if (content == null || bar.isEmpty) return

        // A real shadow needs a blur; three widening translucent rects read the same at this size.
        for (step in SHADOW_STEPS downTo 1) {
            val grow = step * 2.4f * unit
            layer.set(bar)
            layer.inset(-grow, -grow)
            layer.offset(0f, SHADOW_OFFSET * unit)
            drop.color = SHADOW_ALPHA[step - 1] shl 24
            canvas.drawRoundRect(layer, radius + grow, radius + grow, drop)
        }

        canvas.drawRoundRect(bar, radius, radius, wash)
        canvas.drawRoundRect(bar, radius, radius, sheen)
        canvas.drawRoundRect(bar, radius, radius, rim)
        canvas.drawText(label, bar.left + HORIZONTAL_PADDING * unit, baseline, text)
    }

    private class Palette(
        val washTop: Int,
        val washMid: Int,
        val washBottom: Int,
        val sheen: Int,
        val rimNear: Int,
        val rimFar: Int,
        val ink: Int,
    )

    private companion object {
        /**
         * The panel the strip is laid out against; the window is 1920×720 on the tested car.
         *
         * Panel pixels, not dp: this is a second display whose density does not describe its
         * geometry, so every size below is a fraction of the panel width and scales with it.
         */
        const val PANEL_WIDTH = 1920f
        const val BAR_HEIGHT = 54f
        const val HORIZONTAL_PADDING = 30f
        const val TEXT_SIZE = 29f
        /** Clear of the cluster's own bottom edge, where the factory bar keeps its readouts. */
        const val END_MARGIN = 42f
        const val BOTTOM_MARGIN = 26f
        const val SHADOW_STEPS = 3
        const val SHADOW_OFFSET = 5f
        /** Nearest layer darkest; the outermost one is only a hint. */
        val SHADOW_ALPHA = intArrayOf(0x14, 0x0E, 0x08)

        val DAY_GLASS = Palette(
            washTop = 0x66FFFFFF,
            washMid = 0x3AFFFFFF,
            washBottom = 0x22FFFFFF,
            sheen = 0x40FFFFFF,
            rimNear = 0xFFFFFFFF.toInt(),
            rimFar = 0x26FFFFFF,
            ink = 0xF00B0B0F.toInt(),
        )

        val NIGHT_GLASS = Palette(
            washTop = 0x4D000000,
            washMid = 0x38000000,
            washBottom = 0x4D000000,
            sheen = 0x24FFFFFF,
            rimNear = 0xB8FFFFFF.toInt(),
            rimFar = 0x1FFFFFFF,
            ink = 0xF2FFFFFF.toInt(),
        )
    }
}
