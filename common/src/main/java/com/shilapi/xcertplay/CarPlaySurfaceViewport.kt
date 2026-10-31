package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.media.CarPlayVideoLayout

/**
 * Measure and place the compositor surface and its retained picture together. Never change a child's
 * LayoutParams from an OnLayoutChangeListener: that requests a second layout during the first one.
 * The layout provider also drives the host's touch mapping and black-corner overlay.
 */
internal class CarPlaySurfaceViewport(
    context: Context,
    private val content: (Int, Int) -> CarPlayVideoLayout,
    private val laidOut: (Int, Int) -> Unit,
) : FrameLayout(context) {
    private var measuredBounds: CarPlaySurfaceBounds? = null
    private var notifiedBounds: CarPlaySurfaceBounds? = null
    private var notifiedWidth = -1
    private var notifiedHeight = -1

    fun updateContent() {
        if (width <= 0 || height <= 0) return
        val next = CarPlaySurfaceBounds.from(content(width, height))
        if (next != measuredBounds) requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val height = View.MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val bounds = CarPlaySurfaceBounds.from(content(width, height))
        measuredBounds = bounds
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility != View.GONE) {
                child.measure(View.MeasureSpec.makeMeasureSpec(bounds.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(bounds.height, View.MeasureSpec.EXACTLY))
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val bounds = CarPlaySurfaceBounds.from(content(right - left, bottom - top))
        measuredBounds = bounds
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility != View.GONE) {
                child.layout(bounds.left, bounds.top, bounds.left + bounds.width, bounds.top + bounds.height)
            }
        }
        if (notifiedBounds != bounds || notifiedWidth != right - left || notifiedHeight != bottom - top) {
            notifiedBounds = bounds
            notifiedWidth = right - left
            notifiedHeight = bottom - top
            laidOut(notifiedWidth, notifiedHeight)
        }
    }
}
