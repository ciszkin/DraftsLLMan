package by.ciszkin.draftsllman.solver

import by.ciszkin.draftsllman.scene.Anchor
import by.ciszkin.draftsllman.scene.AnchorAxis
import kasuari.Expression
import kasuari.Variable

/** 4 solver variables per node, top-left convention. Anchors are linear expressions over these. */
data class NodeVars(val x: Variable, val y: Variable, val width: Variable, val height: Variable)

object AnchorMath {
    // Bare Variables are coerced to Expression via `+ 0.0` (Variable.plus(Double): Expression);
    // composed forms use Variable/Expression arithmetic verified against kasuari 0.1.6.
    fun xExpr(v: NodeVars, a: Anchor): Expression = when (a) {
        Anchor.LEFT, Anchor.TOP_LEFT, Anchor.BOTTOM_LEFT, Anchor.LEFT_CENTER -> v.x + 0.0
        Anchor.RIGHT, Anchor.TOP_RIGHT, Anchor.BOTTOM_RIGHT, Anchor.RIGHT_CENTER -> v.x + v.width
        Anchor.CENTER_X, Anchor.CENTER, Anchor.TOP_CENTER, Anchor.BOTTOM_CENTER -> v.x + v.width * 0.5
        else -> error("anchor $a has no X expression (axis=${a.axis})")
    }

    fun yExpr(v: NodeVars, a: Anchor): Expression = when (a) {
        Anchor.TOP, Anchor.TOP_LEFT, Anchor.TOP_RIGHT, Anchor.TOP_CENTER -> v.y + 0.0
        Anchor.BOTTOM, Anchor.BOTTOM_LEFT, Anchor.BOTTOM_RIGHT, Anchor.BOTTOM_CENTER -> v.y + v.height
        Anchor.CENTER_Y, Anchor.CENTER, Anchor.LEFT_CENTER, Anchor.RIGHT_CENTER -> v.y + v.height * 0.5
        else -> error("anchor $a has no Y expression (axis=${a.axis})")
    }

    fun hasX(a: Anchor) = a.axis == AnchorAxis.X || a.axis == AnchorAxis.POINT
    fun hasY(a: Anchor) = a.axis == AnchorAxis.Y || a.axis == AnchorAxis.POINT
}
