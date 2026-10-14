package by.ciszkin.draftsllman.scene

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CurveNodeTest {

    @Test
    fun `quadratic curve emits a path element with M start Q control end`() {
        val node = CurveNode(
            id = "c1",
            semanticRole = "cable",
            path = BezierPath(
                startPoint = 0.0 to 0.0,
                endPoint = 100.0 to 0.0,
                controlPoint1 = 50.0 to 50.0,
            ),
        )
        val svg = node.toSvgString()
        assertTrue(svg.contains("<path"), "expected a <path element: $svg")
        assertTrue(svg.contains("""id="c1""""), "expected id=\"c1\": $svg")
        assertTrue(svg.contains("""data-role="cable""""), "expected data-role=\"cable\": $svg")
        assertTrue(
            svg.contains("""d="M 0.00 0.00 Q 50.00 50.00 100.00 0.00""""),
            "expected quadratic path data 'M 0.00 0.00 Q 50.00 50.00 100.00 0.00': $svg",
        )
    }

    @Test
    fun `cubic curve emits a path element with M start C c1 c2 end`() {
        val node = CurveNode(
            id = "c1",
            semanticRole = "cable",
            path = BezierPath(
                startPoint = 0.0 to 0.0,
                endPoint = 100.0 to 0.0,
                controlPoint1 = 30.0 to 50.0,
                controlPoint2 = 70.0 to 50.0,
            ),
        )
        val svg = node.toSvgString()
        assertTrue(
            svg.contains("""d="M 0.00 0.00 C 30.00 50.00 70.00 50.00 100.00 0.00""""),
            "expected cubic path data 'M 0.00 0.00 C 30.00 50.00 70.00 50.00 100.00 0.00': $svg",
        )
    }

    @Test
    fun `box is the bounding box of start end and control points`() {
        val node = CurveNode(
            id = "c1",
            semanticRole = "cable",
            path = BezierPath(
                startPoint = 0.0 to 0.0,
                endPoint = 100.0 to 0.0,
                controlPoint1 = 50.0 to 50.0,
            ),
        )
        assertEquals(0.0, node.box.left)
        assertEquals(100.0, node.box.right)
        assertEquals(0.0, node.box.top)
        assertEquals(50.0, node.box.bottom)
        assertEquals(100.0, node.box.width)
        assertEquals(50.0, node.box.height)
    }

    @Test
    fun `withBox translates every anchor and control point by the origin delta`() {
        val node = CurveNode(
            id = "c1",
            semanticRole = "cable",
            path = BezierPath(
                startPoint = 0.0 to 0.0,
                endPoint = 100.0 to 0.0,
                controlPoint1 = 50.0 to 50.0,
                controlPoint2 = 70.0 to 50.0,
            ),
        )
        val moved = node.withBox(node.box.translate(10.0, 20.0)) as CurveNode
        // box recomputes from the translated points: origin shifted, size unchanged.
        assertEquals(10.0, moved.box.x)
        assertEquals(20.0, moved.box.y)
        assertEquals(100.0, moved.box.width)
        assertEquals(50.0, moved.box.height)
        // every anchor/control point shifts by (+10, +20) in the emitted path data.
        assertTrue(
            moved.toSvgString().contains("""d="M 10.00 20.00 C 60.00 70.00 80.00 70.00 110.00 20.00""""),
            "expected translated cubic path data: ${moved.toSvgString()}",
        )
    }
}
