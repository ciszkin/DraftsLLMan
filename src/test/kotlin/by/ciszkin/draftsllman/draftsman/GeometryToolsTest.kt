package by.ciszkin.draftsllman.draftsman

import by.ciszkin.draftsllman.contract.ToolArgs.AddNodesArgs
import by.ciszkin.draftsllman.scene.Anchor
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.CurveNode
import by.ciszkin.draftsllman.scene.LineNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.ShapeNode
import by.ciszkin.draftsllman.scene.Style
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * GeometryTools is the dispatch glue between the add_nodes tool contract ([AddNodesArgs]) and the
 * pure-math GeometryEngine. It reads the target node's SOLVED box from the SceneGraph, resolves
 * anchor names to absolute (x,y), calls the matching preset generator, and converts each
 * ComputedNode into a concrete SceneNode. These tests build a manually-positioned SceneGraph
 * (no solver) with a 100×100 target box at (50,50) so all anchor coordinates are exact.
 */
class GeometryToolsTest {

    // Target box: left=50, right=150, top=50, bottom=150, cx=cy=100.
    private val targetBox = ResolvedBox(50.0, 50.0, 100.0, 100.0)
    private val graph: SceneGraph =
        SceneGraph.EMPTY.withNode(CircleNode("target", "frame", targetBox, Style()))

    private fun approx(a: Double, b: Double, tol: Double = 1e-9) = abs(a - b) <= tol

    // ---- single_node -----------------------------------------------------------

    @Test fun `single_node produces one node at the center anchor`() {
        val out = GeometryTools.execute(
            AddNodesArgs(targetId = "target", nodeName = "hub", nodeType = "point", preset = "single_node"),
            graph,
        )
        assertEquals(1, out.size)
        val node = assertIs<CircleNode>(out.first())
        assertEquals("target_hub_0", node.id)
        assertEquals("hub", node.semanticRole)
        // size = max(100,100) * 0.05 = 5 -> box centered on (100,100)
        assertTrue(approx(node.box.cx, 100.0), "cx=${node.box.cx}")
        assertTrue(approx(node.box.cy, 100.0), "cy=${node.box.cy}")
        assertTrue(approx(node.box.width, 5.0), "width=${node.box.width}")
        assertTrue(approx(node.box.height, 5.0), "height=${node.box.height}")
    }

    @Test fun `single_node honors a non-center anchor`() {
        val out = GeometryTools.execute(
            AddNodesArgs(
                targetId = "target", nodeName = "dot", nodeType = "point", preset = "single_node",
                centerAnchor = "TOP_LEFT",
            ),
            graph,
        )
        val node = assertIs<CircleNode>(out.single())
        // TOP_LEFT -> (50, 50)
        assertTrue(approx(node.box.cx, 50.0), "cx=${node.box.cx}")
        assertTrue(approx(node.box.cy, 50.0), "cy=${node.box.cy}")
    }

    @Test fun `single_node curve builds a CurveNode from position along angle`() {
        val out = GeometryTools.execute(
            AddNodesArgs(targetId = "target", nodeName = "arc", nodeType = "curve", preset = "single_node"),
            graph,
        )
        val node = assertIs<CurveNode>(out.single())
        assertEquals("target_arc_0", node.id)
        // start = center (100,100); length = 100*0.05 = 5 along angle 0 -> end (105,100)
        assertTrue(approx(node.path.startPoint.first, 100.0))
        assertTrue(approx(node.path.startPoint.second, 100.0))
        assertTrue(approx(node.path.endPoint.first, 105.0))
        assertTrue(approx(node.path.endPoint.second, 100.0))
    }

    @Test fun `single_node line builds a LineNode from position along angle`() {
        val out = GeometryTools.execute(
            AddNodesArgs(targetId = "target", nodeName = "tick", nodeType = "line", preset = "single_node"),
            graph,
        )
        val node = assertIs<LineNode>(out.single())
        assertEquals("target_tick_0", node.id)
        // start = center (100,100); size = 100*0.05 = 5 along angle 0 -> end (105,100)
        assertTrue(approx(node.start.first, 100.0), "start.x=${node.start.first}")
        assertTrue(approx(node.start.second, 100.0), "start.y=${node.start.second}")
        assertTrue(approx(node.end.first, 105.0), "end.x=${node.end.first}")
        assertTrue(approx(node.end.second, 100.0), "end.y=${node.end.second}")
    }

