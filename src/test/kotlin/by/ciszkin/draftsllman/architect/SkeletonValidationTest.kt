package by.ciszkin.draftsllman.architect

import by.ciszkin.draftsllman.scene.CanvasSpec
import kotlin.test.Test
import kotlin.test.assertTrue

class SkeletonValidationTest {
    private val canvas = CanvasSpec(200.0, 200.0)

    private fun comp(id: String, type: String = "circle", w: Double = 10.0, h: Double = 10.0) =
        SkeletonComponent(id, "role", type, w, h, mapOf("hub" to "CENTER"))

    @Test
    fun `a connected skeleton passes`() {
        val s = EmitSkeleton(
            components = listOf(comp("a"), comp("b")),
            constraints = listOf(SkeletonConstraint("c1", "connect", "a:hub", "b:hub")),
        )
        assertTrue(validateSkeleton(s, canvas).isEmpty())
    }

    @Test
    fun `empty components are rejected`() {
        assertTrue(validateSkeleton(EmitSkeleton(), canvas).any { it.path == "components" })
    }

    @Test
    fun `duplicate component ids are rejected`() {
        val s = EmitSkeleton(components = listOf(comp("a"), comp("a")))
        assertTrue(validateSkeleton(s, canvas).any { it.path == "components" && it.message.contains("duplicate") })
    }

    @Test
    fun `unsupported type and non-positive size are rejected`() {
        val s = EmitSkeleton(components = listOf(SkeletonComponent("a", "r", "hexagon", 0.0, 10.0)))
        val errors = validateSkeleton(s, canvas)
        assertTrue(errors.any { it.path == "components[0].type" })
        assertTrue(errors.any { it.path == "components[0].size" })
    }

    @Test
    fun `unknown kind and unknown component ref are rejected`() {
        val s = EmitSkeleton(
            components = listOf(comp("a")),
            constraints = listOf(SkeletonConstraint("c1", "weld", "a:hub", "ghost:hub")),
        )
        val errors = validateSkeleton(s, canvas)
        assertTrue(errors.any { it.path == "constraints[0].kind" })
        assertTrue(errors.any { it.path == "constraints[0].target" && it.message.contains("unknown component") })
    }

    @Test
    fun `a valid distance_ratio on canvas_width passes`() {
        val s = EmitSkeleton(
            components = listOf(comp("rear"), comp("front")),
            constraints = listOf(
                SkeletonConstraint("gap", "distance_ratio", "rear:CENTER_X", "front:CENTER_X", ratio = 0.6, relativeTo = "canvas_width"),
            ),
        )
        assertTrue(validateSkeleton(s, canvas).isEmpty())
    }

    @Test
    fun `a valid distance_ratio on canvas_height passes`() {
        val s = EmitSkeleton(
            components = listOf(comp("top"), comp("bottom")),
            constraints = listOf(
                SkeletonConstraint("gap", "distance_ratio", "top:CENTER_Y", "bottom:CENTER_Y", ratio = 0.4, relativeTo = "canvas_height"),
            ),
        )
        assertTrue(validateSkeleton(s, canvas).isEmpty())
    }

    @Test
    fun `distance_ratio rejects a ratio above 1`() {
        val s = EmitSkeleton(
            components = listOf(comp("rear"), comp("front")),
            constraints = listOf(
                SkeletonConstraint("gap", "distance_ratio", "rear:CENTER_X", "front:CENTER_X", ratio = 1.5, relativeTo = "canvas_width"),
            ),
        )
        assertTrue(validateSkeleton(s, canvas).any { it.message.contains("ratio must be") })
    }

    @Test
    fun `distance_ratio rejects a ratio of zero`() {
        val s = EmitSkeleton(
            components = listOf(comp("rear"), comp("front")),
            constraints = listOf(
                SkeletonConstraint("gap", "distance_ratio", "rear:CENTER_X", "front:CENTER_X", ratio = 0.0, relativeTo = "canvas_width"),
            ),
        )
        assertTrue(validateSkeleton(s, canvas).any { it.message.contains("ratio must be") })
    }

