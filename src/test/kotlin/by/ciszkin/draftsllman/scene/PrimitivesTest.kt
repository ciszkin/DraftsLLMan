package by.ciszkin.draftsllman.scene

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PrimitivesTest {
    @Test fun `ResolvedBox derives anchors with top-left convention`() {
        val b = ResolvedBox(x = 10.0, y = 20.0, width = 30.0, height = 40.0)
        assertEquals(25.0, b.cx)
        assertEquals(40.0, b.cy)
        assertEquals(10.0, b.left)
        assertEquals(40.0, b.right)
        assertEquals(20.0, b.top)
        assertEquals(60.0, b.bottom)
    }

    @Test fun `Color toCss includes leading hash and round-trips`() {
        val c = Color.rgb(0xFF, 0x00, 0x80)
        assertEquals("#FF0080", c.toCss())
        assertEquals(c, Color.fromCss("#FF0080"))
        assertEquals(c, Color.fromCss("#ff0080"))
    }

    @Test fun `Color is opaque-only`() {
        assertFailsWith<IllegalArgumentException> { Color(0x80FF0000.toInt()) }
    }

    @Test fun `Anchor axis classifies X, Y, POINT`() {
        assertEquals(AnchorAxis.X, Anchor.LEFT.axis)
        assertEquals(AnchorAxis.Y, Anchor.BOTTOM.axis)
        assertEquals(AnchorAxis.POINT, Anchor.CENTER.axis)
        assertEquals(AnchorAxis.POINT, Anchor.TOP_LEFT.axis)
    }

    @Test fun `ResolvedBox translate shifts x and y, keeps size`() {
        val moved = ResolvedBox(10.0, 20.0, 30.0, 40.0).translate(5.0, 7.0)
        assertEquals(ResolvedBox(15.0, 27.0, 30.0, 40.0), moved)
    }

    @Test fun `mid-edge anchors are POINT`() {
        assertEquals(AnchorAxis.POINT, Anchor.TOP_CENTER.axis)
        assertEquals(AnchorAxis.POINT, Anchor.BOTTOM_CENTER.axis)
        assertEquals(AnchorAxis.POINT, Anchor.LEFT_CENTER.axis)
        assertEquals(AnchorAxis.POINT, Anchor.RIGHT_CENTER.axis)
    }
}
