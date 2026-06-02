package com.vixcy.mango

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import android.view.View

/**
 * A [SurfaceView] that can be adjusted to a specified aspect ratio.
 * 
 * SurfaceView is superior to TextureView for 60fps performance because it
 * uses a dedicated hardware layer, bypassing the UI thread's rendering latency.
 */
class AutoFitSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : SurfaceView(context, attrs, defStyle) {

    private var ratioWidth = 0
    private var ratioHeight = 0

    fun setAspectRatio(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            throw IllegalArgumentException("Size must be positive.")
        }
        if (ratioWidth == width && ratioHeight == height) return
        ratioWidth = width
        ratioHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val height = View.MeasureSpec.getSize(heightMeasureSpec)
        if (0 == ratioWidth || ratioHeight == 0) {
            setMeasuredDimension(width, height)
        } else {
            // Aspect-fit the viewfinder inside its parent so the selected recording
            // ratio changes the preview frame itself instead of zooming the content.
            if (width > height * ratioWidth / ratioHeight) {
                setMeasuredDimension(height * ratioWidth / ratioHeight, height)
            } else {
                setMeasuredDimension(width, width * ratioHeight / ratioWidth)
            }
        }
    }
}