    @Test
    fun `distance_ratio accepts the boundary ratio of one`() {
        val s = EmitSkeleton(
            components = listOf(comp("rear"), comp("front")),
            constraints = listOf(
                SkeletonConstraint("gap", "distance_ratio", "rear:CENTER_X", "front:CENTER_X", ratio = 1.0, relativeTo = "canvas_width"),
            ),
        )
        assertTrue(validateSkeleton(s, canvas).isEmpty())
    }

    @Test
    fun `distance_ratio rejects an unknown relative_to`() {
        val s = EmitSkeleton(
            components = listOf(comp("rear"), comp("front")),
            constraints = listOf(
                SkeletonConstraint("gap", "distance_ratio", "rear:CENTER_X", "front:CENTER_X", ratio = 0.6, relativeTo = "diagonal"),
            ),
        )
        assertTrue(validateSkeleton(s, canvas).any { it.message.contains("relative_to must be") })
    }

    @Test
    fun `distance_ratio on canvas_height rejects an X-axis anchor`() {
        val s = EmitSkeleton(
            components = listOf(comp("rear"), comp("front")),
            constraints = listOf(
                SkeletonConstraint("gap", "distance_ratio", "rear:CENTER_X", "front:CENTER_X", ratio = 0.6, relativeTo = "canvas_height"),
            ),
        )
        assertTrue(validateSkeleton(s, canvas).any { it.message.contains("-axis") })
    }

    @Test
    fun `distance_ratio on canvas_width rejects a Y-axis anchor`() {
        val s = EmitSkeleton(
            components = listOf(comp("rear"), comp("front")),
            constraints = listOf(
                SkeletonConstraint("gap", "distance_ratio", "rear:CENTER_Y", "front:CENTER_Y", ratio = 0.6, relativeTo = "canvas_width"),
            ),
        )
        assertTrue(validateSkeleton(s, canvas).any { it.message.contains("-axis") })
    }

    @Test
    fun `an alias name colliding with a geometric anchor is rejected`() {
        // Reproduces the Slices 3/4 pipeline crash: alias "top" collides with Anchor.TOP. The old
        // alias-first validator resolved "top" to the alias (RIGHT_CENTER, POINT) and approved the
        // connect; the geometric-first builder then resolved "top" to Anchor.TOP (Y-axis) and threw
        // in SceneConstraint.Connect. The validator must now reject this up front.
        val s = EmitSkeleton(
            components = listOf(
                SkeletonComponent("seat_stay", "stay", "line", 100.0, 60.0, mapOf("top" to "RIGHT_CENTER")),
                SkeletonComponent("seattube", "tube", "rect", 40.0, 60.0, mapOf("top" to "RIGHT_CENTER")),
            ),
            constraints = listOf(SkeletonConstraint("c1", "connect", "seat_stay:top", "seattube:top")),
        )
        val errors = validateSkeleton(s, canvas)
        assertTrue(
            errors.any { it.message.contains("collides with a reserved geometric anchor name") },
            "expected a collision error but got: $errors"
        )
    }

    @Test
    fun `a non-colliding alias used in connect is approved`() {
        val s = EmitSkeleton(
            components = listOf(
                SkeletonComponent("seat_stay", "stay", "line", 100.0, 60.0, mapOf("topPt" to "RIGHT_CENTER")),
                SkeletonComponent("seattube", "tube", "rect", 40.0, 60.0, mapOf("topPt" to "RIGHT_CENTER")),
            ),
            constraints = listOf(SkeletonConstraint("c1", "connect", "seat_stay:topPt", "seattube:topPt")),
        )
        val errors = validateSkeleton(s, canvas)
        assertTrue(errors.isEmpty(), "expected no errors for a non-colliding alias but got: $errors")
    }

    @Test
    fun `a geometric anchor used directly in connect is approved`() {
        val s = EmitSkeleton(
            components = listOf(comp("a"), comp("b")),
            constraints = listOf(SkeletonConstraint("c1", "connect", "a:CENTER", "b:CENTER")),
        )
        assertTrue(validateSkeleton(s, canvas).isEmpty())
    }

