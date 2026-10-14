package by.ciszkin.draftsllman.eval

import by.ciszkin.draftsllman.scene.BezierPath
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.CurveNode
import by.ciszkin.draftsllman.scene.GroupNode
import by.ciszkin.draftsllman.scene.LineNode
import by.ciszkin.draftsllman.scene.RectNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.ShapeNode
import by.ciszkin.draftsllman.scene.Style
import kotlin.test.Test
import kotlin.test.assertEquals

/** The S1 output -> eval-Prediction mapper: leaf primitives with kind+box for T0/T3 scoring. */
class PredictionMapperTest {

    private fun graph(): SceneGraph {
        val circle = CircleNode("c", "chart", ResolvedBox(10.0, 10.0, 40.0, 40.0), Style())
        val rect = RectNode("r", "button", ResolvedBox(60.0, 60.0, 30.0, 20.0), Style())
        val line = LineNode("l", "divider", start = 10.0 to 90.0, end = 50.0 to 90.0, style = Style())
        val curve = CurveNode(
            "cv", "annotation", BezierPath(10.0 to 10.0, 20.0 to 10.0, 15.0 to 5.0), Style(),
        )
        val poly = ShapeNode("p", "badge", listOf(90.0 to 90.0, 100.0 to 90.0, 95.0 to 80.0), closed = true, style = Style())
        val open = ShapeNode("o", "scribble", listOf(30.0 to 120.0, 40.0 to 130.0, 50.0 to 120.0), closed = false, style = Style())
        val group = GroupNode("g", "struct", childIds = listOf("c"))
        return SceneGraph(
            nodes = mapOf(
                "c" to circle, "r" to rect, "l" to line, "cv" to curve,
                "p" to poly, "o" to open, "g" to group,
            ),
            rootIds = listOf("c", "r", "l", "cv", "p", "o", "g"),
            canvas = CanvasSpec(200.0, 200.0),
        )
    }

    @Test
    fun `maps leaf primitives with kind and box, skipping structural nodes`() {
        val pred = PredictionMapper.from(graph())
        assertEquals(listOf("c", "r", "l", "cv", "p", "o"), pred.components.map { it.id })
        val byId = pred.components.associateBy { it.id }
        assertEquals(PrimitiveKind.CIRCLE, byId["c"]?.kind)
        assertEquals(PrimitiveKind.RECT, byId["r"]?.kind)
        assertEquals(PrimitiveKind.LINE, byId["l"]?.kind)
        assertEquals(PrimitiveKind.PATH, byId["cv"]?.kind)
        assertEquals(PrimitiveKind.POLYGON, byId["p"]?.kind, "closed shape -> POLYGON")
        assertEquals(PrimitiveKind.PATH, byId["o"]?.kind, "open shape -> PATH")
        // box + role carried through
        assertEquals(ResolvedBox(60.0, 60.0, 30.0, 20.0), byId["r"]?.box)
        assertEquals("button", byId["r"]?.role)
    }
}