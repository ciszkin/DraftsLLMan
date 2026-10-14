package by.ciszkin.draftsllman.scene

import by.ciszkin.draftsllman.scene.svg.toSvgCoord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SceneNodeTest {
    private val box = ResolvedBox(10.0, 20.0, 30.0, 40.0)
    private val red = Style(fill = Color.rgb(255, 0, 0))

    @Test fun `circle emits id data-role cx cy r from box`() {
        val c = CircleNode("hub", "wheel", box, red)
        assertEquals(
            """<circle id="hub" data-role="wheel" cx="25.00" cy="40.00" r="15.00" fill="#FF0000"/>""",
            c.toSvgString(),
        )
    }

    @Test fun `rect emits rounded corners when set`() {
        val r = RectNode("frame", "frame", box, Style(fill = Color.rgb(0, 0, 255), cornerRadius = 5.0))
        assertEquals(
            """<rect id="frame" data-role="frame" x="10.00" y="20.00" width="30.00" height="40.00" rx="5.00" fill="#0000FF"/>""",
            r.toSvgString(),
        )
    }

    @Test fun `line emits explicit start and end endpoints`() {
        val l = LineNode("bar", "frame", start = 10.0 to 20.0, end = 40.0 to 60.0, style = Style(stroke = Color.BLACK))
        assertEquals(
            """<line id="bar" data-role="frame" x1="10.00" y1="20.00" x2="40.00" y2="60.00" stroke="#000000" stroke-width="1.00"/>""",
            l.toSvgString(),
        )
    }

    @Test fun `forward diagonal line renders un-mirrored (not forced to min-corner to max-corner)`() {
        // A "/" diagonal: start at bottom-left, end at top-right. The old box-only model flipped
        // this to "\" (x1/y1=min corner, x2/y2=max corner). Explicit endpoints must preserve slope.
        val l = LineNode("diag", "edge", start = 10.0 to 60.0, end = 40.0 to 20.0)
        assertEquals(
            """<line id="diag" data-role="edge" x1="10.00" y1="60.00" x2="40.00" y2="20.00"/>""",
            l.toSvgString(),
        )
        // box is the tight bbox of the two endpoints; it carries no slope info of its own.
        assertEquals(ResolvedBox(10.0, 20.0, 30.0, 40.0), l.box)
    }

    @Test fun `line withBox translates both endpoints by the origin delta`() {
        val l = LineNode("diag", "edge", start = 10.0 to 60.0, end = 40.0 to 20.0)
        val moved = l.withBox(l.box.translate(5.0, 5.0)) as LineNode
        assertEquals(15.0 to 65.0, moved.start)
        assertEquals(45.0 to 25.0, moved.end)
        assertEquals(ResolvedBox(15.0, 25.0, 30.0, 40.0), moved.box)
    }

    // ---- withBox affine box-map (spec 2026-08-18) ----

    @Test fun `withBox returns the same instance for an identical box`() {
        val l = LineNode("bar", "frame", start = 10.0 to 20.0, end = 40.0 to 60.0)
        assertSame(l, l.withBox(l.box))
    }

    @Test fun `withBox stretch maps opposite corners into the new box`() {
        // Declared diagonal (0,0)->(150,10) stretched into solved box [55,100,275,10]:
        // endpoints land on the new box's opposite corners; the derived box == the new box.
        val l = LineNode("frame", "frame", start = 0.0 to 0.0, end = 150.0 to 10.0)
        val stretched = l.withBox(ResolvedBox(55.0, 100.0, 275.0, 10.0)) as LineNode
        assertEquals(55.0 to 100.0, stretched.start)
        assertEquals(330.0 to 110.0, stretched.end)
        assertEquals(ResolvedBox(55.0, 100.0, 275.0, 10.0), stretched.box)
    }

    @Test fun `withBox stretch preserves a backslash slope (corner identity, not corner swap)`() {
        // start sits at the box's TOP-RIGHT (a "\" line); after scaling it must still be top-right.
        val l = LineNode("edge", "frame", start = 40.0 to 20.0, end = 10.0 to 60.0) // box [10,20,30,40]
        val stretched = l.withBox(ResolvedBox(0.0, 0.0, 60.0, 80.0)) as LineNode   // sx=2, sy=2
        assertEquals(60.0 to 0.0, stretched.start)   // top-right -> top-right
        assertEquals(0.0 to 80.0, stretched.end)     // bottom-left -> bottom-left
        assertEquals(ResolvedBox(0.0, 0.0, 60.0, 80.0), stretched.box)
    }

    @Test fun `withBox keeps a degenerate axis degenerate under a taller box`() {
        // A horizontal line (height 0) under a box with height 50: sy guards to 1.0 — the line
        // stays horizontal; the derived box is TIGHTER than the target (documented invariant).
        val l = LineNode("spoke", "detail", start = 0.0 to 0.0, end = 100.0 to 0.0)
        val moved = l.withBox(ResolvedBox(10.0, 20.0, 200.0, 50.0)) as LineNode // sx=2, sy=1
        assertEquals(10.0 to 20.0, moved.start)
        assertEquals(210.0 to 20.0, moved.end)
        assertEquals(ResolvedBox(10.0, 20.0, 200.0, 0.0), moved.box)
    }

    @Test fun `withBox translates a zero-length line to the new origin`() {
        // Both axes degenerate: both guards fire, scales are 1.0 — the point just moves.
        val l = LineNode("dot", "detail", start = 5.0 to 5.0, end = 5.0 to 5.0)
        val moved = l.withBox(ResolvedBox(40.0, 60.0, 0.0, 0.0)) as LineNode
        assertEquals(40.0 to 60.0, moved.start)
        assertEquals(40.0 to 60.0, moved.end)
    }

    @Test fun `withBox returns a copy with new geometry`() {
        val c = CircleNode("hub", "wheel", box, red)
        val moved = c.withBox(ResolvedBox(0.0, 0.0, 10.0, 10.0))
        assertEquals(5.0, moved.box.cx)
    }

    @Test fun `withId returns a copy with the new id, geometry unchanged`() {
        val c = CircleNode("hub", "wheel", box, red)
        val renamed = c.withId("rim")
        assertEquals("rim", renamed.id)
        assertEquals(box, renamed.box)
    }

    @Test fun `svg coordinates serialize with exactly two decimals`() {
        val l = LineNode("bar", "frame", start = 10.0 to 20.0567, end = 40.0 to 60.0)
        assertEquals(
            """<line id="bar" data-role="frame" x1="10.00" y1="20.06" x2="40.00" y2="60.00"/>""",
            l.toSvgString(),
        )
    }

    /** Pins the rounding MODE and negative handling: 0.125 is exactly representable, so
     *  HALF_UP -> "0.13" while HALF_EVEN -> "0.12"; -0.0 (mirrored boxes, solver residuals)
     *  must not serialize as "-0.00". */
    @Test fun `svg coordinate rounding is HALF_UP and normalizes negative zero`() {
        assertEquals("0.13", 0.125.toSvgCoord())
        assertEquals("-2.50", (-2.5).toSvgCoord())
        assertEquals("0.00", (-0.0).toSvgCoord())
    }
}
