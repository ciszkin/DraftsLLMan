package by.ciszkin.draftsllman

import by.ciszkin.draftsllman.engine.ToolExecutor
import by.ciszkin.draftsllman.engine.ToolResult
import by.ciszkin.draftsllman.render.SkiaRenderer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class Phase1MilestoneTest {
    @Test fun `draws a constrained object from text-driven tool calls`() {
        val ex = ToolExecutor.default()
        fun call(json: String) = ex.execute(json).also {
            assertTrue(it !is ToolResult.Failure && it !is ToolResult.Rejected, "step failed: $json -> $it")
        }

        call("""{"type":"set_view_box","width":200,"height":120}""")
        call("""{"type":"add_node","id":"rear","role":"wheel","kind":"circle","width":40,"height":40,"preferredX":40,"preferredY":80}""")
        call("""{"type":"add_node","id":"front","role":"wheel","kind":"circle","width":40,"height":40,"preferredX":140,"preferredY":80}""")
        call("""{"type":"add_constraint","id":"ax","kind":"align","sourceId":"rear","sourceAnchor":"CENTER_Y","targetId":"front","targetAnchor":"CENTER_Y"}""")
        call("""{"type":"add_constraint","id":"gap","kind":"distance","sourceId":"rear","sourceAnchor":"RIGHT","targetId":"front","targetAnchor":"LEFT","pixels":60}""")

        val svg = (ex.execute("""{"type":"export_svg","dummy":0}""") as ToolResult.Query).payload as String
        assertTrue(svg.contains("""<circle id="rear" data-role="wheel""""))
        assertTrue(svg.contains("""<circle id="front" data-role="wheel""""))
        val png = SkiaRenderer.render(ex.engine().current)
        assertTrue(png.size > 100, "rendered PNG must be non-empty")

        // Constraint-driven geometry: rear & front are 60px apart (Distance) and vertically aligned (Align).
        val rear = ex.engine().current.nodes["rear"]!!.box
        val front = ex.engine().current.nodes["front"]!!.box
        assertTrue(abs(60.0 - (front.left - rear.right)) < 1e-6, "axle gap must be 60: ${front.left - rear.right}")
        assertTrue(abs(rear.cy - front.cy) < 1e-6, "wheels must be vertically aligned")
    }
}
