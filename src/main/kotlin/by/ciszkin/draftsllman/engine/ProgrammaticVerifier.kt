package by.ciszkin.draftsllman.engine

import by.ciszkin.draftsllman.scene.CurveNode
import by.ciszkin.draftsllman.scene.GroupNode
import by.ciszkin.draftsllman.scene.InstanceNode
import by.ciszkin.draftsllman.scene.LineNode
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.ShapeNode
import by.ciszkin.draftsllman.solver.ConstraintSet

data class VerifierVerdict(val ok: Boolean, val errors: List<String>)

object ProgrammaticVerifier {
    fun verify(graph: SceneGraph, constraints: ConstraintSet): VerifierVerdict {
        val errors = buildList {
            for ((id, node) in graph.nodes) {
                if (node is GroupNode) continue // structural; box is derived from children, not authored
                val b = node.box
                if (b.x.isNaN() || b.y.isNaN() || b.width.isNaN() || b.height.isNaN())
                    add("node '$id' has non-finite geometry")
                // Circle/Rect author their box as the rendered SIZE — both dimensions must be positive.
                // InstanceNode is a position-only <use> with NO inherent size (box is (x,y,0,0)); it is
                // exempt from the size check entirely. Line/Curve/Shape DERIVE the box from points/
                // endpoints, so an axis-aligned line legitimately has a zero width OR height (e.g. a
                // horizontal spoke); only a fully degenerate point box (both <= 0) is invalid. Without
                // these exemptions, add_nodes detail lines/instances are wrongly rejected and the whole
                // mutation rolls back.
                val derivedBox = node is LineNode || node is CurveNode || node is ShapeNode
                val noInherentSize = node is InstanceNode
                if (derivedBox) {
                    if (b.width <= 0.0 && b.height <= 0.0)
                        add("node '$id' has a degenerate (point) box (${b.width}x${b.height})")
                } else if (!noInherentSize) {
                    if (b.width <= 0.0 || b.height <= 0.0)
                        add("node '$id' has non-positive size (${b.width}x${b.height})")
                }
            }
            for (c in constraints.constraints)
                for (ref in c.references())
                    if (ref !in graph.nodes) add("constraint '${c.id}' references missing node '$ref'")
        }
        return VerifierVerdict(errors.isEmpty(), errors)
    }
}
