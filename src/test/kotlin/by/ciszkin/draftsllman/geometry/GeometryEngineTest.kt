package by.ciszkin.draftsllman.geometry

import by.ciszkin.draftsllman.scene.Anchor
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.RectNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.Style
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeometryEngineTest {
    // Helper: create a resolved circle at (100, 100) with radius 50
    private fun testCircle() = CircleNode(
        id = "wheel", semanticRole = "wheel", box = ResolvedBox(50.0, 50.0, 100.0, 100.0),
        style = Style()
    )

    // Helper: create a resolved rect at (0, 0) with size 200x100
    private fun testRect() = RectNode(
        id = "frame", semanticRole = "frame", box = ResolvedBox(0.0, 0.0, 200.0, 100.0),
        style = Style()
    )

    @Test fun `radial pattern produces correct angles and positions`() {
        val wheel = testCircle()
        val cx = wheel.box.cx
        val cy = wheel.box.cy
        val radius = 50.0  // to rim (BOTTOM anchor)

        val nodes = GeometryEngine.generateRadialPattern(
            targetId = "wheel", nodeName = "spoke", nodeType = GeometryEngine.NodeType.Line,
            centerAnchor = Anchor.CENTER, boundaryAnchor = Anchor.BOTTOM,
            count = 8, displacement = 0.5, sizeRatio = 0.05,
            centerPoint = Pair(cx, cy), boundaryPoint = Pair(cx, cy + radius)
        )

        assertEquals(8, nodes.size, "should produce 8 spokes")
        val first = nodes[0]
        assertTrue(first.id.startsWith("wheel_spoke_"), "id namespace: ${first.id}")
        assertEquals("spoke", first.role)
        assertEquals(GeometryEngine.NodeType.Line, first.type, "node type should be Line")

        // Verify angular spacing: 360° / 8 = 45° starting from baseAngle (π/2, straight down)
        val angles = nodes.map { it.angle }
        val baseAngle = Math.PI / 2  // atan2(50, 0) = π/2
        val expected = (0 until 8).map { baseAngle + it * 2 * Math.PI / 8 }
        angles.zip(expected).forEach { (actual, exp) ->
            assertTrue(Math.abs(actual - exp) < 0.001, "angle mismatch: actual=$actual vs expected=$exp")
        }
    }

    @Test fun `linear pattern produces correct spacing`() {
        val frame = testRect()
        val left = Pair(0.0, 50.0)  // LEFT anchor
        val right = Pair(200.0, 50.0)  // RIGHT anchor

        val nodes = GeometryEngine.generateLinearPattern(
            targetId = "frame", nodeName = "rivet", nodeType = GeometryEngine.NodeType.Point,
            startAnchor = Anchor.LEFT, endAnchor = Anchor.RIGHT,
            count = 5, displacement = 0.5, sizeRatio = 0.03,
            startPoint = left, endPoint = right
        )

        assertEquals(5, nodes.size, "should produce 5 rivets")
        assertEquals(GeometryEngine.NodeType.Point, nodes[0].type, "node type should be Point")

        val xPositions = nodes.map { it.position!!.first }
        // Points are evenly spaced between endpoints: 200/6, 2*200/6, 3*200/6, 4*200/6, 5*200/6
        val expected = (1..5).map { it * 200.0 / 6.0 }
        xPositions.zip(expected).forEach { (actual, exp) ->
            assertTrue(Math.abs(actual - exp) < 0.01, "x-position mismatch: actual=$actual vs expected=$exp")
        }
    }

    @Test fun `grid pattern produces correct 2D spacing`() {
        val frame = testRect()
        val topLeft = Pair(0.0, 0.0)
        val bottomRight = Pair(200.0, 100.0)

        val nodes = GeometryEngine.generateGridPattern(
            targetId = "frame", nodeName = "vent", nodeType = GeometryEngine.NodeType.Shape,
            topLeftAnchor = Anchor.TOP_LEFT, bottomRightAnchor = Anchor.BOTTOM_RIGHT,
            rows = 3, cols = 4, sizeRatio = 0.02,
            topLeftPoint = topLeft, bottomRightPoint = bottomRight
        )

        assertEquals(12, nodes.size, "3 × 4 = 12 vents")
        assertEquals(GeometryEngine.NodeType.Shape, nodes[0].type, "node type should be Shape")
    }

    @Test fun `boundary pattern on circle places nodes on perimeter`() {
        val wheel = testCircle()
        val cx = wheel.box.cx
        val cy = wheel.box.cy
        val radius = 50.0

        val nodes = GeometryEngine.generateBoundaryPattern(
            targetId = "wheel", nodeName = "reflective", nodeType = GeometryEngine.NodeType.Point,
            count = 12, sizeRatio = 0.02,
            centerPoint = Pair(cx, cy), radius = radius
        )

        assertEquals(12, nodes.size, "should place 12 points")
        assertEquals(GeometryEngine.NodeType.Point, nodes[0].type, "node type should be Point")

        nodes.forEach { node ->
            val (x, y) = node.position!!
            val dx = x - cx
            val dy = y - cy
            val dist = Math.sqrt(dx * dx + dy * dy)
            assertTrue(Math.abs(dist - radius) < 1.0, "point not on perimeter: actual dist=$dist vs expected radius=$radius")
        }
    }
}
