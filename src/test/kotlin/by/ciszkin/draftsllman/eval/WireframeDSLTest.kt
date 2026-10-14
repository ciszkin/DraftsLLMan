package by.ciszkin.draftsllman.eval

import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.RectNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WireframeDSLTest {

    @Test
    fun buildIsDeterministicForSameSeed() {
        val a = WireframeDSL().build(7)
        val b = WireframeDSL().build(7)

        val idsAndBoxesA = a.truth.components.map { Triple(it.id, it.kind, it.box) }
        val idsAndBoxesB = b.truth.components.map { Triple(it.id, it.kind, it.box) }
        assertEquals(idsAndBoxesA, idsAndBoxesB, "same seed must produce identical truth components")
        assertEquals(a.truth.edges, b.truth.edges, "same seed must produce identical edges")
        assertEquals(a.truth.annotationIds, b.truth.annotationIds, "same seed must produce identical annotations")
    }

    @Test
    fun differentSeedsProduceDifferentBoxes() {
        val a = WireframeDSL().build(1)
        val b = WireframeDSL().build(2)

        val boxesA = a.truth.components.map { it.box }
        val boxesB = b.truth.components.map { it.box }
        assertTrue(
            boxesA.zip(boxesB).any { (ba, bb) -> ba != bb },
            "different seeds should differ in at least one component box",
        )
    }

    @Test
    fun truthBoxesMatchEmittedNodeBoxesAndKindsMatchNodeTypes() {
        val wireframe = WireframeDSL().build(5)
        val graph: SceneGraph = wireframe.graph

        for (comp in wireframe.truth.components) {
            val node = requireNotNull(graph.nodes[comp.id]) {
                "truth component ${comp.id} must exist in the scene graph"
            }
            assertEquals(node.box, comp.box, "box for ${comp.id} must match the emitted node box")
            val expectedKind = when (node) {
                is CircleNode -> PrimitiveKind.CIRCLE
                is RectNode -> PrimitiveKind.RECT
                is by.ciszkin.draftsllman.scene.LineNode -> PrimitiveKind.LINE
                is by.ciszkin.draftsllman.scene.CurveNode -> PrimitiveKind.PATH
                is by.ciszkin.draftsllman.scene.ShapeNode ->
                    if (node.closed) PrimitiveKind.POLYGON else PrimitiveKind.PATH
                else -> error("unexpected node type for ${comp.id}")
            }
            assertEquals(expectedKind, comp.kind, "kind for ${comp.id} must match node type")
        }
    }

    @Test
    fun annotationHandling() {
        val wireframe = WireframeDSL().build(3)
        val graph: SceneGraph = wireframe.graph

        assertTrue(wireframe.truth.annotationIds.isNotEmpty(), "annotationIds must be non-empty")
        val componentIds = wireframe.truth.components.map { it.id }.toSet()
        for (id in wireframe.truth.annotationIds) {
            assertFalse(componentIds.contains(id), "annotation $id must NOT appear in truth.components")
            assertTrue(graph.nodes.containsKey(id), "annotation $id must exist in the scene graph for Sketchify")
        }
    }

    @Test
    fun annotationsAreReachableForRendering() {
        val wireframe = WireframeDSL().build(4)
        val graph: SceneGraph = wireframe.graph

        // Sketchify renders via SkiaRenderer, which draws only nodes reachable from rootIds.
        // If an annotation is in `nodes` but NOT in rootIds, the wavy pointer marks never appear in
        // the sketch — and the eval's annotation-discrimination test would measure nothing.
        for (id in wireframe.truth.annotationIds) {
            assertTrue(
                graph.rootIds.contains(id),
                "annotation $id must be a root so Sketchify actually renders it (Skia draws only root-reachable nodes)",
            )
        }
    }

    @Test
    fun annotationsHaveVisibleCalloutGeometry() {
        val wireframe = WireframeDSL().build(6)
        val graph: SceneGraph = wireframe.graph

        // The annotations must be VISIBLE, hand-drawn CALLOUT marks (label dot + pointer arrow),
        // not invisible sub-pixel primitives — otherwise a model has nothing to exclude and the
        // annotation-discrimination score is vacuous.
        assertTrue(wireframe.truth.annotationIds.size >= 4, "two call-outs = at least 4 primitives")
        for (id in wireframe.truth.annotationIds) {
            val node = graph.nodes[id] ?: error("annotation $id missing from graph")
            val span = node.box.width + node.box.height
            assertTrue(
                span >= 24.0,
                "annotation $id must have visible geometry (span $span < 24px)",
            )
        }
        // A call-out pointer is a multi-point polyline with an arrowhead; the dot is a small circle.
        val ptr = graph.nodes["annotation_0_ptr"]
        assertIs<by.ciszkin.draftsllman.scene.ShapeNode>(ptr, "annotation_0_ptr must be a pointer polyline")
        assertTrue(ptr.points.size >= 4, "pointer must have an arrowhead (>= 4 points)")
        val dot = graph.nodes["annotation_0_dot"]
        assertIs<by.ciszkin.draftsllman.scene.CircleNode>(dot, "annotation_0_dot must be a label dot")
    }

    @Test
    fun hasContainsEdgeWithGeometricContainment() {
        val wireframe = WireframeDSL().build(11)
        val graph: SceneGraph = wireframe.graph

        val containsEdges = wireframe.truth.edges
            .filter { it.relation == EdgeRelation.CONTAINS }
        assertTrue(containsEdges.isNotEmpty(), "at least one CONTAINS edge is required")
        assertTrue(containsEdges.all { edge ->
            val fromBox = graph.nodes[edge.from]?.box
            val toBox = graph.nodes[edge.to]?.box
            fromBox != null && toBox != null && contains(fromBox, toBox)
        }, "every CONTAINS edge must be geometrically valid")
    }

    @Test
    fun semanticVarietyPresent() {
        val wireframe = WireframeDSL().build(9)
        val graph: SceneGraph = wireframe.graph

        val chartNodes = graph.nodes.values.filterIsInstance<CircleNode>().filter { it.semanticRole == "chart" }
        assertTrue(chartNodes.isNotEmpty(), "at least one CircleNode with role chart is required")

        val buttonNodes = graph.nodes.values.filterIsInstance<RectNode>().filter { it.semanticRole == "button" }
        assertTrue(buttonNodes.isNotEmpty(), "at least one RectNode with role button is required")
    }

    @Test
    fun `macro tier is a small recoverable slice with annotations`() {
        // The eval's difficulty tiering (§8): a MACRO slice has ~6-7 components (containers, chart,
        // one button, dividers, badge) plus call-out annotations — small enough for a vision model
        // to recover satisfiably, unlike the full 13-component detail tier.
        val wf = WireframeDSL().buildMacro(7)
        val n = wf.truth.components.size
        assertTrue(n in 6..8, "macro tier must be small, got $n: ${wf.truth.components.map { it.id }}")
        assertTrue(wf.truth.annotationIds.size >= 4, "macro tier keeps call-out annotations")
        val roles = wf.truth.components.map { it.role }.toSet()
        assertTrue(roles.containsAll(setOf("container", "chart", "divider")), "core zones present: $roles")
        // deterministic
        val again = WireframeDSL().buildMacro(7)
        assertEquals(wf.truth.components, again.truth.components)
    }

    @Test
    fun factoryForwardsToBuild() {
        val difficulty = Difficulty(componentCount = 8, jitterLevel = 1)
        val wireframe = synthWireframe(13, difficulty)
        assertTrue(wireframe.truth.components.isNotEmpty())
        assertTrue(wireframe.graph.nodes.isNotEmpty())
    }

    private fun contains(outer: ResolvedBox, inner: ResolvedBox): Boolean =
        inner.left >= outer.left && inner.right <= outer.right &&
            inner.top >= outer.top && inner.bottom <= outer.bottom
}
