package by.ciszkin.draftsllman.scene

import kotlinx.serialization.Serializable

/**
 * A bezier path segment used by [CurveNode]. When [controlPoint2] is null the path is a quadratic
 * bezier (`M start Q c1 end`); when set, it is a cubic (`M start C c1 c2 end`). [tension] is a
 * designer-facing bow factor (0.0 = flat chord) carried through for downstream tooling; it is not
 * used by [CurveNode.toSvgString] itself.
 */
@Serializable
data class BezierPath(
    val startPoint: Pair<Double, Double>,
    val endPoint: Pair<Double, Double>,
    val controlPoint1: Pair<Double, Double>,
    val controlPoint2: Pair<Double, Double>? = null,
    val tension: Double = 0.5,
) {
    /** True when this is a cubic bezier (both control points present). */
    val isCubic: Boolean get() = controlPoint2 != null
}

/**
 * Compute a quadratic bezier control point for the chord [start]→[end] that bows the curve away from
 * the chord by [tension]. The control point is placed at the chord midpoint displaced along the
 * chord's perpendicular by `tension / 2` times the chord vector — deterministic (no normal-direction
 * ambiguity) and yields a smooth family from flat (0.0) to strongly bowed (>0.5).
 */
fun computeQuadraticControl(
    start: Pair<Double, Double>,
    end: Pair<Double, Double>,
    tension: Double = 0.5,
): Pair<Double, Double> {
    val (sx, sy) = start
    val (ex, ey) = end
    val midX = (sx + ex) / 2.0
    val midY = (sy + ey) / 2.0
    // Perpendicular to chord (ex-sx, ey-sy) is (-(ey-sy), ex-sx); scale by tension/2.
    val px = -(ey - sy) * tension / 2.0
    val py = (ex - sx) * tension / 2.0
    return Pair(midX + px, midY + py)
}
