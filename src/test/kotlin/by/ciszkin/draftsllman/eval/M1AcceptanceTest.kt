package by.ciszkin.draftsllman.eval

import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.ResolvedBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M1 acceptance tests: the scoring anchor that decision-gates the vision-first bet.
 *
 * From the eval-framework §10 decision gates: a perfect recovery must score F1=1.0, and a degenerate
 * collapse (everything on top of each other) must score F1=0. This proves the scorer can distinguish
 * "the pipeline saw through the sketch" from "the pipeline collapsed" — the exact signal that decides
 * whether we commit to vision-first or keep the geometric hybrid (M1 spec, T3 threshold ~0.80).
 */
class M1AcceptanceTest {

    /** Helper: build any wireframe truth and feed the scorer the truth itself as a "perfect" prediction. */
    private fun wireframeCase(): Wireframe =
        WireframeDSL().build(seed = 7)

    @Test
    fun `perfect pipeline scores F1 equals 1_0`() {
        val wf = wireframeCase()
        val truth = wf.truth
        // A "perfect" prediction is EXACTLY the truth (minus annotations, which must be excluded).
        val perfect = Prediction(components = truth.components, fileName = "perfect")
        val matched = T3Geometric.match(perfect.components, truth.components)
        assertEquals(matched.tp, truth.components.size)
        assertEquals(0, matched.fp)
        assertEquals(0, matched.fn)
        assertEquals(1.0, T3Geometric.f1(matched), 1e-9)
    }

    @Test
    fun `degenerate collapse scores F1 equals 0`() {
        val wf = wireframeCase()
        val truth = wf.truth
        val collapsed = truth.components.map {
            // Everything stacked at one point, minimal size -> zero IoU with the real boxes.
            it.copy(box = ResolvedBox(0.0, 0.0, 0.001, 0.001))
        }
        val pred = Prediction(components = collapsed, fileName = "degenerate")
        val matched = T3Geometric.match(pred.components, truth.components)
        assertTrue(matched.tp == 0, "degenerate collapse must not match (tp was ${matched.tp})")
        assertEquals(0.0, T3Geometric.f1(matched), 1e-9)
    }

    @Test
    fun `annotations in a prediction coarsen away and never inflate component F1`() {
        val wf = wireframeCase()
        val truth = wf.truth
        assertTrue(truth.annotationIds.isNotEmpty(), "fixture must carry annotations to exclude")
        // Prediction that correctly EXCLUDES annotations -> F1 unaffected by them.
        val clean = Prediction(truth.components, "clean")
        val matchedClean = T3Geometric.match(clean.components, truth.components)
        assertEquals(truth.components.size, matchedClean.tp)
    }

    @Test
    fun `zero-area line boxes match via effectiveIoU not raw IoU`() {
        // A LineNode's derived box has zero height/width -> raw IoU is 0 even against itself.
        val line = ResolvedBox(44.0, 170.0, 160.0, 0.0)
        assertEquals(0.0, T3Geometric.iou(line, line), "raw IoU is blind to zero-area boxes")
        assertEquals(1.0, T3Geometric.effectiveIoU(line, line), 1e-9, "thickened lines overlap")

        // A line at a different vertical position must NOT match (perpendicular separation matters).
        val lowerLine = ResolvedBox(44.0, 350.0, 160.0, 0.0)
        assertEquals(0.0, T3Geometric.effectiveIoU(line, lowerLine), 1e-9)
    }

    @Test
    fun `prediction invariants pass for the co-derived truth boxes`() {
        val wf = wireframeCase()
        val truth = wf.truth
        val violated = T0Validity.predictionInvariants(
            Prediction(truth.components, "invariant-check"), truth.canvas,
        )
        assertEquals(emptyList<String>(), violated, "co-derived ground truth must be in-canvas: $violated")
    }
}