    @Test fun `single_node shape builds a closed 4-corner square centered on the anchor`() {
        val out = GeometryTools.execute(
            AddNodesArgs(targetId = "target", nodeName = "chip", nodeType = "shape", preset = "single_node"),
            graph,
        )
        val node = assertIs<ShapeNode>(out.single())
        assertEquals("target_chip_0", node.id)
        assertEquals(4, node.points.size)
        assertTrue(node.closed)
        // side = 100*0.05 = 5 -> box centered on (100,100) with width/height 5
        assertTrue(approx(node.box.cx, 100.0))
        assertTrue(approx(node.box.cy, 100.0))
        assertTrue(approx(node.box.width, 5.0))
        assertTrue(approx(node.box.height, 5.0))
    }

    @Test fun `single_node resolves a registered named anchor alias via resolveAnchorName`() {
        // The headline reason GeometryTools goes through resolveAnchorName: an LLM-declared alias
        // like "hub" (mapped to a geometric anchor) must resolve identically to a geometric name.
        val aliasGraph = graph.withAnchorAliases("target", mapOf("hub" to Anchor.CENTER))
        val out = GeometryTools.execute(
            AddNodesArgs(
                targetId = "target", nodeName = "dot", nodeType = "point", preset = "single_node",
                centerAnchor = "hub",
            ),
            aliasGraph,
        )
        val node = assertIs<CircleNode>(out.single())
        // "hub" -> Anchor.CENTER -> (cx, cy) = (100, 100)
        assertTrue(approx(node.box.cx, 100.0), "cx=${node.box.cx}")
        assertTrue(approx(node.box.cy, 100.0), "cy=${node.box.cy}")
    }

    // ---- radial_pattern --------------------------------------------------------

    @Test fun `radial_pattern point nodes lie on the expected circle`() {
        // center=CENTER(100,100), boundary=TOP_CENTER(100,50) -> radius 50, displacement 0.5 -> r=25
        val out = GeometryTools.execute(
            AddNodesArgs(
                targetId = "target", nodeName = "spoke", nodeType = "point", preset = "radial_pattern",
                count = 4, centerAnchor = "CENTER", boundaryAnchor = "TOP_CENTER", displacement = 0.5,
            ),
            graph,
        )
        assertEquals(4, out.size)
        out.forEachIndexed { i, n ->
            assertEquals("target_spoke_$i", n.id, "id=$i")
            assertIs<CircleNode>(n, "node $i should be a CircleNode")
            // each circle's center is the computed position; must be 25 from (100,100)
            val d = sqrt((n.box.cx - 100.0).let { it * it } + (n.box.cy - 100.0).let { it * it })
            assertTrue(approx(d, 25.0), "node $i distance from center=$d (expected 25)")
        }
    }

    @Test fun `radial_pattern line node box spans its two endpoints`() {
        // Same geometry as above (radius 50, r=25). i=1 -> angle 0, pos (125,100).
        val out = GeometryTools.execute(
            AddNodesArgs(
                targetId = "target", nodeName = "tick", nodeType = "line", preset = "radial_pattern",
                count = 4, centerAnchor = "CENTER", boundaryAnchor = "TOP_CENTER", displacement = 0.5,
            ),
            graph,
        )
        assertEquals(4, out.size)
        out.forEach { assertIs<LineNode>(it) }
        // i=1: pos=(125,100), angle=0, length=5 -> end=(130,100). box spans {(125,100),(130,100)}.
        val n1 = out[1]
        assertEquals("target_tick_1", n1.id)
        // bounding box of the two endpoints: x in [125,130], y in [100,100]
        assertTrue(approx(n1.box.x, 125.0), "x=${n1.box.x}")
        assertTrue(approx(n1.box.right, 130.0), "right=${n1.box.right}")
        assertTrue(approx(n1.box.y, 100.0), "y=${n1.box.y}")
        assertTrue(approx(n1.box.bottom, 100.0), "bottom=${n1.box.bottom}")
        // width == length (5), height == 0 -> box is exactly the two-endpoint span
        assertTrue(approx(n1.box.width, 5.0), "width=${n1.box.width}")
        assertTrue(approx(n1.box.height, 0.0), "height=${n1.box.height}")
        // LineNode emits a <line> whose x1/x2 come from the box (NOT a hardcoded 2.0-height rect)
        val svg = n1.toSvgString()
        assertTrue(svg.startsWith("<line"), "not a <line>: $svg")
        assertTrue(svg.contains("x1=") && svg.contains("x2="), "missing endpoints: $svg")
    }