    @Test
    fun `a validator-approved skeleton with aliases builds without throwing`() {
        // The real guarantee: whatever the validator approves, the builder must build (no divergence).
        val s = EmitSkeleton(
            components = listOf(
                SkeletonComponent("frame", "chassis", "line", 200.0, 100.0, mapOf("rearAxle" to "BOTTOM_LEFT")),
                SkeletonComponent("wheel", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
            ),
            constraints = listOf(SkeletonConstraint("link", "connect", "frame:rearAxle", "wheel:hub")),
        )
        assertTrue(validateSkeleton(s, canvas).isEmpty())
        SkeletonBuilder.build(s, canvas)  // must not throw
    }

    @Test
    fun `a lowercase alias geometric value is accepted and builds without throwing`() {
        // The full validator->builder guarantee for alias VALUES (the residual case-sensitivity bug):
        // the validator accepts "center"/"bottom_left" (case-insensitive); the builder must build them
        // too — previously it threw Anchor.valueOf("center") (case-sensitive) while validation passed.
        val s = EmitSkeleton(
            components = listOf(
                SkeletonComponent("frame", "chassis", "line", 200.0, 100.0, mapOf("rearAxle" to "bottom_left")),
                SkeletonComponent("wheel", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "center")),
            ),
            constraints = listOf(SkeletonConstraint("link", "connect", "frame:rearAxle", "wheel:hub")),
        )
        assertTrue(validateSkeleton(s, canvas).isEmpty(), "validator should accept lowercase geometric values")
        SkeletonBuilder.build(s, canvas)  // must not throw
    }

    // ---- contain (spec 2026-08-17) ----

    @Test
    fun `a contain constraint with a plain component id and canvas target passes`() {
        val s = EmitSkeleton(
            components = listOf(comp("seat"), comp("frame")),
            constraints = listOf(SkeletonConstraint("k1", "contain", "seat", "canvas", margin = 0.02)),
        )
        assertTrue(validateSkeleton(s, canvas).isEmpty())
    }

    @Test
    fun `contain accepts the default margin`() {
        val s = EmitSkeleton(
            components = listOf(comp("seat")),
            constraints = listOf(SkeletonConstraint("k1", "contain", "seat", "canvas")),
        )
        assertTrue(validateSkeleton(s, canvas).isEmpty())
    }

    @Test
    fun `contain rejects an anchor-shaped source with guidance`() {
        val s = EmitSkeleton(
            components = listOf(comp("seat")),
            constraints = listOf(SkeletonConstraint("k1", "contain", "seat:topPt", "canvas")),
        )
        val errors = validateSkeleton(s, canvas)
        assertTrue(errors.any { it.path == "constraints[0].source" && it.message.contains("plain component id") },
            "expected anchor-ref guidance, got: $errors")
    }

    @Test
    fun `contain rejects a target other than canvas`() {
        val s = EmitSkeleton(
            components = listOf(comp("seat"), comp("frame")),
            constraints = listOf(SkeletonConstraint("k1", "contain", "seat", "frame")),
        )
        assertTrue(validateSkeleton(s, canvas).any { it.path == "constraints[0].target" && it.message.contains("canvas") })
    }

    @Test
    fun `contain rejects margins outside the 0 to 0 5 range`() {
        for (bad in listOf(-0.1, 0.6)) {
            val s = EmitSkeleton(
                components = listOf(comp("seat")),
                constraints = listOf(SkeletonConstraint("k1", "contain", "seat", "canvas", margin = bad)),
            )
            assertTrue(validateSkeleton(s, canvas).any { it.path == "constraints[0].margin" && it.message.contains("[0.0, 0.5]") },
                "margin $bad must be rejected")
        }
    }

    @Test
    fun `contain rejects a margin whose band is too small for a rigid component`() {
        // Canvas is 200×200, comp("seat") is 10×10. margin 0.48 → band = 0.04 → 0.04*200 = 8 < 10 → reject.
        val s = EmitSkeleton(
            components = listOf(comp("seat")),
            constraints = listOf(SkeletonConstraint("k1", "contain", "seat", "canvas", margin = 0.48)),
        )
        assertTrue(validateSkeleton(s, canvas).any { it.path == "constraints[0].margin" && it.message.contains("Lower the margin") })
    }

    @Test
    fun `contain accepts a small margin for the same rigid component`() {
        // margin 0.02 → band 0.96 → 192 ≥ 10 → pass.
        val s = EmitSkeleton(
            components = listOf(comp("seat")),
            constraints = listOf(SkeletonConstraint("k1", "contain", "seat", "canvas", margin = 0.02)),
        )
        assertTrue(validateSkeleton(s, canvas).isEmpty())
    }

