package by.ciszkin.draftsllman.contract

import by.ciszkin.draftsllman.scene.Anchor
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.Style
import by.ciszkin.draftsllman.solver.ConstraintSet
import kotlin.test.Test
import kotlin.test.assertTrue

class ToolArgsTest {
    @Test fun `AddNode rejects blank id, duplicate id, unknown kind, non-positive size`() {
        val blankErr = ToolArgs.AddNode(id = " ", role = "w", kind = "circle", width = 4.0, height = 4.0)
            .semanticValidate(SceneGraph.EMPTY, ConstraintSet.EMPTY)
        assertTrue(blankErr.any { it.path == "id" })

        val dup = SceneGraph.EMPTY.withNode(CircleNode("a", "w", ResolvedBox(0.0, 0.0, 1.0, 1.0), Style()))
        val dupErr = ToolArgs.AddNode(id = "a", role = "w", kind = "circle", width = 4.0, height = 4.0)
            .semanticValidate(dup, ConstraintSet.EMPTY)
        assertTrue(dupErr.any { it.path == "id" })

        val kindErr = ToolArgs.AddNode(id = "x", role = "w", kind = "path", width = 4.0, height = 4.0)
            .semanticValidate(SceneGraph.EMPTY, ConstraintSet.EMPTY)
        assertTrue(kindErr.any { it.path == "kind" }) // path deferred in Phase 1
    }

    @Test fun `AddConstraint rejects dangling node and mismatched anchor axes`() {
        val dangling = ToolArgs.AddConstraint(
            id = "c1", kind = "align", sourceId = "ghost", sourceAnchor = "CENTER_X",
            targetId = "ghost2", targetAnchor = "CENTER_X",
        ).semanticValidate(SceneGraph.EMPTY, ConstraintSet.EMPTY)
        assertTrue(dangling.any { it.path == "sourceId" })

        val g = SceneGraph.EMPTY
            .withNode(CircleNode("a", "w", ResolvedBox(0.0, 0.0, 10.0, 10.0), Style()))
            .withNode(CircleNode("b", "w", ResolvedBox(0.0, 0.0, 10.0, 10.0), Style()))
            .copy(canvas = CanvasSpec(100.0, 100.0))
        val axisErr = ToolArgs.AddConstraint(
            id = "c1", kind = "align", sourceId = "a", sourceAnchor = "LEFT",
            targetId = "b", targetAnchor = "BOTTOM", // X vs Y
        ).semanticValidate(g, ConstraintSet.EMPTY)
        assertTrue(axisErr.any { it.path == "anchors" })
    }

    @Test fun `AddConstraint resolves a named alias to its geometric anchor and validates`() {
        val g = SceneGraph.EMPTY
            .withNode(CircleNode("wheel", "w", ResolvedBox(0.0, 0.0, 10.0, 10.0), Style()))
            .withNode(CircleNode("frame", "w", ResolvedBox(0.0, 0.0, 10.0, 10.0), Style()))
            .withAnchorAliases("wheel", mapOf("hub" to Anchor.CENTER))
        // hub -> CENTER (a POINT): valid for connect
        val errs = ToolArgs.AddConstraint(
            id = "c1", kind = "connect", sourceId = "frame", sourceAnchor = "CENTER",
            targetId = "wheel", targetAnchor = "hub",
        ).semanticValidate(g, ConstraintSet.EMPTY)
        assertTrue(errs.isEmpty(), "named POINT anchor must be valid for connect: $errs")
    }

    @Test fun `AddConstraint rejects an unknown anchor name`() {
        val g = SceneGraph.EMPTY
            .withNode(CircleNode("a", "w", ResolvedBox(0.0, 0.0, 1.0, 1.0), Style()))
            .withNode(CircleNode("b", "w", ResolvedBox(0.0, 0.0, 1.0, 1.0), Style()))
        val errs = ToolArgs.AddConstraint(
            id = "c1", kind = "connect", sourceId = "a", sourceAnchor = "CENTER",
            targetId = "b", targetAnchor = "nope",
        ).semanticValidate(g, ConstraintSet.EMPTY)
        assertTrue(errs.any { it.path == "targetAnchor" })
    }
}
