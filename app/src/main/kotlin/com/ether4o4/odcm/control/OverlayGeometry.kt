package com.ether4o4.odcm.control

/** Saved geometry never changes during a temporary keyboard fit. */
internal data class OverlayGeometry(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun fit(spaceWidth: Int, spaceHeight: Int): OverlayGeometry {
        val w = width.coerceIn(1, spaceWidth.coerceAtLeast(1))
        val h = height.coerceIn(1, spaceHeight.coerceAtLeast(1))
        return OverlayGeometry(x.coerceIn(0, (spaceWidth - w).coerceAtLeast(0)),
            y.coerceIn(0, (spaceHeight - h).coerceAtLeast(0)), w, h)
    }
    fun resize(dx: Int, dy: Int, spaceWidth: Int, spaceHeight: Int, minWidth: Int, minHeight: Int): OverlayGeometry {
        val maxW = (spaceWidth - x).coerceAtLeast(1)
        val maxH = (spaceHeight - y).coerceAtLeast(1)
        return copy(width = (width + dx).coerceIn(minOf(minWidth, maxW), maxW),
            height = (height + dy).coerceIn(minOf(minHeight, maxH), maxH))
    }
}
