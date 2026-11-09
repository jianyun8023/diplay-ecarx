// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import android.view.Gravity
import android.widget.*
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

internal const val VALUE_SEPARATOR = " · "

internal class SettingsSearchEditText(context: android.content.Context) : EditText(context) {
    private val glyphBounds = android.graphics.Rect()
    private var measuredHint: String? = null
    private var measuredHintHeight = -1
    private var measuredHintTextSize = -1f
    private var measuredHintBaseline = 0

    override fun onDraw(canvas: android.graphics.Canvas) {
        val value: CharSequence = if (text.isNullOrEmpty()) hint ?: "" else text
        val drawingBaseline = if (text.isNullOrEmpty() && value.isNotEmpty()) hintBaseline(value) else baseline
        val offset = if (baseline >= 0) {
            val contentCentre = paddingTop + (height - paddingTop - paddingBottom) / 2f
            contentCentre - alignedTextCentreY(this, value, glyphBounds, drawingBaseline)
        } else 0f
        // Text, selection and cursor move together.
        val checkpoint = canvas.save()
        canvas.translate(0f, offset)
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    /**
     * TextView.baseline describes the editable layout, even while it draws a hint, and a hint in
     * a fallback font can sit differently. Measured once per hint, height and text size rather
     * than on every frame of the blinking cursor.
     */
    private fun hintBaseline(value: CharSequence): Int {
        if (value.toString() != measuredHint || height != measuredHintHeight || paint.textSize != measuredHintTextSize) {
            // The editor scrolls horizontally rather than wrapping: lay the hint out on one line.
            val hintWidth = maxOf(
                (width - compoundPaddingLeft - compoundPaddingRight).coerceAtLeast(1),
                kotlin.math.ceil(android.text.Layout.getDesiredWidth(value, paint).toDouble()).toInt(),
            )
            val hintLayout = android.text.StaticLayout.Builder.obtain(value, 0, value.length, paint, hintWidth)
                .setMaxLines(1).setIncludePad(includeFontPadding).apply {
                    if (Build.VERSION.SDK_INT >= 28) setUseLineSpacingFromFallbacks(isFallbackLineSpacing)
                }.build()
            measuredHintBaseline = extendedPaddingTop +
                ((height - extendedPaddingTop - extendedPaddingBottom - hintLayout.height).coerceAtLeast(0) / 2) +
                hintLayout.getLineBaseline(0)
            measuredHint = value.toString()
            measuredHintHeight = height
            measuredHintTextSize = paint.textSize
        }
        return measuredHintBaseline
    }
}

internal class HeaderTitleView(context: android.content.Context) : TextView(context) {
    private val glyphBounds = android.graphics.Rect()

    override fun onDraw(canvas: android.graphics.Canvas) {
        val value = text.toString()
        paint.getTextBounds(value, 0, value.length, glyphBounds)
        val checkpoint = canvas.save()
        if (layout?.lineCount == 1 && !glyphBounds.isEmpty && baseline >= 0) {
            val textCentre = alignedTextCentreY(this, value, glyphBounds)
            val contentCentre = paddingTop + (height - paddingTop - paddingBottom) / 2f
            canvas.translate(0f, contentCentre - textCentre)
        }
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }
}

/**
 * Text in the "Title · Value" form opens a choice, so it reads as a setting row:
 * start-aligned, muted value, chevron. Any other text stays a centred action button.
 * The plain text is unchanged, so callers and tests keep matching "Title · Value".
 */
internal class SettingButton(
    context: android.content.Context,
    private val muted: Int,
    private val accent: Int,
) : Button(context) {
    var action = false
    var contentOffsetY = 0f
    var alignVisibleGlyphs = true
    private val glyphBounds = android.graphics.Rect()

    fun textAlignmentCentreY(): Float {
        return alignedTextCentreY(this, text, glyphBounds)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        var offset = contentOffsetY
        if (alignVisibleGlyphs) {
            offset = 0f
            // Wrapped labels keep Android's multiline layout. Measure single-line labels
            // on every draw so changing a setting value also updates its visual centre.
            if (layout?.lineCount == 1 && baseline >= 0) {
                val textCentre = textAlignmentCentreY()
                val contentCentre = paddingTop + (height - paddingTop - paddingBottom) / 2f
                offset = contentCentre - textCentre
            }
            // The chevron is already centred by TextView; compensate for the text shift.
            compoundDrawablesRelative.filterNotNull().forEach { drawable ->
                val bounds = drawable.bounds
                val top = -offset.roundToInt()
                drawable.setBounds(bounds.left, top, bounds.right, top + bounds.height())
            }
        }
        val checkpoint = canvas.save()
        canvas.translate(0f, offset)
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        val split = if (action) -1 else text?.indexOf(VALUE_SEPARATOR) ?: -1
        if (text == null || split <= 0) {
            super.setText(text, type)
            gravity = Gravity.CENTER
            setCompoundDrawablesRelative(null, null, null, null)
            return
        }
        val styled = android.text.SpannableString(text).apply {
            setSpan(android.text.style.ForegroundColorSpan(muted), split, text.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        super.setText(styled, BufferType.SPANNABLE)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
            context.getDrawable(R.drawable.ic_dp_chevron)?.mutate()?.apply { setTint(accent) }, null)
    }
}
private fun alignedTextCentreY(
    view: TextView,
    text: CharSequence,
    bounds: android.graphics.Rect,
    drawingBaseline: Int = view.baseline,
): Float {
    if (usesChineseGlyphAlignment(view.resources.configuration.locales[0].language, text)) {
        val value = text.toString()
        view.paint.getTextBounds(value, 0, value.length, bounds)
        return drawingBaseline + (bounds.top + bounds.bottom) / 2f
    }
    // Keep the baseline stable when labels contain descenders or accents.
    val metrics = view.paint.fontMetrics
    return drawingBaseline + (metrics.ascent + metrics.descent) / 2f
}

internal fun usesChineseGlyphAlignment(language: String, text: CharSequence): Boolean {
    return language == "zh" && text.isNotBlank() &&
        text.none { it in 'A'..'Z' || it in 'a'..'z' }
}
