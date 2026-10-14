package by.ciszkin.draftsllman.engine

import by.ciszkin.draftsllman.scene.GroupNode
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.solver.ConstraintSolver
import by.ciszkin.draftsllman.solver.SolveResult

class DraftsEngine(var current: SceneGraph = SceneGraph.EMPTY) {
    fun snapshot(): EngineMemento = EngineMemento(current)
    fun restore(m: EngineMemento) { current = m.graph }
    fun commit(graph: SceneGraph) { current = graph }

    /** Solve the candidate, bake leaf boxes from the solver, then bake group boxes as child unions. */
    fun solveInto(graph: SceneGraph, pinSizes: Boolean = false): Pair<SceneGraph, SolveResult> =
        when (val r = ConstraintSolver.solve(graph, pinSizes)) {
            is SolveResult.Unsatisfiable -> graph to r
            is SolveResult.Success -> {
                var baked = graph.copy(nodes = graph.nodes.mapValues { (id, node) ->
                    if (node is GroupNode) node else r.boxes[id]?.let { node.withBox(it) } ?: node
                })
                baked = baked.copy(nodes = baked.nodes.mapValues { (id, node) ->
                    if (node is GroupNode) baked.groupBox(id)?.let { node.withBox(it) } ?: node else node
                })
                baked to r
            }
        }
}

data class EngineMemento(val graph: SceneGraph)
