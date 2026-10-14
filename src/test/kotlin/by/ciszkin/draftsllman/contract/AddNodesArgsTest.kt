package by.ciszkin.draftsllman.contract

import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.Style
import by.ciszkin.draftsllman.solver.ConstraintSet
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AddNodesArgsTest {

    private val graph: SceneGraph =
        SceneGraph.EMPTY.withNode(CircleNode("frame", "w", ResolvedBox(0.0, 0.0, 10.0, 10.0), Style()))

    @Test fun `invalid node_type is rejected`() {
        val errs = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "spoke", nodeType = "blob", preset = "single_node",
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertTrue(errs.any { it.path == "node_type" }, "expected node_type error: $errs")
    }

    @Test fun `unknown target_id is rejected`() {
        val errs = ToolArgs.AddNodesArgs(
            targetId = "ghost", nodeName = "spoke", nodeType = "point", preset = "single_node",
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertTrue(errs.any { it.path == "target_id" }, "expected target_id error: $errs")
    }

    @Test fun `blank target_id is rejected`() {
        val errs = ToolArgs.AddNodesArgs(
            targetId = " ", nodeName = "spoke", nodeType = "point", preset = "single_node",
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertTrue(errs.any { it.path == "target_id" && it.message.contains("blank") }, "expected blank error: $errs")
    }

    @Test fun `blank node_name is rejected`() {
        val errs = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "", nodeType = "point", preset = "single_node",
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertTrue(errs.any { it.path == "node_name" }, "expected node_name error: $errs")
    }

    @Test fun `unknown preset is rejected`() {
        val errs = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "spoke", nodeType = "point", preset = "nope",
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertTrue(errs.any { it.path == "preset" }, "expected preset error: $errs")
    }

    @Test fun `radial_pattern without count is rejected`() {
        val errs = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "spoke", nodeType = "line", preset = "radial_pattern",
            boundaryAnchor = "RIGHT_CENTER",
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertTrue(errs.any { it.path == "count" }, "expected count error: $errs")
    }

    @Test fun `radial_pattern with non-positive count is rejected`() {
        val zero = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "spoke", nodeType = "line", preset = "radial_pattern",
            count = 0, boundaryAnchor = "RIGHT_CENTER",
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertTrue(zero.any { it.path == "count" && it.message.contains("positive") }, "expected positive-count error: $zero")
        val negative = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "spoke", nodeType = "line", preset = "radial_pattern",
            count = -2, boundaryAnchor = "RIGHT_CENTER",
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertTrue(negative.any { it.path == "count" && it.message.contains("positive") }, "expected positive-count error: $negative")
    }

    @Test fun `linear_pattern and boundary_pattern with non-positive count are rejected`() {
        val linear = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "rib", nodeType = "line", preset = "linear_pattern",
            count = 0, startAnchor = "LEFT", endAnchor = "RIGHT",
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertTrue(linear.any { it.path == "count" && it.message.contains("positive") }, "expected positive-count error: $linear")

        val boundary = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "edge", nodeType = "line", preset = "boundary_pattern", count = -1,
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertTrue(boundary.any { it.path == "count" && it.message.contains("positive") }, "expected positive-count error: $boundary")
    }

    @Test fun `grid_pattern with non-positive rows or cols is rejected`() {
        val badRows = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "cell", nodeType = "point", preset = "grid_pattern",
            rows = 0, cols = 3, topLeftAnchor = "TOP_LEFT", bottomRightAnchor = "BOTTOM_RIGHT",
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertTrue(badRows.any { it.path == "rows" && it.message.contains("positive") }, "expected positive-rows error: $badRows")

        val badCols = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "cell", nodeType = "point", preset = "grid_pattern",
            rows = 3, cols = -1, topLeftAnchor = "TOP_LEFT", bottomRightAnchor = "BOTTOM_RIGHT",
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertTrue(badCols.any { it.path == "cols" && it.message.contains("positive") }, "expected positive-cols error: $badCols")
    }

    @Test fun `radial_pattern without boundary_anchor is rejected`() {
        val errs = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "spoke", nodeType = "line", preset = "radial_pattern", count = 6,
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertTrue(errs.any { it.path == "boundary_anchor" }, "expected boundary_anchor error: $errs")
    }

    @Test fun `boundary_pattern without count is rejected`() {
        val errs = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "edge", nodeType = "line", preset = "boundary_pattern",
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertTrue(errs.any { it.path == "count" }, "expected count error: $errs")
    }

    @Test fun `linear_pattern missing start_anchor or end_anchor is rejected`() {
        val missingStart = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "rib", nodeType = "line", preset = "linear_pattern",
            count = 3, endAnchor = "LEFT",
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertTrue(missingStart.any { it.path == "start_anchor" }, "expected start_anchor error: $missingStart")

        val missingEnd = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "rib", nodeType = "line", preset = "linear_pattern",
            count = 3, startAnchor = "LEFT",
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertTrue(missingEnd.any { it.path == "end_anchor" }, "expected end_anchor error: $missingEnd")

        val missingCount = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "rib", nodeType = "line", preset = "linear_pattern",
            startAnchor = "LEFT", endAnchor = "RIGHT",
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertTrue(missingCount.any { it.path == "count" }, "expected count error: $missingCount")
    }

    @Test fun `grid_pattern missing rows, cols, or corners is rejected`() {
        val errs = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "cell", nodeType = "point", preset = "grid_pattern",
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertTrue(errs.any { it.path == "rows" }, "expected rows error: $errs")
        assertTrue(errs.any { it.path == "cols" }, "expected cols error: $errs")
        assertTrue(errs.any { it.path == "top_left_anchor" }, "expected top_left_anchor error: $errs")
        assertTrue(errs.any { it.path == "bottom_right_anchor" }, "expected bottom_right_anchor error: $errs")
    }

    @Test fun `valid single_node returns no errors`() {
        val errs = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "spoke", nodeType = "point", preset = "single_node",
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertEquals(emptyList(), errs, "single_node must be valid: $errs")
    }

    @Test fun `valid radial_pattern returns no errors`() {
        val errs = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "spoke", nodeType = "line", preset = "radial_pattern",
            count = 6, boundaryAnchor = "RIGHT_CENTER",
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertEquals(emptyList(), errs, "radial_pattern must be valid: $errs")
    }

    @Test fun `valid grid_pattern returns no errors`() {
        val errs = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "cell", nodeType = "point", preset = "grid_pattern",
            rows = 3, cols = 3, topLeftAnchor = "TOP_LEFT", bottomRightAnchor = "BOTTOM_RIGHT",
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertEquals(emptyList(), errs, "grid_pattern must be valid: $errs")
    }

    @Test fun `valid linear_pattern returns no errors`() {
        val errs = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "rib", nodeType = "line", preset = "linear_pattern",
            count = 4, startAnchor = "LEFT_CENTER", endAnchor = "RIGHT_CENTER",
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertEquals(emptyList(), errs, "linear_pattern must be valid: $errs")
    }

    @Test fun `valid boundary_pattern returns no errors`() {
        val errs = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "edge", nodeType = "point", preset = "boundary_pattern", count = 8,
        ).semanticValidate(graph, ConstraintSet.EMPTY)
        assertEquals(emptyList(), errs, "boundary_pattern must be valid: $errs")
    }

    @Test fun `duplicate generated id is rejected (blocks a retry double-emission)`() {
        // A prior add_nodes for (target=frame, name=spoke) left "frame_spoke_0" in the graph. A retry
        // with the same target+name would re-emit "frame_spoke_0" (the engine appends, so it collides).
        val graphWithSpoke = graph.withNode(
            CircleNode("frame_spoke_0", "spoke", ResolvedBox(0.0, 0.0, 1.0, 1.0), Style())
        )
        val errs = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "spoke", nodeType = "line", preset = "radial_pattern",
            count = 4, boundaryAnchor = "RIGHT_CENTER",
        ).semanticValidate(graphWithSpoke, ConstraintSet.EMPTY)
        assertTrue(errs.any { it.path == "id" && it.message.contains("already exist") },
            "expected duplicate-id error: $errs")
    }

    @Test fun `different node_name on the same target is allowed (no false positive)`() {
        // "frame_spoke_0" exists, but adding "rivet" detail uses a different prefix ("frame_rivet_")
        // and must NOT trip the guard — spokes and rivets coexist on one component.
        val graphWithSpoke = graph.withNode(
            CircleNode("frame_spoke_0", "spoke", ResolvedBox(0.0, 0.0, 1.0, 1.0), Style())
        )
        val errs = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "rivet", nodeType = "point", preset = "linear_pattern",
            count = 3, startAnchor = "LEFT", endAnchor = "RIGHT",
        ).semanticValidate(graphWithSpoke, ConstraintSet.EMPTY)
        assertEquals(emptyList(), errs, "different nodeName must not trip the prefix guard: $errs")
    }

    @Test fun `a grid_pattern retry collides on the row_col id and is rejected`() {
        val graphWithCell = graph.withNode(
            CircleNode("frame_cell_1_1", "cell", ResolvedBox(0.0, 0.0, 1.0, 1.0), Style())
        )
        val errs = ToolArgs.AddNodesArgs(
            targetId = "frame", nodeName = "cell", nodeType = "point", preset = "grid_pattern",
            rows = 2, cols = 2, topLeftAnchor = "TOP_LEFT", bottomRightAnchor = "BOTTOM_RIGHT",
        ).semanticValidate(graphWithCell, ConstraintSet.EMPTY)
        assertTrue(errs.any { it.path == "id" }, "expected grid duplicate-id error: $errs")
    }

    @Test fun `add_nodes variant is present in the generated schema and accepts a valid payload`() {
        val gen = KotlinxSchemaGenerator()
        val val_ = OptimumSchemaValidator()
        val schema = gen.generate(ToolArgs.serializer().descriptor)
        val text = schema.toString()
        assertTrue(text.contains("add_nodes"), "schema must list add_nodes: $text")

        val payload = Json.parseToJsonElement(
            """{"type":"add_nodes","target_id":"frame","node_name":"spoke","node_type":"point","preset":"single_node"}"""
        )
        assertEquals(emptyList(), val_.validate(schema, payload), "valid add_nodes payload must pass schema validation")
    }
}
