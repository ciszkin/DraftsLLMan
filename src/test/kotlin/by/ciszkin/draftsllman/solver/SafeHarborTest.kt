package by.ciszkin.draftsllman.solver

import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import by.ciszkin.draftsllman.agent.ScriptedPromptExecutor
import by.ciszkin.draftsllman.architect.EmitSkeleton
import by.ciszkin.draftsllman.architect.SkeletonBuilder
import by.ciszkin.draftsllman.architect.SkeletonComponent
import by.ciszkin.draftsllman.architect.SkeletonConstraint
import by.ciszkin.draftsllman.coordination.Coordinator
import by.ciszkin.draftsllman.engine.DraftsEngine
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.LineNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.SceneNode
import by.ciszkin.draftsllman.scene.Style
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Safe-Harbor tests (Zero-Coordinate Slice 6, Task 4): the model-independent safety net that prevents a
 * collapsed/unsatisfiable skeleton from rendering as one blob. Covers [SafeHarbor.isDegenerate],
 * [SafeHarbor.spread], and the end-to-end [Coordinator] acceptance test — a connect-only skeleton that
 * collapses under the solver comes out of the Coordinator with the wheels at DIFFERENT cx.
 */
class SafeHarborTest {
    private val canvas = CanvasSpec(500.0, 500.0)

    private fun circle(id: String, x: Double, y: Double, w: Double, h: Double) =
        CircleNode(id, "n", ResolvedBox(x, y, w, h), Style())

    /** A line spanning (x,y)→(x+w,y+h), mirroring SkeletonBuilder's diagonal emit. */
    private fun line(id: String, x: Double, y: Double, w: Double, h: Double) =
        LineNode(id, "n", start = x to y, end = (x + w) to (y + h))

    private fun graphOf(vararg nodes: SceneNode): SceneGraph =
        SceneGraph(nodes = nodes.associateBy { it.id }, rootIds = nodes.map { it.id })

    // ---------- isDegenerate ----------

    @Test fun `two coincident centers are degenerate`() {
        assertTrue(SafeHarbor.isDegenerate(graphOf(circle("a", 0.0, 0.0, 40.0, 40.0), circle("b", 0.0, 0.0, 40.0, 40.0))))
    }

    @Test fun `well-separated nodes are not degenerate`() {
        assertFalse(SafeHarbor.isDegenerate(graphOf(circle("a", 0.0, 0.0, 40.0, 40.0), circle("b", 200.0, 200.0, 40.0, 40.0))))
    }

    @Test fun `a collapsed-to-point node is degenerate`() {
        // Both dimensions ~0 → true point, not a legitimate 1D line.
        assertTrue(SafeHarbor.isDegenerate(graphOf(circle("a", 0.0, 0.0, 0.0, 0.0))))
    }

    @Test fun `a horizontal line component is not degenerate`() {
        // 1D: width>0, height=0 — a legitimate line (e.g. a bicycle frame) must not trip the area check.
        assertFalse(SafeHarbor.isDegenerate(graphOf(line("bar", 0.0, 250.0, 200.0, 0.0))))
    }

    @Test fun `a vertical line component is not degenerate`() {
        // 1D: width=0, height>0 — also legitimate.
        assertFalse(SafeHarbor.isDegenerate(graphOf(line("bar", 250.0, 0.0, 0.0, 200.0))))
    }

    @Test fun `a single node is not degenerate`() {
        assertFalse(SafeHarbor.isDegenerate(graphOf(circle("a", 10.0, 10.0, 40.0, 40.0))))
    }

    @Test fun `isDegenerate respects the eps threshold`() {
        // Centers (20,20) & (65,20) → 45 apart: fine at eps=1, degenerate at eps=100.
        val g = graphOf(circle("a", 0.0, 0.0, 40.0, 40.0), circle("b", 45.0, 0.0, 40.0, 40.0))
        assertFalse(SafeHarbor.isDegenerate(g, eps = 1.0))
        assertTrue(SafeHarbor.isDegenerate(g, eps = 100.0))
    }

    @Test fun `degeneracy names the coincident pair`() {
        val g = graphOf(circle("a", 0.0, 0.0, 10.0, 10.0), circle("b", 0.0, 0.0, 10.0, 10.0))
        val d = SafeHarbor.degeneracy(g)
        assertEquals(listOf("a", "b"), d?.componentIds)
        assertEquals("coincident centers", d?.reason)
    }

