package by.ciszkin.draftsllman.engine

import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.Style
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SceneGraphHasherTest {
    private fun graph(x: Double) = SceneGraph.EMPTY
        .withNode(CircleNode("a", "w", ResolvedBox(x, 0.0, 10.0, 10.0), Style()))
        .withRoot("a")

    @Test fun `same graph yields same hash`() =
        assertEquals(SceneGraphHasher.hash(graph(5.0)), SceneGraphHasher.hash(graph(5.0)))

    @Test fun `changed geometry yields different hash`() =
        assertNotEquals(SceneGraphHasher.hash(graph(5.0)), SceneGraphHasher.hash(graph(6.0)))
}
