package by.ciszkin.draftsllman.engine

import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.GroupNode
import by.ciszkin.draftsllman.scene.InstanceNode
import by.ciszkin.draftsllman.scene.LineNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.SceneNode
import by.ciszkin.draftsllman.scene.ShapeNode
import by.ciszkin.draftsllman.scene.Style
import by.ciszkin.draftsllman.solver.ConstraintSet
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgrammaticVerifierTest {
    private fun graph(node: SceneNode) = SceneGraph.EMPTY.withNode(node).withRoot(node.id)

    @Test fun `valid solved leaf passes`() {
        val ok = graph(CircleNode("a", "w", ResolvedBox(0.0, 0.0, 4.0, 4.0), Style()))
        assertTrue(ProgrammaticVerifier.verify(ok, ConstraintSet.EMPTY).ok)
    }

    @Test fun `NaN or non-positive leaf size fails`() {
        assertFalse(ProgrammaticVerifier.verify(graph(CircleNode("a", "w", ResolvedBox(0.0, 0.0, 0.0, 4.0), Style())), ConstraintSet.EMPTY).ok)
        assertFalse(ProgrammaticVerifier.verify(graph(CircleNode("a", "w", ResolvedBox(Double.NaN, 0.0, 4.0, 4.0), Style())), ConstraintSet.EMPTY).ok)
    }

    @Test fun `group with empty box passes (structural, derived box)`() {
        val g = SceneGraph(
            nodes = mapOf("g" to GroupNode("g", "bike", listOf("a"))),
            rootIds = listOf("g"),
            children = mapOf("g" to listOf("a")),
        )
        assertTrue(ProgrammaticVerifier.verify(g, ConstraintSet.EMPTY).ok)
    }

    @Test fun `axis-aligned line with one zero dimension passes (derived bounding box)`() {
        // A horizontal spoke: box derived from two endpoints has height 0 — legitimate, must pass.
        val horizontal = graph(LineNode("s", "spoke", start = Pair(0.0, 5.0), end = Pair(10.0, 5.0)))
        assertTrue(ProgrammaticVerifier.verify(horizontal, ConstraintSet.EMPTY).ok,
            "axis-aligned line must pass: ${ProgrammaticVerifier.verify(horizontal, ConstraintSet.EMPTY).errors}")
        // A vertical spoke: box width 0 — also legitimate.
        val vertical = graph(LineNode("s2", "spoke", start = Pair(5.0, 0.0), end = Pair(5.0, 10.0)))
        assertTrue(ProgrammaticVerifier.verify(vertical, ConstraintSet.EMPTY).ok)
    }

    @Test fun `a degenerate point line still fails`() {
        // start == end -> box is a zero-size point; rejected even for derived-box nodes.
        val point = graph(LineNode("s", "spoke", start = Pair(5.0, 5.0), end = Pair(5.0, 5.0)))
        assertFalse(ProgrammaticVerifier.verify(point, ConstraintSet.EMPTY).ok)
    }

    @Test fun `a valid InstanceNode passes despite its zero-size box`() {
        // InstanceNode.box is (x, y, 0, 0) by design — a position-only <use> with no inherent size.
        // The verifier must exempt it from the size check (it has no rendered size of its own).
        val inst = graph(InstanceNode("u", "badge", masterId = "m", x = 10.0, y = 20.0, scale = 1.0, style = Style()))
        assertTrue(ProgrammaticVerifier.verify(inst, ConstraintSet.EMPTY).ok,
            "valid InstanceNode must pass: ${ProgrammaticVerifier.verify(inst, ConstraintSet.EMPTY).errors}")
    }
}