    @Test fun `degeneracy names the point-collapsed node`() {
        val g = graphOf(circle("tiny", 5.0, 5.0, 0.0, 0.0), circle("ok", 100.0, 100.0, 10.0, 10.0))
        val d = SafeHarbor.degeneracy(g)
        assertEquals(listOf("tiny"), d?.componentIds)
        assertEquals("collapsed to a point", d?.reason)
    }

    @Test fun `degeneracy is null for a well-separated layout`() {
        val g = graphOf(circle("a", 0.0, 0.0, 10.0, 10.0), circle("b", 200.0, 200.0, 10.0, 10.0))
        assertNull(SafeHarbor.degeneracy(g))
    }

    @Test fun `a valid connected bicycle skeleton is not degenerate after solve`() {
        // The Slice-5 bicycle (two wheels connected to a frame's axles, incl. a horizontal-ish LINE frame)
        // solves to a separated layout, so the Safe-Harbor must NOT fire and overwrite it. Guards against
        // the 1D-line false-positive regressing the Task-3 distance_ratio fix in production.
        val bicycle = EmitSkeleton(
            components = listOf(
                SkeletonComponent("rear", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
                SkeletonComponent("front", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
                SkeletonComponent("frame", "chassis", "line", 200.0, 100.0,
                    mapOf("rearAxle" to "BOTTOM_LEFT", "frontAxle" to "BOTTOM_RIGHT")),
            ),
            constraints = listOf(
                SkeletonConstraint("lr", "connect", "frame:rearAxle", "rear:hub"),
                SkeletonConstraint("lf", "connect", "frame:frontAxle", "front:hub"),
            ),
        )
        assertFalse(SafeHarbor.isDegenerate(solvePhase2(bicycle)),
            "valid bicycle layout must not be flagged degenerate (would override a correct solve)")
    }

    // ---------- spread ----------

    @Test fun `spread places three components in distinct evenly-spaced slots`() {
        val skeleton = EmitSkeleton(
            components = listOf(
                SkeletonComponent("c", "r", "circle", 80.0, 60.0),
                SkeletonComponent("a", "r", "rect", 80.0, 60.0),
                SkeletonComponent("b", "r", "circle", 80.0, 60.0),
            ),
        )
        val spread = SafeHarbor.spread(skeleton, canvas)

        // id-sorted order → a (i=0), b (i=1), c (i=2); N=3 → slots at (i+1)*500/4.
        val expected = mapOf("a" to 125.0, "b" to 250.0, "c" to 375.0)
        for ((id, slotCx) in expected) {
            val box = spread.nodes[id]?.box ?: fail("missing node $id")
            assertApprox(slotCx, box.cx, 1e-6, "$id cx")
            assertApprox(250.0, box.cy, 1e-6, "$id cy")
            assertTrue(box.x >= 0.0 && box.y >= 0.0, "$id below canvas: $box")
            assertTrue(box.right <= canvas.width + 1e-6 && box.bottom <= canvas.height + 1e-6, "$id above canvas: $box")
        }
        // Centers distinct.
        val centers = expected.keys.map { spread.nodes[it]!!.box.cx }
        assertEquals(3, centers.toSet().size)
    }

    @Test fun `spread preserves declared size for non-oversized components`() {
        val skeleton = EmitSkeleton(components = listOf(SkeletonComponent("a", "r", "rect", 80.0, 60.0)))
        val box = SafeHarbor.spread(skeleton, canvas).nodes["a"]!!.box
        assertApprox(80.0, box.width, 1e-6, "width")
        assertApprox(60.0, box.height, 1e-6, "height")
    }

    @Test fun `spread clamps an oversized component proportionally`() {
        // N=1 → maxDim = 0.8 * 500 / 2 = 200. A 400x200 node's max dim (400) exceeds it → scaled by 0.5.
        val skeleton = EmitSkeleton(components = listOf(SkeletonComponent("big", "r", "rect", 400.0, 200.0)))
        val box = SafeHarbor.spread(skeleton, canvas).nodes["big"]!!.box
        assertApprox(200.0, box.width, 1e-6, "clamped width")
        assertApprox(100.0, box.height, 1e-6, "clamped height (aspect preserved)")
    }

    @Test fun `spread recenters a non-oversized line preserving length and orientation`() {
        // A line component spans its declared box as (0,0)→(w,h); spread rebuilds start/end to re-center it.
        val skeleton = EmitSkeleton(components = listOf(SkeletonComponent("bar", "r", "line", 120.0, 40.0)))
        val node = SafeHarbor.spread(skeleton, canvas).nodes["bar"]!!
        // Slot for N=1, i=0 → cx = 250, cy = 250; line not oversized (len≈126.5 < maxDim=200) → size stays.
        assertApprox(250.0, node.box.cx, 1e-6, "line cx")
        assertApprox(250.0, node.box.cy, 1e-6, "line cy")
        assertApprox(120.0, node.box.width, 1e-6, "line width")
        assertApprox(40.0, node.box.height, 1e-6, "line height")
    }

    @Test fun `spread clamps an oversized line to maxDim preserving angle, centered and in bounds`() {
        // N=1 → maxDim = 0.8*500/2 = 200. A 400x40 line has length hypot(400,40)≈402 > 200 → clamped.
        val skeleton = EmitSkeleton(components = listOf(SkeletonComponent("bar", "r", "line", 400.0, 40.0)))
        val node = SafeHarbor.spread(skeleton, canvas).nodes["bar"]!!
        val line = node as? LineNode ?: fail("expected LineNode, got ${node::class}")
        val newDx = line.end.first - line.start.first
        val newDy = line.end.second - line.start.second
        val len = hypot(newDx, newDy)
        assertTrue(len <= 200.0 + 1e-6, "line length $len must be ≤ maxDim=200")
        // Centered on the slot (250, 250).
        assertApprox(250.0, (line.start.first + line.end.first) / 2.0, 1e-6, "line center cx")
        assertApprox(250.0, (line.start.second + line.end.second) / 2.0, 1e-6, "line center cy")
        // Orientation preserved: original slope 40/400 = 0.1.
        assertApprox(40.0 / 400.0, newDy / newDx, 1e-6, "line slope (orientation preserved)")
        // Within canvas bounds (no overflow past x=500).
        val minX = minOf(line.start.first, line.end.first)
        val maxX = maxOf(line.start.first, line.end.first)
        assertTrue(minX >= 0.0 && maxX <= canvas.width, "line x-range [$minX,$maxX] out of [0,${canvas.width}]")
    }

    @Test fun `spread does not re-solve and keeps adjacent slots non-overlapping`() {
        val skeleton = EmitSkeleton(
            components = listOf(
                SkeletonComponent("a", "r", "circle", 100.0, 100.0),
                SkeletonComponent("b", "r", "circle", 100.0, 100.0),
            ),
        )
        val spread = SafeHarbor.spread(skeleton, canvas)
        val a = spread.nodes["a"]!!.box
        val b = spread.nodes["b"]!!.box
        // Slots at 500/3≈166.67 and 1000/3≈333.33; centers ~166.67 apart, both 100 wide → no overlap.
        assertTrue(a.cx < b.cx)
        assertTrue(hypot(a.cx - b.cx, a.cy - b.cy) > 100.0, "slots overlap: a=$a b=$b")
    }

    // ---------- connectHonoringSpread ----------

    @Test fun `connectHonoringSpread separates wheels and keeps the frame spanning on an over-constrained bicycle`() {
        // Over-constrained bicycle: the frame (w=240) spans rear↔front via rearAxle/frontAxle, AND a
        // downtube (w=100) also bridges frame↔rear. Both are rigid rects (line members would be stretchable
        // struts now). The downtube's pinned geometry forces frame.x+220 == frame.x (see below) → the raw
        // Phase-2 solve is Unsatisfiable. connectHonoringSpread must drop the cycle-closing back-edge
        // (downtube↔rear), KEEP both frame↔wheel connects, and yield a layout where the wheels are
        // SEPARATED and the frame's axles COINCIDE with the wheel hubs — a recognizable bicycle topology.
        // The old row-spread would disconnect them (frame in one slot, wheels in others), so this is the
        // acceptance proof that connect topology is honored.
        val bicycle = EmitSkeleton(
            components = listOf(
                SkeletonComponent("rear", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
                SkeletonComponent("front", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
                SkeletonComponent("frame", "chassis", "rect", 240.0, 120.0, mapOf(
                    "rearAxle" to "BOTTOM_LEFT", "frontAxle" to "BOTTOM_RIGHT", "mount" to "CENTER")),
                SkeletonComponent("downtube", "chassis", "rect", 100.0, 60.0,
                    mapOf("head" to "TOP_LEFT", "tail" to "BOTTOM_RIGHT")),
            ),
            constraints = listOf(
                SkeletonConstraint("c1", "connect", "frame:rearAxle", "rear:hub"),
                SkeletonConstraint("c2", "connect", "frame:frontAxle", "front:hub"),
                SkeletonConstraint("c3", "connect", "frame:mount", "downtube:head"),
                SkeletonConstraint("c4", "connect", "downtube:tail", "rear:hub"),
            ),
        )
        // Precondition: the raw Phase-2 solve really IS unsatisfiable (proves we exercise the path).
        val rawGraph = SkeletonBuilder.build(bicycle, canvas)
        val (_, rawResult) = DraftsEngine().solveInto(rawGraph, pinSizes = true)
        assertTrue(rawResult is SolveResult.Unsatisfiable,
            "precondition: over-constrained bicycle must be unsatisfiable, got $rawResult")

        val harbor = SafeHarbor.connectHonoringSpread(bicycle, canvas)
        val solved = harbor.graph
        assertEquals(SafeHarborTier.CONNECT_TREE, harbor.tier,
            "over-constrained bicycle should resolve via the spanning-tree tier, got ${harbor.tier}")

        // (a) wheels separated (different cx) — not stacked.
        val rearBox = solved.nodes["rear"]!!.box
        val frontBox = solved.nodes["front"]!!.box
        assertTrue(abs(rearBox.cx - frontBox.cx) > 1.0,
            "wheels not separated: rear.cx=${rearBox.cx}, front.cx=${frontBox.cx}")

        // (b) frame SPANS the wheels: rearAxle ≈ rear hub, frontAxle ≈ front hub (connect honored).
        val frameBox = solved.nodes["frame"]!!.box
        val rearAxle = frameBox.x to (frameBox.y + frameBox.height)                  // BOTTOM_LEFT
        val frontAxle = (frameBox.x + frameBox.width) to (frameBox.y + frameBox.height) // BOTTOM_RIGHT
        assertPointApprox(rearAxle, rearBox.cx to rearBox.cy, 1.0, "frame rearAxle vs rear hub")
        assertPointApprox(frontAxle, frontBox.cx to frontBox.cy, 1.0, "frame frontAxle vs front hub")

        // (c) not degenerate overall.
        assertFalse(SafeHarbor.isDegenerate(solved), "connect-honoring layout must not be degenerate")
    }

    @Test fun `connectHonoringSpread separates wheels and spans frame on a clean connected bicycle`() {
        // The clean case (no conflict): the normal solve already works, and connectHonoringSpread
        // (callable directly, not only via Safe-Harbor trigger) must preserve the connected topology.
        val bicycle = EmitSkeleton(
            components = listOf(
                SkeletonComponent("rear", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
                SkeletonComponent("front", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
                SkeletonComponent("frame", "chassis", "line", 240.0, 120.0,
                    mapOf("rearAxle" to "BOTTOM_LEFT", "frontAxle" to "BOTTOM_RIGHT")),
            ),
            constraints = listOf(
                SkeletonConstraint("lr", "connect", "frame:rearAxle", "rear:hub"),
                SkeletonConstraint("lf", "connect", "frame:frontAxle", "front:hub"),
            ),
        )
        val harbor = SafeHarbor.connectHonoringSpread(bicycle, canvas)
        val solved = harbor.graph
        assertEquals(SafeHarborTier.CONNECT_TREE, harbor.tier)
        val rearBox = solved.nodes["rear"]!!.box
        val frontBox = solved.nodes["front"]!!.box
        assertTrue(abs(rearBox.cx - frontBox.cx) > 1.0, "wheels not separated")
        val frameBox = solved.nodes["frame"]!!.box
        assertPointApprox(frameBox.x to (frameBox.y + frameBox.height), rearBox.cx to rearBox.cy, 1.0, "rear axle vs hub")
        assertPointApprox((frameBox.x + frameBox.width) to (frameBox.y + frameBox.height),
            frontBox.cx to frontBox.cy, 1.0, "front axle vs hub")
        assertFalse(SafeHarbor.isDegenerate(solved))
    }

    @Test fun `connectHonoringSpread falls back to row-spread when sibling stacking is inherent`() {
        // Two leaves A, B both connect to hub H at H.CENTER (a true stack): the REQUIRED connect
        // coincidence pins A.cx == B.cx == hub.cx, so injecting a distance_ratio between A and B would
        // CONTRADICT it (REQUIRED vs REQUIRED) — even the connect-honoring solve is unsatisfiable.
        // Must fall back to the deterministic disconnected row-spread (non-overlapping).
        val stacked = EmitSkeleton(
            components = listOf(
                SkeletonComponent("hub", "body", "rect", 100.0, 100.0, mapOf("joint" to "CENTER")),
                SkeletonComponent("a", "leaf", "circle", 60.0, 60.0, mapOf("core" to "CENTER")),
                SkeletonComponent("b", "leaf", "circle", 60.0, 60.0, mapOf("core" to "CENTER")),
            ),
            constraints = listOf(
                SkeletonConstraint("ca", "connect", "hub:joint", "a:core"),
                SkeletonConstraint("cb", "connect", "hub:joint", "b:core"),
            ),
        )
        val harbor = SafeHarbor.connectHonoringSpread(stacked, canvas)
        val solved = harbor.graph
        assertEquals(SafeHarborTier.ROW_SPREAD, harbor.tier,
            "inherent stacking must fall through to row-spread, got ${harbor.tier}")
        // Row-spread: each component at a distinct cx (disconnected but non-overlapping).
        val cxs = listOf("hub", "a", "b").map { solved.nodes[it]!!.box.cx }
        assertEquals(3, cxs.toSet().size, "row-spread fallback should place each at a distinct cx, got $cxs")
        assertFalse(SafeHarbor.isDegenerate(solved))
    }

    @Test fun `connectHonoringSpread stretches a zero-width hub to separate its leaves`() {
        // hub (width 0) connects A at LEFT_CENTER and B at RIGHT_CENTER — but width 0 collapses both
        // anchors to the same x, so the tier-1 (pinned) solve is degenerate. Tier 2 injects a sibling
        // distance_ratio AND un-pins sizes so the hub can stretch: A and B end up separated. This is
        // the case the injected-separation mechanism is designed for.
        val collapsed = EmitSkeleton(
            components = listOf(
                SkeletonComponent("hub", "body", "rect", 0.0, 100.0,
                    mapOf("port" to "LEFT_CENTER", "starboard" to "RIGHT_CENTER")),
                SkeletonComponent("a", "leaf", "circle", 60.0, 60.0, mapOf("core" to "CENTER")),
                SkeletonComponent("b", "leaf", "circle", 60.0, 60.0, mapOf("core" to "CENTER")),
            ),
            constraints = listOf(
                SkeletonConstraint("cl", "connect", "hub:port", "a:core"),
                SkeletonConstraint("cr", "connect", "hub:starboard", "b:core"),
            ),
        )
        // Precondition: the tree-only pinned solve IS degenerate (zero-width hub → A, B coincide),
        // i.e. tier 1 cannot handle it and tier 2 (stretch) is required.
        assertTrue(SafeHarbor.isDegenerate(solvePhase2(collapsed)),
            "precondition: zero-width hub should collapse A and B under a pinned solve")

        val harbor = SafeHarbor.connectHonoringSpread(collapsed, canvas)
        val solved = harbor.graph
        assertEquals(SafeHarborTier.CONNECT_SEPARATION, harbor.tier,
            "zero-width hub should resolve via the sibling-separation tier, got ${harbor.tier}")
        val aCx = solved.nodes["a"]!!.box.cx
        val bCx = solved.nodes["b"]!!.box.cx
        assertTrue(abs(aCx - bCx) > 1.0, "tier-2 separation should spread A and B: aCx=$aCx bCx=$bCx")
        assertFalse(SafeHarbor.isDegenerate(solved))
    }

    @Test fun `connectHonoringSpread keeps distance_ratio-align canvas anchors so wheels do not collapse`() {
        // The real-world defect: the two wheels are positioned ONLY by a `distance_ratio` wheelbase + an
        // `align` on Y (the pattern the Architect teaches) — they are NOT connected to each other. A
        // separate rigid-rect cycle (frame↔fork↔stem) makes the full Phase-2 solve Unsatisfiable. (A line
        // cycle would now self-heal via stretchable struts — Safe-Harbor's tree tier is only reachable via
        // rigid-body cycles.) The old (broken) tryConnectSolve DROPPED the distance_ratio/align, so with no
        // anchor the disconnected wheels floated to the origin and coincided → isDegenerate → null →
        // row-spread (disconnected, wheelbase 83 = a row slot gap, not 250). Keeping the anchors (fix) lets
        // the spanning-tree tier solve with the wheelbase intact: wheels separated by ~250, anchored in-canvas,
        // NOT row-spread.
        val skeleton = EmitSkeleton(
            components = listOf(
                SkeletonComponent("rear", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
                SkeletonComponent("front", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
                SkeletonComponent("frame", "tube", "rect", 200.0, 60.0, mapOf("head" to "LEFT_CENTER", "tail" to "RIGHT_CENTER")),
                SkeletonComponent("fork", "tube", "rect", 160.0, 60.0, mapOf("head" to "LEFT_CENTER", "tail" to "RIGHT_CENTER")),
                SkeletonComponent("stem", "tube", "rect", 120.0, 60.0, mapOf("head" to "LEFT_CENTER", "tail" to "RIGHT_CENTER")),
            ),
            constraints = listOf(
                // Canvas anchors (these are what the old impl wrongly dropped):
                SkeletonConstraint("wb", "distance_ratio", "rear:CENTER_X", "front:CENTER_X", ratio = 0.5, relativeTo = "canvas_width"),
                SkeletonConstraint("ay", "align", "rear:CENTER_Y", "front:CENTER_Y"),
                // A connect CYCLE among the tubes → makes the full solve Unsatisfiable. The spanning tree
                // drops one back-edge; the remaining two-connect tree is satisfiable.
                SkeletonConstraint("e1", "connect", "frame:head", "fork:tail"),
                SkeletonConstraint("e2", "connect", "fork:head", "stem:tail"),
                SkeletonConstraint("e3", "connect", "stem:head", "frame:tail"),
            ),
        )
        // Precondition: the full Phase-2 solve is unsatisfiable (the rigid-rect cycle).
        val rawGraph = SkeletonBuilder.build(skeleton, canvas)
        val (_, rawResult) = DraftsEngine().solveInto(rawGraph, pinSizes = true)
        assertTrue(rawResult is SolveResult.Unsatisfiable,
            "precondition: rigid-rect cycle must make the full solve unsatisfiable, got $rawResult")

        val harbor = SafeHarbor.connectHonoringSpread(skeleton, canvas)
        // The defect fix: connect-honoring SUCCEEDS (does NOT fall through to row-spread).
        assertEquals(SafeHarborTier.CONNECT_TREE, harbor.tier,
            "anchored wheels should resolve via the spanning-tree tier, not row-spread; got ${harbor.tier}")
        val solved = harbor.graph

        // The wheelbase distance_ratio is honored (~250 = 0.5 * 500), not the row-spread slot gap (~83).
        val rearBox = solved.nodes["rear"]!!.box
        val frontBox = solved.nodes["front"]!!.box
        assertApprox(250.0, abs(frontBox.cx - rearBox.cx), 1.0, "wheelbase (distance_ratio honored)")
        assertFalse(SafeHarbor.isDegenerate(solved), "anchored layout must not be degenerate")

        // Anchored in-canvas: the wheels (the components the distance_ratio/align position) sit inside
        // the canvas — not floating off-canvas the way the un-anchored row-spread / collapse would. (The
        // salvaged tube sub-assembly has no anchors of its own and may drift; that is expected — only the
        // wheels are what the test is asserting anchoring for.)
        for (id in listOf("rear", "front")) {
            val b = solved.nodes[id]!!.box
            assertTrue(b.cx in 0.0..canvas.width && b.cy in 0.0..canvas.height,
                "$id center (${b.cx}, ${b.cy}) outside canvas [0,${canvas.width}]x[0,${canvas.height}]")
        }
    }

    // ---------- Coordinator integration (acceptance test) ----------

    @Test fun `Safe-Harbor separates collapsed wheels end-to-end through the Coordinator`() {
        // ONLY connect (no distance_ratio): rear.hub ←connect→ front.hub forces both centers together →
        // the Phase-2 solve is degenerate (two coincident wheels = one blob). The Safe-Harbor must spread
        // them so the exported SVG shows the two wheels at DIFFERENT cx.
        val collapsed = EmitSkeleton(
            components = listOf(
                SkeletonComponent("rear", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
                SkeletonComponent("front", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
            ),
            constraints = listOf(SkeletonConstraint("c", "connect", "rear:hub", "front:hub")),
        )
        // Sanity: the raw Phase-2 solve of this skeleton IS degenerate (proves we exercise the Safe-Harbor).
        val raw = solvePhase2(collapsed)
        assertTrue(SafeHarbor.isDegenerate(raw), "precondition: skeleton should collapse under the solver")

        val model = LLModel(provider = LLMProvider.Ollama, id = "test")
        val json = Json { ignoreUnknownKeys = false; explicitNulls = false; classDiscriminator = "type" }
        val scripted = ScriptedPromptExecutor().apply {
            // The feasibility gate refuses the first two collapsed emits (degenerate diagnostic);
            // the third hits MAX_EMIT_ATTEMPTS and stashes the last skeleton regardless (spec §4.3),
            // which is what reaches the Coordinator — whose Phase-2 solve is degenerate -> Safe-Harbor.
            repeat(3) { enqueueToolCall("emit_skeleton", json.encodeToString(EmitSkeleton.serializer(), collapsed)) }
            enqueueDone() // architect finishes
            enqueueDone() // rear Draftsman: empty
            enqueueDone() // front Draftsman: empty
        }
        val outcome = Coordinator(scripted, model, canvas).run("two wheels")

        assertTrue(outcome.svg.contains("""<circle id="rear"""), "rear wheel missing: ${outcome.svg}")
        assertTrue(outcome.svg.contains("""<circle id="front"""), "front wheel missing: ${outcome.svg}")
        val rearCx = doubleAttr(outcome.svg, "rear", "cx")
        val frontCx = doubleAttr(outcome.svg, "front", "cx")
        assertTrue(abs(rearCx - frontCx) > 1.0,
            "wheels collapsed to the same cx (rear=$rearCx, front=$frontCx) — Safe-Harbor did not spread: ${outcome.svg}")
    }

    /** Helper: runs the Phase-2 solve (SkeletonBuilder + DraftsEngine pinSizes=true) for precondition checks. */
    private fun solvePhase2(skeleton: EmitSkeleton): SceneGraph {
        val graph = SkeletonBuilder.build(skeleton, canvas)
        val (solved, _) = DraftsEngine().solveInto(graph, pinSizes = true)
        return solved
    }

    private fun doubleAttr(svg: String, elementId: String, attr: String): Double {
        val elem = Regex("""<\w+\b[^>]*\bid="${Regex.escape(elementId)}"[^>]*/?>""")
            .find(svg) ?: fail("no element with id='$elementId' in svg")
        val pattern = Regex("\\b" + attr + "=\"(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\"")
        val m = pattern.find(elem.value) ?: fail("no '$attr' on element id='$elementId': ${elem.value}")
        return m.groupValues[1].toDouble()
    }

    private fun assertApprox(expected: Double, actual: Double, tol: Double, label: String) {
        assertTrue(abs(expected - actual) <= tol, "$label: expected $expected got $actual (Δ=${expected - actual}, tol=$tol)")
    }

    private fun assertPointApprox(expected: Pair<Double, Double>, actual: Pair<Double, Double>, tol: Double, label: String) {
        val dx = abs(expected.first - actual.first)
        val dy = abs(expected.second - actual.second)
        assertTrue(dx <= tol && dy <= tol,
            "$label: expected (${expected.first}, ${expected.second}) got (${actual.first}, ${actual.second}) (Δx=$dx, Δy=$dy, tol=$tol)")
    }
}