    @Test
    fun `contain exempts line components from the band check`() {
        // A line (stretchable strut) wider than any band on a 200 canvas should pass — it may compress at solve.
        val s = EmitSkeleton(
            components = listOf(SkeletonComponent("frame", "tube", "line", 300.0, 10.0)),
            constraints = listOf(SkeletonConstraint("k1", "contain", "frame", "canvas", margin = 0.1)),
        )
        assertTrue(validateSkeleton(s, canvas).isEmpty())
    }

    @Test
    fun `contain rejects margin 0 5 on a rigid component`() {
        // margin 0.5 → band 0.0 → always too small for positive sizes.
        val s = EmitSkeleton(
            components = listOf(comp("seat")),
            constraints = listOf(SkeletonConstraint("k1", "contain", "seat", "canvas", margin = 0.5)),
        )
        assertTrue(validateSkeleton(s, canvas).any { it.path == "constraints[0].margin" && it.message.contains("Lower the margin") })
    }

    @Test
    fun `contain rejects an unknown component source`() {
        val s = EmitSkeleton(
            components = listOf(comp("seat")),
            constraints = listOf(SkeletonConstraint("k1", "contain", "ghost", "canvas")),
        )
        assertTrue(validateSkeleton(s, canvas).any { it.path == "constraints[0].source" && it.message.contains("unknown component") })
    }

    // ---- relative_size + distribute (spec 2026-08-20) ----

    @Test
    fun `a relative_size constraint with plain ids and a valid ratio passes`() {
        val s = EmitSkeleton(
            components = listOf(comp("rear"), comp("front")),
            constraints = listOf(SkeletonConstraint("rs", "relative_size", "front", "rear", ratio = 1.6)),
        )
        assertTrue(validateSkeleton(s, canvas).isEmpty())
    }

    @Test
    fun `relative_size rejects anchor-shaped refs and out-of-range ratios`() {
        val anchorShaped = EmitSkeleton(
            components = listOf(comp("rear"), comp("front")),
            constraints = listOf(SkeletonConstraint("rs", "relative_size", "front:hub", "rear", ratio = 1.6)),
        )
        assertTrue(validateSkeleton(anchorShaped, canvas).any { it.path == "constraints[0].source" && it.message.contains("plain component ids") })
        for (bad in listOf(0.0, 5.5)) {
            val s = EmitSkeleton(
                components = listOf(comp("rear"), comp("front")),
                constraints = listOf(SkeletonConstraint("rs", "relative_size", "front", "rear", ratio = bad)),
            )
            assertTrue(validateSkeleton(s, canvas).any { it.path == "constraints[0].ratio" && it.message.contains("(0.0, 5.0]") },
                "ratio $bad must be rejected")
        }
    }

    @Test
    fun `relative_size rejects unknown components`() {
        val s = EmitSkeleton(
            components = listOf(comp("rear")),
            constraints = listOf(SkeletonConstraint("rs", "relative_size", "front", "rear", ratio = 1.6)),
        )
        assertTrue(validateSkeleton(s, canvas).any { it.path == "constraints[0].source" && it.message.contains("unknown component") })
    }

    @Test
    fun `a distribute constraint with three known components passes`() {
        val s = EmitSkeleton(
            components = listOf(comp("p1"), comp("p2"), comp("p3")),
            constraints = listOf(SkeletonConstraint("d", "distribute", "", "",
                components = listOf("p1", "p2", "p3"), along = "width")),
        )
        assertTrue(validateSkeleton(s, canvas).isEmpty())
    }

    @Test
    fun `distribute rejects fewer than three components`() {
        val s = EmitSkeleton(
            components = listOf(comp("p1"), comp("p2")),
            constraints = listOf(SkeletonConstraint("d", "distribute", "", "",
                components = listOf("p1", "p2"), along = "width")),
        )
        assertTrue(validateSkeleton(s, canvas).any { it.path == "constraints[0].components" && it.message.contains("at least 3") })
    }

