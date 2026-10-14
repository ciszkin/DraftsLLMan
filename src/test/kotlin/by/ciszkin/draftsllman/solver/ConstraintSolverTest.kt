package by.ciszkin.draftsllman.solver

import by.ciszkin.draftsllman.engine.DraftsEngine
import by.ciszkin.draftsllman.scene.Anchor
import by.ciszkin.draftsllman.scene.AnchorAxis
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.LineNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.Style
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConstraintSolverTest {
    private fun leaf(id: String, x: Double, y: Double, w: Double, h: Double) =
        CircleNode(id, "n", ResolvedBox(x, y, w, h), Style())

    private fun approx(expected: Double, actual: Double, t: Double = 1e-6) =
        assertTrue(abs(expected - actual) <= t, "expected $expected got $actual")

    private fun strutLine(id: String, x: Double, y: Double, w: Double) =
        LineNode(id, "n", start = x to y, end = (x + w) to y)

    @Test fun `no constraints returns preferred boxes unchanged`() {
        val graph = SceneGraph.EMPTY.withNode(leaf("a", 10.0, 20.0, 4.0, 4.0)).withRoot("a")
        val result = ConstraintSolver.solve(graph)
        assertTrue(result is SolveResult.Success)
        approx(10.0, result.boxes["a"]!!.x)
    }

    @Test fun `Align makes source center coincide with target center on X`() {
        val graph = SceneGraph(
            nodes = mapOf(
                "a" to leaf("a", 0.0, 0.0, 4.0, 4.0),
                "b" to leaf("b", 100.0, 0.0, 20.0, 20.0),
            ),
            rootIds = listOf("a", "b"),
            constraints = ConstraintSet.EMPTY.add(
                SceneConstraint.Align("c1", "a", Anchor.CENTER_X, "b", Anchor.CENTER_X)
            ),
        )
        val boxes = (ConstraintSolver.solve(graph) as SolveResult.Success).boxes
        approx(boxes["b"]!!.cx, boxes["a"]!!.cx)
    }

    @Test fun `Distance enforces an axis gap invariant`() {
        val graph = SceneGraph(
            nodes = mapOf(
                "a" to leaf("a", 0.0, 0.0, 10.0, 10.0),
                "b" to leaf("b", 100.0, 0.0, 10.0, 10.0),
            ),
            rootIds = listOf("a", "b"),
            constraints = ConstraintSet.EMPTY.add(
                SceneConstraint.Distance("c1", "a", Anchor.RIGHT, "b", Anchor.LEFT, pixels = 30.0)
            ),
        )
        val boxes = (ConstraintSolver.solve(graph) as SolveResult.Success).boxes
        approx(30.0, boxes["b"]!!.left - boxes["a"]!!.right)
    }

    @Test fun `an over-constrained set reports the conflicting constraint id`() {
        // align says the two centers are EQUAL on X; the distance says they are 30px apart —
        // REQUIRED vs REQUIRED, unsatisfiable. The second constraint added (the Distance) is the
        // one the solver fails on, so its id must be carried on the result for the revise loop.
        val graph = SceneGraph(
            nodes = mapOf(
                "a" to leaf("a", 0.0, 0.0, 10.0, 10.0),
                "b" to leaf("b", 100.0, 0.0, 10.0, 10.0),
            ),
            rootIds = listOf("a", "b"),
            constraints = ConstraintSet.EMPTY.add(
                SceneConstraint.Align("c1", "a", Anchor.CENTER_X, "b", Anchor.CENTER_X)
            ).add(
                SceneConstraint.Distance("c2", "a", Anchor.CENTER_X, "b", Anchor.CENTER_X, pixels = 30.0)
            ),
        )
        val result = ConstraintSolver.solve(graph, pinSizes = true)
        assertTrue(result is SolveResult.Unsatisfiable, "expected unsatisfiable, got: $result")
        assertEquals("c2", result.constraintId, "must name the conflicting constraint")
    }

    @Test fun `the catch-all construction leaves constraintId null by default`() {
        // The catch-all branch (outer catch in solve) has no offending constraint to name; the
        // default parameter is the contract that keeps that path id-free. (Do NOT test this via an
        // unknown-node constraint: `vars()` throws IllegalStateException there, which solve does
        // NOT catch — validateSkeleton rejects unknown refs before the solver ever sees them.)
        assertNull(SolveResult.Unsatisfiable("boom").constraintId)
    }

    @Test fun `Connect coincides point anchors on both axes`() {
        val graph = SceneGraph(
            nodes = mapOf(
                "a" to leaf("a", 0.0, 0.0, 10.0, 10.0),
                "b" to leaf("b", 100.0, 100.0, 20.0, 20.0),
            ),
            rootIds = listOf("a", "b"),
            constraints = ConstraintSet.EMPTY.add(
                SceneConstraint.Connect("c1", "a", Anchor.CENTER, "b", Anchor.CENTER)
            ),
        )
        val boxes = (ConstraintSolver.solve(graph) as SolveResult.Success).boxes
        approx(boxes["b"]!!.cx, boxes["a"]!!.cx)
        approx(boxes["b"]!!.cy, boxes["a"]!!.cy)
    }

    @Test fun `contradictory REQUIRED constraints are unsatisfiable`() {
        val graph = SceneGraph(
            nodes = mapOf(
                "a" to leaf("a", 0.0, 0.0, 10.0, 10.0),
                "b" to leaf("b", 0.0, 0.0, 10.0, 10.0),
            ),
            rootIds = listOf("a", "b"),
            constraints = ConstraintSet.EMPTY
                .add(SceneConstraint.Align("c1", "a", Anchor.LEFT, "b", Anchor.LEFT))
                .add(SceneConstraint.Distance("c2", "a", Anchor.LEFT, "b", Anchor.LEFT, pixels = 999.0)),
        )
        assertTrue(ConstraintSolver.solve(graph) is SolveResult.Unsatisfiable)
    }

    @Test fun `Connect coincides mid-edge point anchors`() {
        val graph = SceneGraph(
            nodes = mapOf(
                "a" to leaf("a", 0.0, 0.0, 10.0, 10.0),
                "b" to leaf("b", 100.0, 100.0, 20.0, 20.0),
            ),
            rootIds = listOf("a", "b"),
            constraints = ConstraintSet.EMPTY.add(
                SceneConstraint.Connect("c1", "a", Anchor.TOP_CENTER, "b", Anchor.BOTTOM_CENTER)
            ),
        )
        val boxes = (ConstraintSolver.solve(graph) as SolveResult.Success).boxes
        // TOP_CENTER of a == (cx, top); BOTTOM_CENTER of b == (cx, bottom)
        approx(boxes["a"]!!.cx, boxes["b"]!!.cx)
        approx(boxes["a"]!!.top, boxes["b"]!!.bottom)
    }

    @Test fun `pinSizes keeps a component's width fixed against a width-dependent connect`() {
        val graph = SceneGraph(
            nodes = mapOf(
                "a" to leaf("a", 0.0, 0.0, 10.0, 10.0),
                "b" to leaf("b", 0.0, 0.0, 10.0, 10.0),
            ),
            rootIds = listOf("a", "b"),
            constraints = ConstraintSet.EMPTY.add(
                SceneConstraint.Connect("c1", "a", Anchor.BOTTOM_RIGHT, "b", Anchor.BOTTOM_LEFT)
            ),
        )
        val pinned = (ConstraintSolver.solve(graph, pinSizes = true) as SolveResult.Success).boxes
        approx(10.0, pinned["a"]!!.width)               // width NOT collapsed (the default MEDIUM stay would shrink it)
        approx(pinned["a"]!!.right, pinned["b"]!!.left) // connect still satisfied
    }

    /** Strut rule: a line with BOTH ends connected stretches so a REQUIRED distance always solves. */
    @Test fun `a strut with two connected ends stretches to a REQUIRED distance`() {
        val graph = SceneGraph(
            nodes = mapOf(
                "a" to leaf("a", 0.0, 0.0, 10.0, 10.0),
                "b" to leaf("b", 200.0, 0.0, 10.0, 10.0),
                "strut" to strutLine("strut", 0.0, 0.0, 100.0),
            ),
            rootIds = listOf("a", "b", "strut"),
            constraints = ConstraintSet(listOf(
                SceneConstraint.Connect("k1", "strut", Anchor.LEFT_CENTER, "a", Anchor.CENTER),
                SceneConstraint.Connect("k2", "strut", Anchor.RIGHT_CENTER, "b", Anchor.CENTER),
                SceneConstraint.Distance("d", "a", Anchor.CENTER_X, "b", Anchor.CENTER_X, pixels = 300.0),
            )),
        )
        val result = ConstraintSolver.solve(graph, pinSizes = true)
        assertTrue(result is SolveResult.Success, "strut chain + distance must solve: $result")
        val boxes = result.boxes
        approx(300.0, boxes["b"]!!.cx - boxes["a"]!!.cx)
        approx(300.0, boxes["strut"]!!.width, 1e-6) // stretched end to end
    }

    /** Nothing fighting the strut → MEDIUM stays keep the declared size (today's behavior preserved).
     *  NB: b's initial box is placed so ALL stays are mutually consistent (strut 100 wide from a.cx=5
     *  reaches b.cx=105 exactly) — otherwise the MEDIUM-vs-MEDIUM optimum is a tie and the solver may
     *  pick any width in a range, making the assertion flaky. */
    @Test fun `an undisturbed strut keeps its declared size`() {
        val graph = SceneGraph(
            nodes = mapOf(
                "a" to leaf("a", 0.0, 0.0, 10.0, 10.0),
                "b" to leaf("b", 100.0, 0.0, 10.0, 10.0),
                "strut" to strutLine("strut", 0.0, 0.0, 100.0),
            ),
            rootIds = listOf("a", "b", "strut"),
            constraints = ConstraintSet(listOf(
                SceneConstraint.Connect("k1", "strut", Anchor.LEFT_CENTER, "a", Anchor.CENTER),
                SceneConstraint.Connect("k2", "strut", Anchor.RIGHT_CENTER, "b", Anchor.CENTER),
            )),
        )
        val boxes = (ConstraintSolver.solve(graph, pinSizes = true) as SolveResult.Success).boxes
        approx(100.0, boxes["b"]!!.cx - boxes["a"]!!.cx)
        approx(100.0, boxes["strut"]!!.width)
    }

    /** One connected end = cantilever, NOT a strut → size stays REQUIRED, cannot stretch. */
    @Test fun `a line with only one connected end does not stretch`() {
        val graph = SceneGraph(
            nodes = mapOf(
                "a" to leaf("a", 0.0, 0.0, 10.0, 10.0),
                "strut" to strutLine("strut", 0.0, 0.0, 100.0),
            ),
            rootIds = listOf("a", "strut"),
            constraints = ConstraintSet(listOf(
                SceneConstraint.Connect("k1", "strut", Anchor.LEFT_CENTER, "a", Anchor.CENTER),
                SceneConstraint.Distance("d", "a", Anchor.CENTER_X, "strut", Anchor.RIGHT, pixels = 300.0),
            )),
        )
        val result = ConstraintSolver.solve(graph, pinSizes = true)
        assertTrue(result is SolveResult.Unsatisfiable, "cantilever must stay rigid: $result")
        assertEquals("d", result.constraintId)
    }

    /** Two connects on the SAME anchor (the collapse pattern) do NOT make a strut. */
    @Test fun `two connects on the same anchor do not make a strut`() {
        val graph = SceneGraph(
            nodes = mapOf(
                "a" to leaf("a", 0.0, 0.0, 10.0, 10.0),
                "b" to leaf("b", 200.0, 0.0, 10.0, 10.0),
                "hub" to strutLine("hub", 0.0, 0.0, 100.0),
            ),
            rootIds = listOf("a", "b", "hub"),
            constraints = ConstraintSet(listOf(
                SceneConstraint.Connect("k1", "hub", Anchor.CENTER, "a", Anchor.CENTER),
                SceneConstraint.Connect("k2", "hub", Anchor.CENTER, "b", Anchor.CENTER),
                SceneConstraint.Distance("d", "a", Anchor.CENTER_X, "b", Anchor.CENTER_X, pixels = 300.0),
            )),
        )
        val result = ConstraintSolver.solve(graph, pinSizes = true)
        assertTrue(result is SolveResult.Unsatisfiable, "same-anchor connects must not relax sizes: $result")
    }

    /** Post-strut semantics: a CHAIN of struts plus a distance_ratio constraint always solves.
     *  Before struts, the rigid REQUIRED size stays made this over-constrained (Unsatisfiable).
     *  Now struts stretch to satisfy both the connect chain AND the distance ratio. */
    @Test fun `a strut chain with distance_ratio solves by stretching`() {
        // Wheel A ← strut1 ← strut2 ← Wheel B, with a distance_ratio on the wheels.
        // The struts can stretch to make the wheelbase match the ratio.
        val graph = SceneGraph(
            nodes = mapOf(
                "wheelA" to leaf("wheelA", 0.0, 0.0, 80.0, 80.0),
                "wheelB" to leaf("wheelB", 200.0, 0.0, 80.0, 80.0),
                "strut1" to strutLine("strut1", 80.0, 40.0, 90.0),
                "strut2" to strutLine("strut2", 170.0, 40.0, 90.0),
            ),
            rootIds = listOf("wheelA", "wheelB", "strut1", "strut2"),
            constraints = ConstraintSet(listOf(
                SceneConstraint.Connect("c1", "strut1", Anchor.LEFT_CENTER, "wheelA", Anchor.CENTER),
                SceneConstraint.Connect("c2", "strut1", Anchor.RIGHT_CENTER, "strut2", Anchor.LEFT_CENTER),
                SceneConstraint.Connect("c3", "strut2", Anchor.RIGHT_CENTER, "wheelB", Anchor.CENTER),
                SceneConstraint.Distance("d", "wheelA", Anchor.CENTER_X, "wheelB", Anchor.CENTER_X, pixels = 300.0),
            )),
        )
        val result = ConstraintSolver.solve(graph, pinSizes = true)
        assertTrue(result is SolveResult.Success, "strut chain + distance must solve: $result")
        val boxes = result.boxes
        // The distance constraint is satisfied
        approx(300.0, boxes["wheelB"]!!.cx - boxes["wheelA"]!!.cx, 1e-3)
        // Both struts stretch to bridge the gap (sum ≈ 300 - 2*40 = 220, accounting for wheel radii)
        assertTrue(boxes["strut1"]!!.width > 0.0, "strut1 must have non-zero width")
        assertTrue(boxes["strut2"]!!.width > 0.0, "strut2 must have non-zero width")
    }

    /** Post-strut semantics: a connect CYCLE of lines is no longer Unsatisfiable — every cycle member
     *  is a strut (≥2 connected anchors), so even a geometrically infeasible loop SOLVES by collapsing:
     *  chaining left→right around a head-tail cycle forces total width == 0 exactly. The layout is
     *  degenerate (the emit gate reports it as `degenerate`, revision/Safe-Harbor then handle it) —
     *  a different, more honest failure mode than a raw solver contradiction. This is why the two
     *  Safe-Harbor cycle fixtures use rigid RECTS: only rigid-body cycles can still be UNSATISFIABLE,
     *  so only they can exercise the spanning-tree tier. The sum (not individual widths) is asserted:
     *  Cassowary may distribute the zero among members unpredictably. */
    @Test fun `a geometrically infeasible connect cycle of strut lines solves with zero total span`() {
        val graph = SceneGraph(
            nodes = mapOf(
                "frame" to strutLine("frame", 0.0, 0.0, 200.0),
                "fork" to strutLine("fork", 0.0, 60.0, 160.0),
                "stem" to strutLine("stem", 0.0, 120.0, 120.0),
            ),
            rootIds = listOf("frame", "fork", "stem"),
            constraints = ConstraintSet(listOf(
                SceneConstraint.Connect("e1", "frame", Anchor.LEFT_CENTER, "fork", Anchor.RIGHT_CENTER),
                SceneConstraint.Connect("e2", "fork", Anchor.LEFT_CENTER, "stem", Anchor.RIGHT_CENTER),
                SceneConstraint.Connect("e3", "stem", Anchor.LEFT_CENTER, "frame", Anchor.RIGHT_CENTER),
            )),
        )
        val result = ConstraintSolver.solve(graph, pinSizes = true)
        assertTrue(result is SolveResult.Success, "strut cycle must solve (degenerately), not be unsatisfiable: $result")
        val boxes = result.boxes
        approx(0.0, boxes["frame"]!!.width + boxes["fork"]!!.width + boxes["stem"]!!.width, 1e-3)
    }

    // ---- contain (canvas-bounds, spec 2026-08-17) ----

    /** A node whose stays push it outside is pulled back to the margin bounds (REQUIRED vs MEDIUM). */
    @Test fun `contain pulls an off-canvas node back to the margin bounds`() {
        val graph = SceneGraph(
            nodes = mapOf("a" to leaf("a", -50.0, -30.0, 20.0, 10.0)),
            rootIds = listOf("a"),
            canvas = CanvasSpec(200.0, 100.0),
            constraints = ConstraintSet.EMPTY.add(SceneConstraint.Contain("k1", "a", margin = 0.1)),
        )
        val boxes = (ConstraintSolver.solve(graph) as SolveResult.Success).boxes
        approx(20.0, boxes["a"]!!.x)   // left >= 0.1 * 200, stay pulls to the bound exactly
        approx(10.0, boxes["a"]!!.y)   // top  >= 0.1 * 100
        assertTrue(boxes["a"]!!.right <= 180.0 + 1e-6)   // (1-0.1)*200
        assertTrue(boxes["a"]!!.bottom <= 90.0 + 1e-6)   // (1-0.1)*100
    }

    /** Same on the far edges: stays past the right/bottom bounds are pulled back to them. */
    @Test fun `contain pulls a node past the right and bottom bounds back to them`() {
        val graph = SceneGraph(
            nodes = mapOf("a" to leaf("a", 500.0, 500.0, 20.0, 10.0)),
            rootIds = listOf("a"),
            canvas = CanvasSpec(200.0, 100.0),
            constraints = ConstraintSet.EMPTY.add(SceneConstraint.Contain("k1", "a", margin = 0.1)),
        )
        val boxes = (ConstraintSolver.solve(graph) as SolveResult.Success).boxes
        approx(160.0, boxes["a"]!!.x)  // right (x+20) <= 180
        approx(80.0, boxes["a"]!!.y)   // bottom (y+10) <= 90
    }

    /** Acceptance #2: contain + distance + align on the same nodes solve together (bounds are
     *  inequalities — they never fight the REQUIRED equalities while feasible). */
    @Test fun `contain composes with distance and align without conflict`() {
        val graph = SceneGraph(
            nodes = mapOf(
                "a" to leaf("a", 0.0, 0.0, 10.0, 10.0),
                "b" to leaf("b", 0.0, 0.0, 10.0, 10.0),
            ),
            rootIds = listOf("a", "b"),
            canvas = CanvasSpec(200.0, 100.0),
            constraints = ConstraintSet(listOf(
                SceneConstraint.Contain("k1", "a", margin = 0.1),
                SceneConstraint.Contain("k2", "b", margin = 0.1),
                SceneConstraint.Distance("d", "a", Anchor.RIGHT, "b", Anchor.LEFT, pixels = 50.0),
                SceneConstraint.Align("al", "a", Anchor.CENTER_Y, "b", Anchor.CENTER_Y),
            )),
        )
        val result = ConstraintSolver.solve(graph, pinSizes = true)
        assertTrue(result is SolveResult.Success, "contain + distance + align must solve: $result")
        val boxes = result.boxes
        approx(50.0, boxes["b"]!!.left - boxes["a"]!!.right)
        approx(boxes["a"]!!.cy, boxes["b"]!!.cy)
        boxes.values.forEach { box ->
            assertTrue(box.x >= 20.0 - 1e-6 && box.right <= 180.0 + 1e-6, "x bounds violated: $box")
            assertTrue(box.y >= 10.0 - 1e-6 && box.bottom <= 90.0 + 1e-6, "y bounds violated: $box")
        }
    }

    /** Spec risk §7.1: a REQUIRED span larger than the canvas is honestly unsatisfiable; the
     *  failing add names a constraint id for the revise loop (the last-added one, as today). */
    @Test fun `a span larger than the canvas with contain is unsatisfiable and names a constraint`() {
        val graph = SceneGraph(
            nodes = mapOf(
                "a" to leaf("a", 0.0, 0.0, 10.0, 10.0),
                "b" to leaf("b", 0.0, 0.0, 10.0, 10.0),
            ),
            rootIds = listOf("a", "b"),
            canvas = CanvasSpec(200.0, 100.0),
            constraints = ConstraintSet(listOf(
                SceneConstraint.Contain("k1", "a", margin = 0.1),
                SceneConstraint.Contain("k2", "b", margin = 0.1),
                SceneConstraint.Distance("d", "a", Anchor.CENTER_X, "b", Anchor.CENTER_X, pixels = 400.0),
            )),
        )
        val result = ConstraintSolver.solve(graph, pinSizes = true)
        assertTrue(result is SolveResult.Unsatisfiable, "400px span cannot fit a 200px canvas: $result")
        assertEquals("d", result.constraintId)
    }

    /** Spec §3.2 strut interaction: contain bounds a strut's box; its STRONG size stay yields to
     *  the REQUIRED inequalities — it compresses (declared 300 -> max 160), the solve succeeds. */
    @Test fun `contain compresses an oversized strut instead of failing`() {
        val graph = SceneGraph(
            nodes = mapOf(
                "a" to leaf("a", 0.0, 0.0, 10.0, 10.0),
                "b" to leaf("b", 130.0, 0.0, 10.0, 10.0),
                "strut" to strutLine("strut", 0.0, 0.0, 300.0),
            ),
            rootIds = listOf("a", "b", "strut"),
            canvas = CanvasSpec(200.0, 100.0),
            constraints = ConstraintSet(listOf(
                SceneConstraint.Connect("k1", "strut", Anchor.LEFT_CENTER, "a", Anchor.CENTER),
                SceneConstraint.Connect("k2", "strut", Anchor.RIGHT_CENTER, "b", Anchor.CENTER),
                SceneConstraint.Contain("kc", "strut", margin = 0.1),
            )),
        )
        val result = ConstraintSolver.solve(graph, pinSizes = true)
        assertTrue(result is SolveResult.Success, "oversized strut must compress, not fail: $result")
        val boxes = result.boxes
        approx(160.0, boxes["strut"]!!.width) // capped at (1-2*0.1)*200 = 160, down from declared 300
        approx(20.0, boxes["strut"]!!.x)      // dragged to the left bound by the STRONG width stay
    }

    /** Edge-case regression #1: a pinned-size rigid body wider than the allowed span fails and
     *  names the contain constraint (unlike struts, rigid bodies have REQUIRED size stays). */
    @Test fun `contain names itself when a pinned rigid body exceeds the allowed span`() {
        val graph = SceneGraph(
            nodes = mapOf("a" to leaf("a", 0.0, 0.0, 300.0, 10.0)),
            rootIds = listOf("a"),
            canvas = CanvasSpec(200.0, 100.0),
            constraints = ConstraintSet.EMPTY.add(SceneConstraint.Contain("k1", "a", margin = 0.1)),
        )
        val result = ConstraintSolver.solve(graph, pinSizes = true)
        assertTrue(result is SolveResult.Unsatisfiable, "300-wide rigid body cannot fit 160 span: $result")
        assertEquals("k1", result.constraintId, "must name the contain constraint")
    }

    /** Edge-case regression #2: margin = 0.5 allows zero span (a centerline band), so a pinned node
     *  becomes Unsatisfiable — its REQUIRED size stay fights the REQUIRED inequalities. */
    @Test fun `contain with margin 0 5 fails on a pinned node (zero allowed span)`() {
        val graph = SceneGraph(
            nodes = mapOf("a" to leaf("a", 0.0, 0.0, 10.0, 10.0)),
            rootIds = listOf("a"),
            canvas = CanvasSpec(200.0, 100.0),
            constraints = ConstraintSet.EMPTY.add(SceneConstraint.Contain("k1", "a", margin = 0.5)),
        )
        val result = ConstraintSolver.solve(graph, pinSizes = true)
        assertTrue(result is SolveResult.Unsatisfiable, "margin 0.5 allows zero span; pinned node must fail: $result")
        assertEquals("k1", result.constraintId)
    }

    /** Engine consistency (spec 2026-08-18): what the solver solved is what the node renders.
     *  The strut's box is stretched to 300 by the REQUIRED distance; after solveInto the line's
     *  ENDPOINTS must span the same 300 (the old translate-only withBox kept them at 100). */
    @Test fun `a stretched strut renders the span the solver solved`() {
        val graph = SceneGraph(
            nodes = mapOf(
                "a" to leaf("a", 0.0, 0.0, 10.0, 10.0),
                "b" to leaf("b", 200.0, 0.0, 10.0, 10.0),
                "strut" to strutLine("strut", 0.0, 0.0, 100.0),
            ),
            rootIds = listOf("a", "b", "strut"),
            constraints = ConstraintSet(listOf(
                SceneConstraint.Connect("k1", "strut", Anchor.LEFT_CENTER, "a", Anchor.CENTER),
                SceneConstraint.Connect("k2", "strut", Anchor.RIGHT_CENTER, "b", Anchor.CENTER),
                SceneConstraint.Distance("d", "a", Anchor.CENTER_X, "b", Anchor.CENTER_X, pixels = 300.0),
            )),
        )
        val (solved, result) = DraftsEngine().solveInto(graph, pinSizes = true)
        assertTrue(result is SolveResult.Success, "strut chain + distance must solve: $result")
        val solvedWidth = (result as SolveResult.Success).boxes["strut"]!!.width
        val strut = solved.nodes["strut"] as LineNode
        approx(300.0, solvedWidth, 1e-3)                                  // solver stretched the box
        approx(solvedWidth, abs(strut.end.first - strut.start.first), 1e-6) // endpoints render the same span
    }

    // ---- relative_size + distribute (spec 2026-08-20) ----

    /** Uniform scale, DIRECTIONAL: the TARGET (rear) is the reference — its declared size stays
     *  REQUIRED; only the SOURCE (front) demotes to STRONG and scales onto it. Declared front 80
     *  must GROW to 1.6×60 = 96 — never the inverted compromise (rear shrinking to 50). */
    @Test fun `relative_size scales both axes uniformly and overrides declared sizes`() {
        val graph = SceneGraph(
            nodes = mapOf(
                "rear" to leaf("rear", 0.0, 0.0, 60.0, 60.0),
                "front" to leaf("front", 0.0, 0.0, 80.0, 80.0), // declared 80 — the ratio says 96
            ),
            rootIds = listOf("rear", "front"),
            canvas = CanvasSpec(500.0, 500.0),
            constraints = ConstraintSet.EMPTY.add(
                SceneConstraint.RelativeSize("rs", "front", "rear", ratio = 1.6)
            ),
        )
        val boxes = (ConstraintSolver.solve(graph, pinSizes = true) as SolveResult.Success).boxes
        approx(60.0, boxes["rear"]!!.width, 1e-3)    // target/reference holds its declared size
        approx(96.0, boxes["front"]!!.width, 1e-3)   // source grows to ratio × reference
        approx(96.0, boxes["front"]!!.height, 1e-3)  // uniform: both axes at the ratio
        approx(boxes["front"]!!.width, boxes["front"]!!.height, 1e-6) // aspect preserved (no ellipse)
    }

    @Test fun `distribute equalizes consecutive gaps along the width axis`() {
        val graph = SceneGraph(
            nodes = mapOf(
                "a" to leaf("a", 0.0, 0.0, 20.0, 10.0),
                "b" to leaf("b", 100.0, 0.0, 20.0, 10.0), // initial gaps 80 and 180
                "c" to leaf("c", 300.0, 0.0, 20.0, 10.0),
            ),
            rootIds = listOf("a", "b", "c"),
            canvas = CanvasSpec(500.0, 500.0),
            constraints = ConstraintSet.EMPTY.add(
                SceneConstraint.Distribute("d", listOf("a", "b", "c"), AnchorAxis.X)
            ),
        )
        val boxes = (ConstraintSolver.solve(graph) as SolveResult.Success).boxes
        val gap1 = boxes["b"]!!.left - boxes["a"]!!.right
        val gap2 = boxes["c"]!!.left - boxes["b"]!!.right
        approx(gap1, gap2, 1e-6)
    }

    @Test fun `distribute equalizes gaps along the height axis`() {
        val graph = SceneGraph(
            nodes = mapOf(
                "a" to leaf("a", 0.0, 0.0, 10.0, 20.0),
                "b" to leaf("b", 0.0, 100.0, 10.0, 20.0),
                "c" to leaf("c", 0.0, 300.0, 10.0, 20.0),
            ),
            rootIds = listOf("a", "b", "c"),
            canvas = CanvasSpec(500.0, 500.0),
            constraints = ConstraintSet.EMPTY.add(
                SceneConstraint.Distribute("d", listOf("a", "b", "c"), AnchorAxis.Y)
            ),
        )
        val boxes = (ConstraintSolver.solve(graph) as SolveResult.Success).boxes
        val gap1 = boxes["b"]!!.top - boxes["a"]!!.bottom
        val gap2 = boxes["c"]!!.top - boxes["b"]!!.bottom
        approx(gap1, gap2, 1e-6)
    }

    /** Spec §4 acceptance #1, third clause: over-constrained combinations fail HONESTLY and name
     *  a constraint for the revise loop — two conflicting ratios on the same pair is the
     *  canonical impossible set (width can't be both 1.5x and 2x of the reference). */
    @Test fun `conflicting relative_size ratios are unsatisfiable and name a constraint`() {
        val graph = SceneGraph(
            nodes = mapOf(
                "rear" to leaf("rear", 0.0, 0.0, 60.0, 60.0),
                "front" to leaf("front", 0.0, 0.0, 80.0, 80.0),
            ),
            rootIds = listOf("rear", "front"),
            canvas = CanvasSpec(500.0, 500.0),
            constraints = ConstraintSet(listOf(
                SceneConstraint.RelativeSize("rs1", "front", "rear", ratio = 1.5),
                SceneConstraint.RelativeSize("rs2", "front", "rear", ratio = 2.0),
            )),
        )
        val result = ConstraintSolver.solve(graph, pinSizes = true)
        assertTrue(result is SolveResult.Unsatisfiable, "conflicting ratios must fail: $result")
        assertTrue(result.constraintId != null, "the failing constraint must be named: $result")
    }
}
