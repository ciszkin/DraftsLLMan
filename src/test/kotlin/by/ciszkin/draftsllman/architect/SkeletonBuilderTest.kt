package by.ciszkin.draftsllman.architect

import by.ciszkin.draftsllman.scene.Anchor
import by.ciszkin.draftsllman.scene.AnchorAxis
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.LineNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.solver.SceneConstraint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SkeletonBuilderTest {
    @Test fun `build creates one node per component at the origin with its size`() {
        val skeleton = EmitSkeleton(components = listOf(
            SkeletonComponent("wheel", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
            SkeletonComponent("frame", "chassis", "line", 200.0, 100.0, mapOf("rear" to "BOTTOM_LEFT")),
        ))
        val graph = SkeletonBuilder.build(skeleton, CanvasSpec(500.0, 500.0))
        val wheel = graph.nodes["wheel"] as CircleNode
        val frame = graph.nodes["frame"] as LineNode
        assertEquals(ResolvedBox(0.0, 0.0, 80.0, 80.0), wheel.box)
        assertEquals(ResolvedBox(0.0, 0.0, 200.0, 100.0), frame.box)
        assertEquals(listOf("wheel", "frame"), graph.rootIds)
    }

    @Test fun `build records each component's resolved named anchors`() {
        val skeleton = EmitSkeleton(components = listOf(
            SkeletonComponent("wheel", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER", "top" to "TOP_CENTER")),
        ))
        val graph = SkeletonBuilder.build(skeleton)
        assertEquals(Anchor.CENTER, graph.anchorAliases["wheel"]?.get("hub"))
        assertEquals(Anchor.TOP_CENTER, graph.anchorAliases["wheel"]?.get("top"))
    }

    @Test fun `build resolves a connect constraint to SceneConstraint Connect`() {
        val skeleton = EmitSkeleton(
            components = listOf(
                SkeletonComponent("frame", "chassis", "line", 200.0, 100.0, mapOf("rear" to "BOTTOM_LEFT")),
                SkeletonComponent("wheel", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
            ),
            constraints = listOf(SkeletonConstraint("c1", "connect", "frame:rear", "wheel:hub")),
        )
        val graph = SkeletonBuilder.build(skeleton)
        val c = graph.constraints.constraints.single()
        assertTrue(c is SceneConstraint.Connect)
        assertEquals("frame", c.nodeA); assertEquals(Anchor.BOTTOM_LEFT, c.anchorA)
        assertEquals("wheel", c.nodeB); assertEquals(Anchor.CENTER, c.anchorB)
    }

    @Test fun `build converts distance_ratio relative to canvas_width into pixels`() {
        val skeleton = EmitSkeleton(
            components = listOf(
                SkeletonComponent("a", "r", "circle", 40.0, 40.0, mapOf("cx" to "CENTER_X")),
                SkeletonComponent("b", "r", "circle", 40.0, 40.0, mapOf("cx" to "CENTER_X")),
            ),
            constraints = listOf(
                SkeletonConstraint("d1", "distance_ratio", "a:cx", "b:cx", ratio = 0.5, relativeTo = "canvas_width"),
            ),
        )
        val graph = SkeletonBuilder.build(skeleton, CanvasSpec(500.0, 400.0))
        val c = graph.constraints.constraints.single()
        assertTrue(c is SceneConstraint.Distance)
        assertEquals(250.0, c.pixels) // 0.5 * canvas width 500
    }

    @Test fun `build converts distance_ratio relative to canvas_height into pixels`() {
        val skeleton = EmitSkeleton(
            components = listOf(
                SkeletonComponent("a", "r", "circle", 40.0, 40.0, mapOf("cy" to "CENTER_Y")),
                SkeletonComponent("b", "r", "circle", 40.0, 40.0, mapOf("cy" to "CENTER_Y")),
            ),
            constraints = listOf(
                SkeletonConstraint("d1", "distance_ratio", "a:cy", "b:cy", ratio = 0.5, relativeTo = "canvas_height"),
            ),
        )
        val graph = SkeletonBuilder.build(skeleton, CanvasSpec(500.0, 400.0))
        val c = graph.constraints.constraints.single()
        assertTrue(c is SceneConstraint.Distance)
        assertEquals(200.0, c.pixels) // 0.5 * canvas height 400
    }

    @Test fun `build converts distance_ratio ratio one to the full canvas dimension`() {
        val skeleton = EmitSkeleton(
            components = listOf(
                SkeletonComponent("a", "r", "circle", 40.0, 40.0, mapOf("cx" to "CENTER_X")),
                SkeletonComponent("b", "r", "circle", 40.0, 40.0, mapOf("cx" to "CENTER_X")),
            ),
            constraints = listOf(
                SkeletonConstraint("d1", "distance_ratio", "a:cx", "b:cx", ratio = 1.0, relativeTo = "canvas_width"),
            ),
        )
        val graph = SkeletonBuilder.build(skeleton, CanvasSpec(500.0, 400.0))
        val c = graph.constraints.constraints.single()
        assertTrue(c is SceneConstraint.Distance)
        assertEquals(500.0, c.pixels) // 1.0 * canvas width 500 == full dimension
    }

    @Test fun `parseRef splits componentId and anchorName`() {
        assertEquals("wheel" to "hub", SkeletonBuilder.parseRef("wheel:hub"))
    }

    @Test fun `build throws on a malformed anchor ref`() {
        val skeleton = EmitSkeleton(
            components = listOf(SkeletonComponent("a", "r", "circle", 10.0, 10.0)),
            constraints = listOf(SkeletonConstraint("c1", "connect", "nope", "a:center")),
        )
        assertFailsWith<IllegalArgumentException> { SkeletonBuilder.build(skeleton) }
    }

    @Test fun `build throws on an unknown alias`() {
        val skeleton = EmitSkeleton(
            components = listOf(
                SkeletonComponent("a", "r", "circle", 10.0, 10.0),
                SkeletonComponent("b", "r", "circle", 10.0, 10.0),
            ),
            constraints = listOf(SkeletonConstraint("c1", "connect", "a:unknownAlias", "b:center")),
        )
        assertFailsWith<IllegalStateException> { SkeletonBuilder.build(skeleton) }
    }

    @Test fun `build resolves a lowercase alias geometric value case-insensitively`() {
        // Reproduces the residual Slices 3/4 divergence: the validator accepted a lowercase alias VALUE
        // (Anchor.valueOf(geometric.uppercase())), but the builder used Anchor.valueOf(geometricName)
        // (case-sensitive) and threw. Both now route through resolveAliasMap, so "center" -> CENTER.
        val skeleton = EmitSkeleton(components = listOf(
            SkeletonComponent("wheel", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "center")),
        ))
        val graph = SkeletonBuilder.build(skeleton)
        assertEquals(Anchor.CENTER, graph.anchorAliases["wheel"]?.get("hub"))
    }

    @Test fun `build drops an alias whose geometric value is invalid instead of throwing`() {
        // The broader validator<->builder divergence: the validator DROPS invalid alias values (and
        // reports "unknown anchor" only if a constraint references one); the builder must match, not
        // throw — an unreferenced bad alias must not crash the build (matters for SafeHarbor's modified
        // fallback skeletons, which must never throw).
        val skeleton = EmitSkeleton(components = listOf(
            SkeletonComponent("wheel", "wheel", "circle", 80.0, 80.0,
                mapOf("hub" to "CENTER", "typo" to "middel")),
        ))
        val graph = SkeletonBuilder.build(skeleton)  // must not throw
        assertEquals(Anchor.CENTER, graph.anchorAliases["wheel"]?.get("hub"))
        assertEquals(null, graph.anchorAliases["wheel"]?.get("typo"))
    }

    @Test fun `build maps contain to SceneConstraint Contain preserving the margin fraction`() {
        val skeleton = EmitSkeleton(
            components = listOf(
                SkeletonComponent("wheel", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
                SkeletonComponent("seat", "saddle", "rect", 40.0, 10.0),
            ),
            constraints = listOf(
                SkeletonConstraint("k1", "connect", "seat:CENTER", "wheel:hub"),
                SkeletonConstraint("k2", "contain", "seat", "canvas", margin = 0.02),
            ),
        )
        val graph = SkeletonBuilder.build(skeleton, CanvasSpec(500.0, 400.0))
        val c = graph.constraints.constraints.filterIsInstance<SceneConstraint.Contain>().single()
        assertEquals("seat", c.nodeId)
        assertEquals(0.02, c.margin) // the FRACTION survives build; the solver multiplies by canvas dims
    }

    @Test fun `a validator-approved contain skeleton builds without throwing`() {
        // The validator->builder guarantee must hold for the new kind too (see SkeletonValidationTest).
        val skeleton = EmitSkeleton(
            components = listOf(
                SkeletonComponent("wheel", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
                SkeletonComponent("seat", "saddle", "rect", 40.0, 10.0),
            ),
            constraints = listOf(
                SkeletonConstraint("k1", "connect", "seat:CENTER", "wheel:hub"),
                SkeletonConstraint("k2", "contain", "seat", "canvas", margin = 0.02),
            ),
        )
        assertTrue(validateSkeleton(skeleton, CanvasSpec(500.0, 400.0)).isEmpty())
        SkeletonBuilder.build(skeleton, CanvasSpec(500.0, 400.0))  // must not throw
    }

    @Test fun `build maps relative_size to SceneConstraint RelativeSize`() {
        val skeleton = EmitSkeleton(
            components = listOf(
                SkeletonComponent("rear", "wheel", "circle", 60.0, 60.0),
                SkeletonComponent("front", "wheel", "circle", 96.0, 96.0),
            ),
            constraints = listOf(SkeletonConstraint("rs", "relative_size", "front", "rear", ratio = 1.6)),
        )
        val graph = SkeletonBuilder.build(skeleton, CanvasSpec(500.0, 500.0))
        val c = graph.constraints.constraints.filterIsInstance<SceneConstraint.RelativeSize>().single()
        assertEquals("front", c.nodeA); assertEquals("rear", c.nodeB); assertEquals(1.6, c.ratio)
    }

    @Test fun `build maps distribute to SceneConstraint Distribute with the axis`() {
        val skeleton = EmitSkeleton(
            components = listOf(
                SkeletonComponent("p1", "picket", "rect", 20.0, 60.0),
                SkeletonComponent("p2", "picket", "rect", 20.0, 60.0),
                SkeletonComponent("p3", "picket", "rect", 20.0, 60.0),
            ),
            constraints = listOf(SkeletonConstraint("d", "distribute", "", "",
                components = listOf("p1", "p2", "p3"), along = "height")),
        )
        val graph = SkeletonBuilder.build(skeleton, CanvasSpec(500.0, 500.0))
        val c = graph.constraints.constraints.filterIsInstance<SceneConstraint.Distribute>().single()
        assertEquals(listOf("p1", "p2", "p3"), c.nodeIds)
        assertEquals(AnchorAxis.Y, c.axis)
    }

    @Test fun `validator-approved relative_size and distribute skeletons build without throwing`() {
        val skeleton = EmitSkeleton(
            components = listOf(
                SkeletonComponent("rear", "wheel", "circle", 60.0, 60.0),
                SkeletonComponent("front", "wheel", "circle", 96.0, 96.0),
                SkeletonComponent("p1", "picket", "rect", 20.0, 60.0),
                SkeletonComponent("p2", "picket", "rect", 20.0, 60.0),
                SkeletonComponent("p3", "picket", "rect", 20.0, 60.0),
            ),
            constraints = listOf(
                SkeletonConstraint("rs", "relative_size", "front", "rear", ratio = 1.6),
                SkeletonConstraint("d", "distribute", "", "",
                    components = listOf("p1", "p2", "p3"), along = "width"),
            ),
        )
        assertTrue(validateSkeleton(skeleton, CanvasSpec(500.0, 500.0)).isEmpty())
        SkeletonBuilder.build(skeleton, CanvasSpec(500.0, 500.0))  // must not throw
    }
}