    @Test
    fun `distribute rejects unknown and duplicate components`() {
        val s = EmitSkeleton(
            components = listOf(comp("p1"), comp("p2"), comp("p3")),
            constraints = listOf(SkeletonConstraint("d", "distribute", "", "",
                components = listOf("p1", "p2", "ghost", "p2"), along = "width")),
        )
        val errors = validateSkeleton(s, canvas)
        assertTrue(errors.any { it.path == "constraints[0].components" && it.message.contains("unknown component 'ghost'") })
        assertTrue(errors.any { it.path == "constraints[0].components" && it.message.contains("duplicate") })
    }

    @Test
    fun `distribute rejects a bad along value`() {
        val s = EmitSkeleton(
            components = listOf(comp("p1"), comp("p2"), comp("p3")),
            constraints = listOf(SkeletonConstraint("d", "distribute", "", "",
                components = listOf("p1", "p2", "p3"), along = "diagonal")),
        )
        assertTrue(validateSkeleton(s, canvas).any { it.path == "constraints[0].along" && it.message.contains("width") })
    }

    /** Live-run finding (2026-08-21): a model declared wheels 0.2x0.2 — sizes read as canvas
     *  FRACTIONS (every other field in the contract is ratio-based) and solved to points →
     *  degenerate → flail → Safe-Harbor. Declared sizes are PIXELS; sub-pixel-scale declarations
     *  are rejected with a teaching message instead of wasting the emit budget. */
    @Test
    fun `suspiciously small declared sizes are rejected as fraction mistakes`() {
        val s = EmitSkeleton(components = listOf(SkeletonComponent("wheel", "wheel", "circle", 0.2, 0.2)))
        val errors = validateSkeleton(s, canvas)
        assertTrue(errors.any { it.path == "components[0].size" && it.message.contains("PIXELS") },
            "fraction-looking sizes must be rejected with teaching: $errors")
        // Normal pixel-scale declarations are unaffected.
        assertTrue(validateSkeleton(EmitSkeleton(components = listOf(comp("seat"))), canvas).isEmpty())
    }

    /** Id charset: ':' in a component id is never rejected at declaration but breaks every anchor
     *  ref (parseRef splits at the FIRST colon — "bike:wheel:hub" resolves the wrong component). */
    @Test
    fun `component ids containing a colon are rejected`() {
        val s = EmitSkeleton(components = listOf(
            SkeletonComponent("bike:wheel", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
            comp("other"),
        ))
        val errors = validateSkeleton(s, canvas)
        assertTrue(errors.any { it.path == "components[0].id" && it.message.contains("':'") },
            "colon ids must be rejected with teaching: $errors")
    }

    /** Component-level kinds (contain / relative_size / distribute) on a GROUP component pass
     *  validation today and die as engine diagnostics — burning emit budget (crash-safe since
     *  de58250, but the validator should teach earlier). connect/align/distance_ratio on groups
     *  deliberately KEEP the engine-diagnostic path (pinned by EmitSkeletonToolTest). */
    @Test
    fun `contain relative_size and distribute on group components are rejected`() {
        val group = SkeletonComponent("cluster", "assembly", "group", 100.0, 100.0)
        val leaf = comp("wheel")
        val cases = listOf(
            EmitSkeleton(components = listOf(group, leaf),
                constraints = listOf(SkeletonConstraint("k", "contain", "cluster", "canvas", margin = 0.02))),
            EmitSkeleton(components = listOf(group, leaf),
                constraints = listOf(SkeletonConstraint("rs", "relative_size", "wheel", "cluster", ratio = 1.5))),
            EmitSkeleton(components = listOf(group, leaf, comp("p2"), comp("p3")),
                constraints = listOf(SkeletonConstraint("d", "distribute", "", "",
                    components = listOf("cluster", "p2", "p3"), along = "width"))),
        )
        cases.forEach { s ->
            val errors = validateSkeleton(s, canvas)
            assertTrue(errors.any { it.message.contains("group") },
                "group components must be rejected for component-level kinds: $errors")
        }
        // connect-on-group keeps the engine-diagnostic path (the designed catch-all).
        val connectOnGroup = EmitSkeleton(components = listOf(group, leaf),
            constraints = listOf(SkeletonConstraint("c", "connect", "cluster:CENTER", "wheel:hub")))
        assertTrue(validateSkeleton(connectOnGroup, canvas).isEmpty(),
            "connect-on-group must stay validator-accepted (engine-diagnostic path)")
    }
}
