package by.ciszkin.draftsllman.architect

import by.ciszkin.draftsllman.engine.DraftsEngine
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.GroupNode
import by.ciszkin.draftsllman.scene.LineNode
import by.ciszkin.draftsllman.solver.SolveResult
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The emit_skeleton FEASIBILITY GATE (spec §4.4 revise loop): the tool solves the skeleton
 * immediately and returns a diagnostic instead of stashing when the solve is Unsatisfiable or
 * degenerate, so the Architect LLM can revise and re-emit. After MAX_EMIT_ATTEMPTS (3) failing
 * attempts the LAST skeleton is stashed regardless and the Coordinator's Safe-Harbor applies.
 */
class EmitSkeletonToolTest {
    private val canvas = CanvasSpec(500.0, 500.0)

    /** The exact 8-component bicycle the 2026-08-14 live run emitted (its emit 2) — shared by the
     *  strut money test and the contain money test. */
    private val liveBicycle = EmitSkeleton(
        components = listOf(
            SkeletonComponent("rear_wheel", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
            SkeletonComponent("front_wheel", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
            SkeletonComponent("chain_stay", "frame", "line", 90.0, 10.0,
                mapOf("rearEnd" to "LEFT_CENTER", "frontEnd" to "RIGHT_CENTER")),
            SkeletonComponent("seat_tube", "frame", "line", 10.0, 120.0,
                mapOf("bottomJunc" to "BOTTOM_CENTER", "topJunc" to "TOP_CENTER")),
            SkeletonComponent("top_tube", "frame", "line", 130.0, 10.0,
                mapOf("rearJunc" to "LEFT_CENTER", "frontJunc" to "RIGHT_CENTER")),
            SkeletonComponent("fork", "frame", "line", 10.0, 120.0,
                mapOf("topJunc" to "TOP_CENTER", "bottomJunc" to "BOTTOM_CENTER")),
            SkeletonComponent("handlebar", "controls", "line", 60.0, 10.0,
                mapOf("centerPt" to "CENTER", "leftEnd" to "LEFT_CENTER", "rightEnd" to "RIGHT_CENTER")),
            SkeletonComponent("seat", "saddle", "rect", 40.0, 10.0,
                mapOf("bottomPt" to "BOTTOM_CENTER", "topPt" to "TOP_CENTER")),
        ),
        constraints = listOf(
            SkeletonConstraint("c1", "connect", "chain_stay:rearEnd", "rear_wheel:hub"),
            SkeletonConstraint("c2", "connect", "chain_stay:frontEnd", "seat_tube:bottomJunc"),
            SkeletonConstraint("c3", "connect", "seat_tube:topJunc", "top_tube:rearJunc"),
            SkeletonConstraint("c4", "connect", "top_tube:frontJunc", "fork:topJunc"),
            SkeletonConstraint("c5", "connect", "fork:bottomJunc", "front_wheel:hub"),
            SkeletonConstraint("c6", "connect", "handlebar:centerPt", "fork:topJunc"),
            SkeletonConstraint("c7", "connect", "seat:bottomPt", "seat_tube:topJunc"),
            SkeletonConstraint("c8", "align", "rear_wheel:CENTER_Y", "front_wheel:CENTER_Y"),
            SkeletonConstraint("c9", "distance_ratio", "rear_wheel:CENTER_X", "front_wheel:CENTER_X",
                ratio = 0.6, relativeTo = "canvas_width"),
        ),
    )

    /** Connects + wheelbase distance_ratio: solves, wheels separated — the "ok" case. */
    private val separated = EmitSkeleton(
        components = listOf(
            SkeletonComponent("rear", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
            SkeletonComponent("front", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
            SkeletonComponent("frame", "chassis", "line", 300.0, 100.0,
                mapOf("rearAxle" to "BOTTOM_LEFT", "frontAxle" to "BOTTOM_RIGHT")),
        ),
        constraints = listOf(
            SkeletonConstraint("lr", "connect", "frame:rearAxle", "rear:hub"),
            SkeletonConstraint("lf", "connect", "frame:frontAxle", "front:hub"),
            SkeletonConstraint("wb", "distance_ratio", "rear:CENTER_X", "front:CENTER_X",
                ratio = 0.6, relativeTo = "canvas_width"),
        ),
    )

    /** align (equal) + distance_ratio (apart) on the same anchors: REQUIRED vs REQUIRED — unsatisfiable. */
    private val overConstrained = EmitSkeleton(
        components = listOf(
            SkeletonComponent("rear", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
            SkeletonComponent("front", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
        ),
        constraints = listOf(
            SkeletonConstraint("a1", "align", "rear:CENTER_X", "front:CENTER_X"),
            SkeletonConstraint("d1", "distance_ratio", "rear:CENTER_X", "front:CENTER_X",
                ratio = 0.6, relativeTo = "canvas_width"),
        ),
    )

    /** A lone hub-to-hub connect: SOLVES but collapses both centers — the degenerate case. */
    private val collapsed = EmitSkeleton(
        components = listOf(
            SkeletonComponent("rear", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
            SkeletonComponent("front", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
        ),
        constraints = listOf(SkeletonConstraint("c", "connect", "rear:hub", "front:hub")),
    )

    private fun tool(sink: SkeletonSink) = emitSkeletonTool(canvas, sink)

    @Test fun `a satisfiable non-degenerate skeleton is stashed with ok`() = runBlocking {
        val sink = SkeletonSink()
        val result = tool(sink).execute(separated)
        assertTrue(result.startsWith("ok:"), "expected ok, got: $result")
        assertTrue(result.contains("(solved)"), "ok must say solved: $result")
        assertEquals(separated, sink.poll(), "a clean solve must be stashed")
    }

    @Test fun `an over-constrained skeleton returns unsatisfiable and is NOT stashed`() = runBlocking {
        val sink = SkeletonSink()
        val result = tool(sink).execute(overConstrained)
        assertTrue(result.startsWith("unsatisfiable:"), "expected unsatisfiable, got: $result")
        assertTrue(result.contains("'d1'"), "must name the conflicting constraint id: $result")
        assertNull(sink.poll(), "a failed solve must not be stashed")
    }

    @Test fun `a collapsing skeleton returns degenerate naming the components and is NOT stashed`() = runBlocking {
        val sink = SkeletonSink()
        val result = tool(sink).execute(collapsed)
        assertTrue(result.startsWith("degenerate:"), "expected degenerate, got: $result")
        assertTrue(result.contains("rear") && result.contains("front"), "must name both components: $result")
        assertNull(sink.poll(), "a degenerate solve must not be stashed")
    }

    @Test fun `a structural validation error is unchanged and does not stash`() = runBlocking {
        val sink = SkeletonSink()
        val bad = separated.copy(components = separated.components.map { it.copy(width = 0.0) })
        val result = tool(sink).execute(bad)
        assertTrue(result.startsWith("error:"), "expected structural error, got: $result")
        assertNull(sink.poll())
    }

    @Test fun `after MAX attempts the last skeleton is stashed with the terminal note`() = runBlocking {
        val sink = SkeletonSink()
        val t = tool(sink)
        assertTrue(t.execute(collapsed).startsWith("degenerate:"))   // attempt 1
        assertTrue(t.execute(collapsed).startsWith("degenerate:"))   // attempt 2 (retry 1)
        val third = t.execute(collapsed)                             // attempt 3 (retry 2)
        assertTrue(third.startsWith("max-retries reached:"), "third attempt must be terminal, got: $third")
        assertEquals(collapsed, sink.poll(), "the last skeleton must be stashed on cap")
    }

    @Test fun `a failed attempt followed by a corrected emit recovers to ok`() = runBlocking {
        val sink = SkeletonSink()
        val t = tool(sink)
        assertTrue(t.execute(collapsed).startsWith("degenerate:"))
        val result = t.execute(separated)
        assertTrue(result.startsWith("ok:"), "the revised skeleton must be accepted, got: $result")
        assertEquals(separated, sink.poll())
    }

    /** The engine can THROW on a validator-accepted skeleton (a constraint on a `group` component —
     *  the solver holds no variables for groups). The gate must convert that to a diagnostic (not a
     *  thrown exception Koog would swallow) and still honor the cap-stash guarantee. */
    @Test fun `an engine exception becomes a diagnostic and still honors the cap`() = runBlocking {
        val sink = SkeletonSink()
        val t = tool(sink)
        val groupConstrained = EmitSkeleton(
            components = listOf(
                SkeletonComponent("cluster", "assembly", "group", 100.0, 100.0),
                SkeletonComponent("wheel", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
            ),
            constraints = listOf(SkeletonConstraint("bad", "connect", "cluster:CENTER", "wheel:hub")),
        )
        assertTrue(t.execute(groupConstrained).startsWith("error:"), "engine failure must be a diagnostic, not a throw")
        assertNull(sink.poll(), "an engine-failed attempt must not be stashed")
        assertTrue(t.execute(groupConstrained).startsWith("error:"))
        val third = t.execute(groupConstrained)
        assertTrue(third.startsWith("max-retries reached:"), "cap-stash must hold on engine failures, got: $third")
        assertEquals(groupConstrained, sink.poll())
    }

    /** THE MONEY TEST: the exact 8-component bicycle the 2026-08-14 live run emitted (its emit 2) —
     *  a connect-chain of frame tubes plus a 0.6-wheelbase distance_ratio. Rigid sizes made the chain
     *  sum (220) contradict the ratio (300) → unsatisfiable on every retry. With struts it must
     *  solve on the primary path, non-degenerate. */
    @Test fun `the live-run bicycle chain-plus-wheelbase skeleton solves via struts`() = runBlocking {
        val sink = SkeletonSink()
        val result = tool(sink).execute(liveBicycle)
        assertTrue(result.startsWith("ok:"), "the live-run skeleton that exhausted the revise loop must now solve: $result")
        assertEquals(liveBicycle, sink.poll())
    }

    /** THE CONTAIN MONEY TEST (spec 2026-08-17, acceptance #3): the live-run bicycle plus
     *  contain constraints solves on the primary path with EVERY component center on-canvas —
     *  the 2026-08-14 live run put the seat at y=-2, handlebars y=-30, pedals x=-100. Contains
     *  cover the structural wheels (they anchor the assembly — satellite-only contains leave the
     *  whole chain free to sit off-canvas) plus the two satellites the live run misplaced. */
    @Test fun `the live-run bicycle with contain constraints solves fully on-canvas`() = runBlocking {
        val contained = liveBicycle.copy(constraints = liveBicycle.constraints + listOf(
            SkeletonConstraint("kr", "contain", "rear_wheel", "canvas", margin = 0.02),
            SkeletonConstraint("kf", "contain", "front_wheel", "canvas", margin = 0.02),
            SkeletonConstraint("ks", "contain", "seat", "canvas", margin = 0.02),
            SkeletonConstraint("kh", "contain", "handlebar", "canvas", margin = 0.02),
        ))
        val sink = SkeletonSink()
        val result = tool(sink).execute(contained)
        assertTrue(result.startsWith("ok:"), "the contained live-run bicycle must solve: $result")
        assertEquals(contained, sink.poll())

        // The solve is a pure function — recomputing it here yields the tool's boxes exactly.
        val graph = SkeletonBuilder.build(contained, canvas)
        val (solved, solveResult) = DraftsEngine().solveInto(graph, pinSizes = true)
        assertTrue(solveResult is SolveResult.Success, "re-solve must agree with the tool: $solveResult")
        solved.nodes.values.filter { it !is GroupNode }.forEach { n ->
            // Full-box containment (stronger than centers: catches half-off-canvas boxes on the
            // non-contained frame tubes, which only geometry — not a contain constraint — protects).
            assertTrue(n.box.left >= 0.0 && n.box.right <= canvas.width,
                "${n.id} box x [${n.box.left}, ${n.box.right}] is off-canvas")
            assertTrue(n.box.top >= 0.0 && n.box.bottom <= canvas.height,
                "${n.id} box y [${n.box.top}, ${n.box.bottom}] is off-canvas")
        }

        // The four CONTAINED components must honor the margin band itself — not just the raw
        // canvas edges (a regression clamping to [0, canvas] instead of [margin*canvas, ...]
        // would pass the loop above but fail here). The probe-optimum binds exactly at the bound.
        val lo = 0.02 * canvas.width
        val hi = canvas.width - lo
        listOf("rear_wheel", "front_wheel", "seat", "handlebar").forEach { id ->
            val b = solved.nodes[id]!!.box
            assertTrue(b.left >= lo - 1e-6 && b.right <= hi + 1e-6,
                "$id box x [${b.left}, ${b.right}] leaves the margin band [$lo, $hi]")
            assertTrue(b.top >= lo - 1e-6 && b.bottom <= hi + 1e-6,
                "$id box y [${b.top}, ${b.bottom}] leaves the margin band [$lo, $hi]")
        }

        // Engine consistency: every line tube renders exactly the span the solver solved —
        // withBox box-maps endpoints (the old pure translate drew stretched struts short).
        // All five tubes are non-degenerate on both axes (the validator requires positive w/h),
        // so the span equalities are exact up to float round-trip; a degenerate-axis tube would
        // keep its degeneracy and legitimately differ on that axis.
        val solvedBoxes = (solveResult as SolveResult.Success).boxes
        solved.nodes.values.filterIsInstance<LineNode>().forEach { n ->
            val spanX = abs(n.end.first - n.start.first)
            val spanY = abs(n.end.second - n.start.second)
            assertTrue(
                abs(spanX - solvedBoxes[n.id]!!.width) <= 1e-6,
                "${n.id} renders x-span $spanX but the solver solved width ${solvedBoxes[n.id]!!.width}"
            )
            assertTrue(
                abs(spanY - solvedBoxes[n.id]!!.height) <= 1e-6,
                "${n.id} renders y-span $spanY but the solver solved height ${solvedBoxes[n.id]!!.height}"
            )
        }
    }

    /** relative_size money test (spec 2026-08-20): the penny-farthing — rear 60 declared (the
     *  reference, REQUIRED stay), front declared 80, ratio 1.6 says 96. The directional demotion
     *  must grow the SOURCE onto the reference and solve on the primary path. */
    @Test fun `the penny-farthing skeleton solves with the front wheel at 1 6x the rear`() = runBlocking {
        val skeleton = EmitSkeleton(
            components = listOf(
                SkeletonComponent("rear_wheel", "wheel", "circle", 60.0, 60.0, mapOf("hub" to "CENTER")),
                SkeletonComponent("front_wheel", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
                SkeletonComponent("frame", "chassis", "line", 240.0, 60.0,
                    mapOf("rearAxle" to "BOTTOM_LEFT", "frontAxle" to "BOTTOM_RIGHT")),
            ),
            constraints = listOf(
                SkeletonConstraint("c1", "connect", "frame:rearAxle", "rear_wheel:hub"),
                SkeletonConstraint("c2", "connect", "frame:frontAxle", "front_wheel:hub"),
                SkeletonConstraint("rs", "relative_size", "front_wheel", "rear_wheel", ratio = 1.6),
                SkeletonConstraint("dr", "distance_ratio", "rear_wheel:CENTER_X", "front_wheel:CENTER_X",
                    ratio = 0.5, relativeTo = "canvas_width"),
                SkeletonConstraint("kr", "contain", "rear_wheel", "canvas", margin = 0.05),
                SkeletonConstraint("kf", "contain", "front_wheel", "canvas", margin = 0.05),
            ),
        )
        val sink = SkeletonSink()
        val result = tool(sink).execute(skeleton)
        assertTrue(result.startsWith("ok:"), "penny-farthing must solve: $result")
        assertEquals(skeleton, sink.poll())

        val graph = SkeletonBuilder.build(skeleton, canvas)
        val (solved, solveResult) = DraftsEngine().solveInto(graph, pinSizes = true)
        assertTrue(solveResult is SolveResult.Success, "re-solve must agree: $solveResult")
        val rear = solved.nodes["rear_wheel"]!!.box
        val front = solved.nodes["front_wheel"]!!.box
        assertTrue(abs(front.width - 1.6 * rear.width) <= 1e-3,
            "front width ${front.width} should be 1.6x rear ${rear.width}")
        assertTrue(abs(front.height - 1.6 * rear.height) <= 1e-3, "uniform: both axes at the ratio")
    }

    /** distribute money test (spec 2026-08-20): three pickets, evenly spaced along the width,
     *  aligned and contain-bounded. */
    @Test fun `the picket fence skeleton solves with equal gaps`() = runBlocking {
        val skeleton = EmitSkeleton(
            components = listOf(
                SkeletonComponent("picket_1", "picket", "rect", 20.0, 60.0),
                SkeletonComponent("picket_2", "picket", "rect", 20.0, 60.0),
                SkeletonComponent("picket_3", "picket", "rect", 20.0, 60.0),
            ),
            constraints = listOf(
                SkeletonConstraint("a1", "align", "picket_1:CENTER_Y", "picket_2:CENTER_Y"),
                SkeletonConstraint("a2", "align", "picket_2:CENTER_Y", "picket_3:CENTER_Y"),
                SkeletonConstraint("d", "distribute", "", "",
                    components = listOf("picket_1", "picket_2", "picket_3"), along = "width"),
                // distribute is spacing-only (the prompt: "pair it with align/distance_ratio to pin
                // the set") — without a span, the L1 optimum collapses the stacked-at-origin
                // pickets (equal negative gaps) and the degeneracy guard rightly rejects it.
                SkeletonConstraint("dr", "distance_ratio", "picket_1:CENTER_X", "picket_3:CENTER_X",
                    ratio = 0.3, relativeTo = "canvas_width"),
                SkeletonConstraint("k1", "contain", "picket_1", "canvas", margin = 0.05),
                SkeletonConstraint("k3", "contain", "picket_3", "canvas", margin = 0.05),
            ),
        )
        val sink = SkeletonSink()
        val result = tool(sink).execute(skeleton)
        assertTrue(result.startsWith("ok:"), "picket fence must solve: $result")

        val graph = SkeletonBuilder.build(skeleton, canvas)
        val (solved, solveResult) = DraftsEngine().solveInto(graph, pinSizes = true)
        assertTrue(solveResult is SolveResult.Success, "re-solve must agree: $solveResult")
        val p1 = solved.nodes["picket_1"]!!.box
        val p2 = solved.nodes["picket_2"]!!.box
        val p3 = solved.nodes["picket_3"]!!.box
        val gap1 = p2.left - p1.right
        val gap2 = p3.left - p2.right
        assertTrue(abs(gap1 - gap2) <= 1e-3, "gaps must be equal: $gap1 vs $gap2")
        assertTrue(gap1 > 0.0, "pickets must not overlap")
        assertTrue(p1.cx in 0.0..canvas.width && p3.cx in 0.0..canvas.width, "pickets on-canvas")
    }
}
