package by.ciszkin.draftsllman.eval

import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.ResolvedBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class EvalScorerTest {

    // ---------- T0: validity ----------

    @Test
    fun svgWellFormed_balanced_svg_isTrue() {
        assertTrue(T0Validity.svgWellFormed("<svg><circle/><rect/></svg>"))
    }

    @Test
    fun svgWellFormed_unbalanced_isFalse() {
        assertFalse(T0Validity.svgWellFormed("<svg><circle></svg>"))
    }

    @Test
    fun predictionInvariants_validBoxInsideCanvas_emptyViolations() {
        val p = Prediction(
            components = listOf(EvComponent("a", "x", PrimitiveKind.RECT, ResolvedBox(0.0, 0.0, 10.0, 10.0))),
            fileName = "t.png",
        )
        assertTrue(T0Validity.predictionInvariants(p, CanvasSpec(20.0, 20.0)).isEmpty())
    }

    @Test
    fun predictionInvariants_boxOverflowingRight_isViolation() {
        val p = Prediction(
            components = listOf(EvComponent("a", "x", PrimitiveKind.RECT, ResolvedBox(15.0, 15.0, 10.0, 10.0))),
            fileName = "t.png",
        )
        val violations = T0Validity.predictionInvariants(p, CanvasSpec(20.0, 20.0))
        assertTrue(violations.any { it.contains("right") || it.contains("width") }, "got: $violations")
    }

    @Test
    fun predictionInvariants_negativeWidth_isViolation() {
        val p = Prediction(
            components = listOf(EvComponent("a", "x", PrimitiveKind.RECT, ResolvedBox(0.0, 0.0, -5.0, 10.0))),
            fileName = "t.png",
        )
        val violations = T0Validity.predictionInvariants(p, CanvasSpec(20.0, 20.0))
        assertTrue(violations.any { it.contains("width") }, "got: $violations")
    }

    // ---------- T3: geometric fidelity ----------

    private fun r(x: Double, y: Double, w: Double, h: Double) = ResolvedBox(x, y, w, h)

    @Test
    fun iou_twoOverlappingRects_knownValue() {
        // A: x 0..4, y 0..4 (16px). B: x 2..6, y 2..6 (16px).
        // Intersection: x 2..4 (2), y 2..4 (2) = 4. Union = 16+16-4 = 28. IoU = 4/28.
        val iou = T3Geometric.iou(r(0.0, 0.0, 4.0, 4.0), r(2.0, 2.0, 4.0, 4.0))
        assertEquals(4.0 / 28.0, iou, 1e-9)
    }

    @Test
    fun iou_contained_isSmallOverBig() {
        val iou = T3Geometric.iou(r(0.0, 0.0, 10.0, 10.0), r(2.0, 2.0, 2.0, 2.0))
        assertEquals(4.0 / 100.0, iou, 1e-9)
    }

    @Test
    fun iou_disjoint_isZero() {
        assertEquals(0.0, T3Geometric.iou(r(0.0, 0.0, 4.0, 4.0), r(10.0, 10.0, 4.0, 4.0)), 1e-9)
    }

    @Test
    fun iou_identical_isOne() {
        assertEquals(1.0, T3Geometric.iou(r(1.0, 1.0, 4.0, 4.0), r(1.0, 1.0, 4.0, 4.0)), 1e-9)
    }

    @Test
    fun iou_degenerate_clampsToZero() {
        // Zero-area rect can't be negative.
        assertTrue(T3Geometric.iou(r(0.0, 0.0, 0.0, 0.0), r(0.0, 0.0, 4.0, 4.0)) >= 0.0)
    }

    @Test
    fun match_perfectOneToOne_tp2_f1one() {
        val a = EvComponent("a", "x", PrimitiveKind.RECT, r(0.0, 0.0, 10.0, 10.0))
        val b = EvComponent("b", "y", PrimitiveKind.CIRCLE, r(20.0, 20.0, 10.0, 10.0))
        val m = T3Geometric.match(listOf(a, b), listOf(a, b), iouTau = 0.5)
        assertEquals(2, m.tp)
        assertEquals(0, m.fp)
        assertEquals(0, m.fn)
        assertEquals(1.0, T3Geometric.f1(m), 1e-9)
    }

    @Test
    fun match_roleMismatch_doesNotMatch() {
        val pred = EvComponent("a", "x", PrimitiveKind.RECT, r(0.0, 0.0, 10.0, 10.0))
        val truth = EvComponent("a", "y", PrimitiveKind.RECT, r(0.0, 0.0, 10.0, 10.0)) // same box, diff role
        val m = T3Geometric.match(listOf(pred), listOf(truth), iouTau = 0.5)
        assertEquals(0, m.tp)
        assertEquals(1, m.fp)
        assertEquals(1, m.fn)
        assertEquals(0.0, T3Geometric.f1(m), 1e-9)
    }

    @Test
    fun match_lowIou_doesNotMatch() {
        val pred = EvComponent("a", "x", PrimitiveKind.RECT, r(0.0, 0.0, 10.0, 10.0))
        val truth = EvComponent("a", "x", PrimitiveKind.RECT, r(100.0, 100.0, 10.0, 10.0)) // same role, disjoint
        val m = T3Geometric.match(listOf(pred), listOf(truth), iouTau = 0.5)
        assertEquals(0, m.tp)
        assertEquals(1, m.fp)
        assertEquals(1, m.fn)
    }

    @Test
    fun confusion_truthCircle_predCirclePlusAnnotation() {
        val truth = listOf(EvComponent("a", "x", PrimitiveKind.CIRCLE, r(0.0, 0.0, 10.0, 10.0)))
        val pred = listOf(
            EvComponent("a", "x", PrimitiveKind.CIRCLE, r(0.0, 0.0, 10.0, 10.0)),
            EvComponent("c", "annotation", PrimitiveKind.ANNOTATION, r(50.0, 50.0, 5.0, 5.0)),
        )
        val cm = T3Geometric.confusion(pred, truth)
        assertEquals(1, cm.counts[PrimitiveKind.CIRCLE.ordinal][PrimitiveKind.CIRCLE.ordinal])
        // The pred annotation is unmatched (truth has no annotation, and annotation != circle anyway).
        assertEquals(0.0, cm.annotationPrecision(), 1e-9)
    }

    @Test
    fun centerError_normalizedByCanvasDiagonal() {
        val canvas = CanvasSpec(100.0, 100.0)
        val a = r(0.0, 0.0, 10.0, 10.0) // center (5,5)
        val b = r(50.0, 50.0, 10.0, 10.0) // center (55,55)
        // distance = sqrt(50^2+50^2) = 70.71 ; diagonal = sqrt(100^2+100^2) = 141.42
        val expected = Math.hypot(50.0, 50.0) / Math.hypot(100.0, 100.0)
        assertEquals(expected, T3Geometric.centerError(a, b, canvas), 1e-9)
    }
}
