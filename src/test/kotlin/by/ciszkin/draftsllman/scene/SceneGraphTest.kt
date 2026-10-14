package by.ciszkin.draftsllman.scene

import by.ciszkin.draftsllman.solver.ConstraintSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SceneGraphTest {
    @Test fun `EMPTY has no nodes`() {
        assertEquals(0, SceneGraph.EMPTY.nodes.size)
        assertEquals(ConstraintSet.EMPTY, SceneGraph.EMPTY.constraints)
    }

    @Test fun `withNode adds and withoutNode removes`() {
        val n = CircleNode("a", "x", ResolvedBox(0.0, 0.0, 1.0, 1.0), Style())
        val g = SceneGraph.EMPTY.withNode(n).withRoot("a")
        assertEquals(n, g.nodes["a"])
        val g2 = g.withoutNode("a")
        assertNull(g2.nodes["a"])
    }

    @Test fun `groupBox unions child boxes`() {
        val graph = SceneGraph(
            nodes = mapOf(
                "g" to GroupNode("g", "bike", listOf("a", "b")),
                "a" to CircleNode("a", "w", ResolvedBox(0.0, 0.0, 10.0, 10.0), Style()),
                "b" to CircleNode("b", "w", ResolvedBox(20.0, 0.0, 10.0, 10.0), Style()),
            ),
            rootIds = listOf("g"),
            children = mapOf("g" to listOf("a", "b")),
        )
        val box = graph.groupBox("g")!!
        assertEquals(0.0, box.left)
        assertEquals(30.0, box.right)
    }
}