    @Test fun `radial_pattern shape nodes are 4-point closed squares`() {
        val out = GeometryTools.execute(
            AddNodesArgs(
                targetId = "target", nodeName = "stud", nodeType = "shape", preset = "radial_pattern",
                count = 3, centerAnchor = "CENTER", boundaryAnchor = "TOP_CENTER",
            ),
            graph,
        )
        assertEquals(3, out.size)
        out.forEach { assertIs<ShapeNode>(it) }
        val s = out.first() as ShapeNode
        assertEquals(4, s.points.size, "square has 4 corners")
        assertTrue(s.closed)
    }

    @Test fun `radial pattern detail size uses the raw sub-decimal size_ratio`() {
        // Verifies the e2e flow: size_ratio = 0.05 flows unsnapped into computed geometry.
        // Center=CENTER(100,100), boundary=TOP_CENTER(100,50) → radius 50.
        // Expected detail size = 0.05 × 50 = 2.5 (NOT the old snapped 0.1 × 50 = 5).
        val out = GeometryTools.execute(
            AddNodesArgs(
                targetId = "target", nodeName = "dot", nodeType = "point", preset = "radial_pattern",
                count = 1, centerAnchor = "CENTER", boundaryAnchor = "TOP_CENTER",
                displacement = 0.0, sizeRatio = 0.05,
            ),
            graph,
        )
        assertEquals(1, out.size)
        val node = assertIs<CircleNode>(out.single())
        // The detail node should be centered at the center anchor (displacement=0.0).
        assertTrue(approx(node.box.cx, 100.0), "cx=${node.box.cx}")
        assertTrue(approx(node.box.cy, 100.0), "cy=${node.box.cy}")
        // Size should be 0.05 × center→boundary distance (50) = 2.5, NOT the old snapped 5.0.
        val expectedSize = 0.05 * 50.0  // = 2.5
        assertTrue(approx(node.box.width, expectedSize, tol = 1e-6), "width=${node.box.width} (expected $expectedSize)")
        assertTrue(approx(node.box.height, expectedSize, tol = 1e-6), "height=${node.box.height} (expected $expectedSize)")
    }

    // ---- linear_pattern --------------------------------------------------------

    @Test fun `linear_pattern places nodes evenly along start to end`() {
        // start=TOP_LEFT(50,50), end=BOTTOM_RIGHT(150,150); step=1/(3+1)=0.25 -> t=0.25,0.5,0.75
        val out = GeometryTools.execute(
            AddNodesArgs(
                targetId = "target", nodeName = "rib", nodeType = "point", preset = "linear_pattern",
                count = 3, startAnchor = "TOP_LEFT", endAnchor = "BOTTOM_RIGHT",
            ),
            graph,
        )
        assertEquals(3, out.size)
        // ids are 1..count
        assertEquals(listOf("target_rib_1", "target_rib_2", "target_rib_3"), out.map { it.id })
        val expected = listOf(75.0 to 75.0, 100.0 to 100.0, 125.0 to 125.0)
        out.forEachIndexed { i, n ->
            val c = assertIs<CircleNode>(n)
            assertTrue(approx(c.box.cx, expected[i].first), "i=$i cx=${c.box.cx}")
            assertTrue(approx(c.box.cy, expected[i].second), "i=$i cy=${c.box.cy}")
        }
    }

