package com.ether4o4.odcm.control

import org.junit.Assert.*
import org.junit.Test

class OverlayGeometryTest {
    @Test fun resizingAnchorsTopLeftInAllDirections() {
        val g = OverlayGeometry(45, 80, 310, 490)
        for ((dx, dy) in listOf(60 to 90, -60 to -90, 70 to -50, -30 to 90, 0 to 100, 70 to 0)) {
            val r = g.resize(dx, dy, 1000, 1600, 200, 200)
            assertEquals(g.x, r.x); assertEquals(g.y, r.y)
            assertEquals(g.width + dx, r.width); assertEquals(g.height + dy, r.height)
        }
    }
    @Test fun resizeCannotCrossScreenEdgesOrMinimumDimensions() {
        val g = OverlayGeometry(45, 80, 310, 490)
        val large = g.resize(9999, 9999, 500, 900, 200, 200)
        assertEquals(455, large.width); assertEquals(820, large.height)
        val small = g.resize(-9999, -9999, 500, 900, 200, 200)
        assertEquals(200, small.width); assertEquals(200, small.height)
    }
    @Test fun temporaryKeyboardFitDoesNotOverwriteSavedBounds() {
        val saved = OverlayGeometry(40, 500, 310, 490)
        val keyboard = saved.fit(400, 600)
        assertTrue(keyboard.y + keyboard.height <= 600)
        assertEquals(OverlayGeometry(40, 500, 310, 490), saved.fit(400, 1100))
        val landscape = saved.fit(900, 180)
        assertTrue(landscape.y + landscape.height <= 180)
    }
}
