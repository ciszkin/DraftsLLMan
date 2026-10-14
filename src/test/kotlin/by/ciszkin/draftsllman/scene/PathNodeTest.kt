package by.ciszkin.draftsllman.scene

import kotlin.test.Test
import kotlin.test.assertEquals

class PathNodeTest {

    @Test
    fun `computeQuadraticControl is the chord midpoint when tension is zero`() {
        // Flat: tension 0.0 collapses the bow, so the control sits on the chord midpoint.
        assertEquals(50.0 to 0.0, computeQuadraticControl(0.0 to 0.0, 100.0 to 0.0, tension = 0.0))
    }

    @Test
    fun `computeQuadraticControl bows perpendicular to the chord for positive tension`() {
        // Chord runs along +X; the displacement is purely perpendicular (+Y), so x stays at the
        // midpoint and y grows with tension. At tension 1.0 the offset is half the chord length.
        val (cx, cy) = computeQuadraticControl(0.0 to 0.0, 100.0 to 0.0, tension = 1.0)
        assertEquals(50.0, cx, "no chord-parallel drift (x stays at midpoint)")
        assertEquals(50.0, cy, "perpendicular displacement = tension/2 * chord length")
    }
}
