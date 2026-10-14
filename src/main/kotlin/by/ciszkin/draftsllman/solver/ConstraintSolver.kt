package by.ciszkin.draftsllman.solver

import by.ciszkin.draftsllman.scene.Anchor
import by.ciszkin.draftsllman.scene.AnchorAxis
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.GroupNode
import by.ciszkin.draftsllman.scene.LineNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.SceneNode
import kasuari.AddConstraintError
import kasuari.Solver
import kasuari.Strength
import kasuari.Variable
import kasuari.WeightedRelation
import kasuari.plus
import kasuari.with

sealed interface SolveResult {
    data class Success(val boxes: Map<String, ResolvedBox>) : SolveResult

    /** [constraintId] names the offending constraint when the failure came from adding one
     *  (the revise-loop diagnostic uses it); null on the catch-all exception path. */
    data class Unsatisfiable(val message: String, val constraintId: String? = null) : SolveResult
}

/** The only file that builds solver constraints (AnchorMath holds the kasuari expression helpers). */
object ConstraintSolver {
    fun solve(graph: SceneGraph, pinSizes: Boolean = false): SolveResult {
        val solver = Solver.new()

        // Struts (Stretchable Struts, spec 2026-08-14): a line with >= 2 DISTINCT anchors pinned by
        // connects spans something (e.g. a frame tube between two wheel hubs) — under pinSizes its
        // size stays relax to STRONG (above other nodes' MEDIUM position preferences, but below the
        // REQUIRED constraint equations) so a REQUIRED distance_ratio/align can stretch it. Everything
        // else keeps REQUIRED sizes: pinSizes' anti-shrink guarantee is preserved for rigid bodies
        // (circles/rects) and cantilever lines, and the degeneracy guard still catches a strut
        // squeezed to a point (the emit gate reports it as `degenerate`).
        val connectedAnchors = mutableMapOf<String, MutableSet<Anchor>>()
        for (c in graph.constraints.constraints) {
            if (c is SceneConstraint.Connect) {
                connectedAnchors.getOrPut(c.nodeA) { mutableSetOf() }.add(c.anchorA)
                connectedAnchors.getOrPut(c.nodeB) { mutableSetOf() }.add(c.anchorB)
            }
        }
        // relative_size SOURCE members (spec 2026-08-20): the TARGET is the reference — its size
        // stay keeps REQUIRED, and only the SOURCE demotes to STRONG (the strut rule), so the
        // ratio scales the source ONTO the target instead of the pair landing at the cheapest
        // declared-size compromise (which could invert the intent — shrinking the reference
        // rather than growing the source).
        val relativeSized = mutableSetOf<String>()
        for (c in graph.constraints.constraints) {
            if (c is SceneConstraint.RelativeSize) relativeSized += c.nodeA
        }
        fun sizeStay(node: SceneNode): Strength = when {
            !pinSizes -> Strength.MEDIUM
            node is LineNode && (connectedAnchors[node.id]?.size ?: 0) >= 2 -> Strength.STRONG
            node.id in relativeSized -> Strength.STRONG
            else -> Strength.REQUIRED
        }

        val leaves: Map<String, NodeVars> = graph.nodes
            .filterValues { it !is GroupNode }
            .mapValues { (_, node) ->
                val v = NodeVars(Variable.new(), Variable.new(), Variable.new(), Variable.new())
                addStay(solver, v.x, node.box.x)
                addStay(solver, v.y, node.box.y)
                addStay(solver, v.width, node.box.width, sizeStay(node))
                addStay(solver, v.height, node.box.height, sizeStay(node))
                v
            }

        return try {
            for (c in graph.constraints.constraints) {
                try {
                    addConstraint(solver, leaves, c, graph.canvas)
                } catch (e: AddConstraintError) {
                    return SolveResult.Unsatisfiable("Failed on constraint ${c.id}: ${e.message}", c.id)
                }
            }
            SolveResult.Success(leaves.mapValues { (_, v) ->
                ResolvedBox(solver.getValue(v.x), solver.getValue(v.y), solver.getValue(v.width), solver.getValue(v.height))
            })
        } catch (e: AddConstraintError) {
            SolveResult.Unsatisfiable(e.toString())
        }
    }

    private fun addStay(solver: Solver, variable: Variable, value: Double, strength: Strength = Strength.MEDIUM) {
        solver.addConstraint(variable with WeightedRelation.EQ(strength) to value)
    }