    // ---- grid_pattern ----------------------------------------------------------

    @Test fun `grid_pattern produces rows times cols nodes`() {
        val rows = 2
        val cols = 3
        val out = GeometryTools.execute(
            AddNodesArgs(
                targetId = "target", nodeName = "cell", nodeType = "point", preset = "grid_pattern",
                rows = rows, cols = cols, topLeftAnchor = "TOP_LEFT", bottomRightAnchor = "BOTTOM_RIGHT",
            ),
            graph,
        )
        assertEquals(rows * cols, out.size)
        // ids are namespaced row_col
        val ids = out.map { it.id }
        assertTrue("target_cell_1_1" in ids, "missing 1_1: $ids")
        assertTrue("target_cell_2_3" in ids, "missing 2_3: $ids")
        // all circles
        out.forEach { assertIs<CircleNode>(it) }
        // cellW = 100/(3+1) = 25 -> first cell x = 50 + 1*25 = 75
        val first = out.first { it.id == "target_cell_1_1" } as CircleNode
        assertTrue(approx(first.box.cx, 75.0), "first cell cx=${first.box.cx}")
    }

    // ---- boundary_pattern ------------------------------------------------------

    @Test fun `boundary_pattern nodes lie on the perimeter circle`() {
        // radius = min(100,100)/2 = 50; count=4 -> angles 0, pi/2, pi, 3pi/2
        val out = GeometryTools.execute(
            AddNodesArgs(
                targetId = "target", nodeName = "rim", nodeType = "point", preset = "boundary_pattern",
                count = 4, centerAnchor = "CENTER",
            ),
            graph,
        )
        assertEquals(4, out.size)
        out.forEachIndexed { i, n ->
            assertEquals("target_rim_$i", n.id)
            val c = assertIs<CircleNode>(n)
            val d = sqrt((c.box.cx - 100.0).let { it * it } + (c.box.cy - 100.0).let { it * it })
            assertTrue(approx(d, 50.0), "node $i distance from center=$d (expected 50)")
        }
        // i=0 -> angle 0 -> (150, 100)
        val n0 = out.first() as CircleNode
        assertTrue(approx(n0.box.cx, 150.0))
        assertTrue(approx(n0.box.cy, 100.0))
    }

    @Test fun `boundary_pattern inscribes in the smaller dimension of a non-square target`() {
        // 200 wide x 100 tall at (50,50): cx=150, cy=100. radius must be min(200,100)/2 = 50 so
        // nodes stay inscribed (maxOf would over-scribe to radius 100 and clip past the top/bottom).
        val wideBox = ResolvedBox(50.0, 50.0, 200.0, 100.0)
        val wideGraph = SceneGraph.EMPTY.withNode(CircleNode("wide", "frame", wideBox, Style()))
        val out = GeometryTools.execute(
            AddNodesArgs(
                targetId = "wide", nodeName = "rim", nodeType = "point", preset = "boundary_pattern",
                count = 4, centerAnchor = "CENTER",
            ),
            wideGraph,
        )
        // i=1 -> angle pi/2 -> (150, 100+50) = (150, 150): exactly on the bottom edge (top=50,bottom=150)
        val n1 = out[1] as CircleNode
        assertTrue(approx(n1.box.cy, 150.0), "i=1 cy=${n1.box.cy} (expected 150, the inscribed radius)")
        assertTrue(approx(n1.box.cx, 150.0))
        // and every node is 50 (not 100) from center
        out.forEach { n ->
            val c = assertIs<CircleNode>(n)
            val d = sqrt((c.box.cx - 150.0).let { it * it } + (c.box.cy - 100.0).let { it * it })
            assertTrue(approx(d, 50.0), "distance=$d (expected inscribed radius 50)")
        }
    }

    // ---- error handling --------------------------------------------------------

    @Test fun `unknown target id throws`() {
        try {
            GeometryTools.execute(
                AddNodesArgs(targetId = "ghost", nodeName = "x", nodeType = "point", preset = "single_node"),
                graph,
            )
            error("expected throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("ghost"), "message=${e.message}")
        }
    }
}
