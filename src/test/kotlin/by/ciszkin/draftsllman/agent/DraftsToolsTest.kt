package by.ciszkin.draftsllman.agent

import by.ciszkin.draftsllman.contract.ToolArgs
import by.ciszkin.draftsllman.engine.CritiqueResult
import by.ciszkin.draftsllman.engine.ToolExecutor
import by.ciszkin.draftsllman.engine.ToolResult
import by.ciszkin.draftsllman.scene.ResolvedBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DraftsToolsTest {
    @Test fun `format renders each tool result variant`() {
        assertEquals("ok: touched a", format(ToolResult.Committed("ok", "a", ResolvedBox(10.0, 10.0, 20.0, 20.0))))
        assertEquals("ok", format(ToolResult.Committed("ok", null, null)))
        assertEquals("error: boom", format(ToolResult.Failure("boom")))
        assertEquals("error: bad", format(ToolResult.SolverFailure("bad")))
        assertEquals("error: [nope]", format(ToolResult.Rejected(listOf("nope"))))
        assertEquals("<svg/>", format(ToolResult.Query("<svg/>")))
        assertEquals("critique rendered: global map + ROI image attached", format(ToolResult.Critique(CritiqueResult("", null, null))))
    }

    @Test fun `dispatch serializes args, executes, and formats the result`() {
        val ex = ToolExecutor.default()
        ex.execute("""{"type":"set_view_box","width":200,"height":120}""")
        val out = dispatch(ex, CritiqueImageSink(), ToolArgs.AddNode(id = "a", role = "w", kind = "circle", width = 20.0, height = 20.0, preferredX = 10.0, preferredY = 10.0))
        assertEquals("ok: touched a", out)
        assertTrue(ex.engine().current.nodes.containsKey("a"))
    }

    @Test fun `committed results never leak coordinates to the LLM`() {
        val ex = ToolExecutor.default()
        ex.execute("""{"type":"set_view_box","width":200,"height":120}""")
        val out = dispatch(
            ex, CritiqueImageSink(),
            ToolArgs.AddNode(id = "tire", role = "wheel", kind = "circle", width = 100.0, height = 100.0),
        )
        assertEquals("ok: touched tire", out)
        // Zero-Coordinate Policy: no numeric coordinate pairs and no "@ (…)" coordinate block anywhere.
        assertFalse(Regex("\\d+\\.\\d+,\\d+\\.\\d+").containsMatchIn(out), "result leaks x,y pairs: $out")
        assertFalse(out.contains(" @ "), "result includes a coordinate block: $out")
    }

    @Test fun `format forwards the rich add_nodes message the executor builds`() {
        // runMutation sets Committed.message to the descriptive add_nodes string; format must render it
        // verbatim (not collapse it to "ok: touched <id>") so the Draftsman LLM gets actionable feedback.
        val msg = "ok: added 4 nodes of type 'point' via radial_pattern to 'wheel'"
        assertEquals(msg, format(ToolResult.Committed(msg, "wheel", ResolvedBox(60.0, 60.0, 8.0, 8.0))))
    }

    @Test fun `add_nodes description enumerates the valid node_types and presets`() {
        // Live-run defect (2026-08-14): Draftsmen guessed node_type 'circle'/'dot' because the tool
        // description never listed the valid values — wasted retries, 4 components left undetailed.
        // The description must enumerate node_types (and presets) so the LLM never has to guess.
        val desc = draftsTools(ToolExecutor.default(), CritiqueImageSink())
            .first { it.name == "add_nodes" }.descriptor.description
        assertTrue(desc.contains("node_type: point|line|curve|shape"), "must list node_types: $desc")
        assertTrue(desc.contains("preset:"), "must label the preset list: $desc")
    }
}
