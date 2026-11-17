package com.ether4o4.odcm.control

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.widget.ImageView
import kotlin.math.max

/** Fill any panel shape while keeping the bundled scene's character in frame. */
internal class AvatarView(context: Context) : ImageView(context) {
    var focusX = .5f
        set(value) { field = value.coerceIn(0f, 1f); crop() }
    init { scaleType = ScaleType.MATRIX }
    override fun setImageDrawable(drawable: Drawable?) { super.setImageDrawable(drawable); crop() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); crop() }
    private fun crop() {
        val d = drawable ?: return
        if (width <= 0 || height <= 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        val scale = max(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        val scaledWidth = d.intrinsicWidth * scale
        val scaledHeight = d.intrinsicHeight * scale
        val dx = (width / 2f - scaledWidth * focusX).coerceIn(minOf(0f, width - scaledWidth), 0f)
        val dy = (height - scaledHeight) / 2f
        imageMatrix = Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) }
    }
}