    private fun addConstraint(solver: Solver, leaves: Map<String, NodeVars>, c: SceneConstraint, canvas: CanvasSpec) {
        fun vars(id: String) = leaves[id] ?: error("constraint references unknown/structural node '$id'")
        val R = Strength.REQUIRED
        when (c) {
            is SceneConstraint.Align -> {
                val a = vars(c.sourceId); val b = vars(c.targetId)
                if (AnchorMath.hasX(c.sourceAnchor))
                    solver.addConstraint(AnchorMath.xExpr(a, c.sourceAnchor) with WeightedRelation.EQ(R) to AnchorMath.xExpr(b, c.targetAnchor))
                if (AnchorMath.hasY(c.sourceAnchor))
                    solver.addConstraint(AnchorMath.yExpr(a, c.sourceAnchor) with WeightedRelation.EQ(R) to AnchorMath.yExpr(b, c.targetAnchor))
            }
            is SceneConstraint.Distance -> {
                // Express as B.anchor == A.anchor + pixels (additive; avoids needing a `-` operator).
                val a = vars(c.nodeA); val b = vars(c.nodeB)
                if (c.anchorA.axis == AnchorAxis.X)
                    solver.addConstraint(AnchorMath.xExpr(b, c.anchorB) with WeightedRelation.EQ(R) to (AnchorMath.xExpr(a, c.anchorA) + c.pixels))
                else
                    solver.addConstraint(AnchorMath.yExpr(b, c.anchorB) with WeightedRelation.EQ(R) to (AnchorMath.yExpr(a, c.anchorA) + c.pixels))
            }
            is SceneConstraint.Connect -> {
                val a = vars(c.nodeA); val b = vars(c.nodeB)
                solver.addConstraint(AnchorMath.xExpr(a, c.anchorA) with WeightedRelation.EQ(R) to AnchorMath.xExpr(b, c.anchorB))
                solver.addConstraint(AnchorMath.yExpr(a, c.anchorA) with WeightedRelation.EQ(R) to AnchorMath.yExpr(b, c.anchorB))
            }
            is SceneConstraint.Contain -> {
                // Canvas bounds as REQUIRED inequalities (spec 2026-08-17): they only bite when
                // something would push the box outside, so they compose with the REQUIRED
                // equalities instead of fighting them. STRONG size stays (struts) yield here —
                // an oversized strut compresses rather than failing the solve.
                val v = vars(c.nodeId)
                val minX = c.margin * canvas.width
                val maxX = (1.0 - c.margin) * canvas.width
                val minY = c.margin * canvas.height
                val maxY = (1.0 - c.margin) * canvas.height
                solver.addConstraint(v.x with WeightedRelation.GE(R) to minX)
                solver.addConstraint((v.x + v.width) with WeightedRelation.LE(R) to maxX)
                solver.addConstraint(v.y with WeightedRelation.GE(R) to minY)
                solver.addConstraint((v.y + v.height) with WeightedRelation.LE(R) to maxY)
            }
            is SceneConstraint.RelativeSize -> {
                // Uniform scale, both axes: widthA == ratio * widthB AND heightA == ratio * heightB.
                // Only the SOURCE's size stay demotes to STRONG (see sizeStay) — the TARGET is the
                // REQUIRED reference, so the ratio scales the source onto the target.
                val a = vars(c.nodeA); val b = vars(c.nodeB)
                solver.addConstraint(a.width with WeightedRelation.EQ(R) to b.width * c.ratio)
                solver.addConstraint(a.height with WeightedRelation.EQ(R) to b.height * c.ratio)
            }
            is SceneConstraint.Distribute -> {
                // Equal consecutive gaps along the axis: gap(i) == gap(i+1) for each consecutive
                // triple, gap(i) = left(i+1) - right(i). kasuari minus operators verified
                // (Variable.minus(Variable/Expression)) so the gaps read directly.
                val vs = c.nodeIds.map { vars(it) }
                fun left(v: NodeVars) = if (c.axis == AnchorAxis.X) v.x else v.y
                fun right(v: NodeVars) = if (c.axis == AnchorAxis.X) v.x + v.width else v.y + v.height
                for (i in 0 until vs.size - 2) {
                    solver.addConstraint((left(vs[i + 1]) - right(vs[i])) with WeightedRelation.EQ(R) to (left(vs[i + 2]) - right(vs[i + 1])))
                }
            }
        }
    }
}
