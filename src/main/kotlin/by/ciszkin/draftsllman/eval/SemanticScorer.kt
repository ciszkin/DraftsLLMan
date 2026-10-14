package by.ciszkin.draftsllman.eval

import by.ciszkin.draftsllman.scene.CanvasSpec
import kotlin.math.hypot

/**
 * The SEMANTIC scorer — the axis the decision gate should use (the reference layout is ONE arbitrary
 * realization of the sketch's meaning: a recovered equivalent structure at shifted positions/sizes is
 * a semantic success, not a miss).
 *
 * Matching ignores the IoU gate entirely: a predicted component matches a truth component iff ROLE
 * and KIND agree. Boxes are used ONLY as a tie-breaker (assign same-role candidates to the nearest
 * center — cheap, deterministic disambiguation), not as an acceptance test.
 *
 * [relationConsistency] additionally checks coarse RELATIVE structure over the matched pairs
 * (component A left-of/above B must hold in both diagrams) — this is what makes "left-sidebar vs
 * right-sidebar" a semantic difference without demanding pixel equality. Together: component
 * semantics + relation semantics = "same diagram".
 */
object SemanticScorer {

    /** Same shape as [T3Geometric.MatchResult] but produced by semantic matching. */
    data class SemanticMatch(
        val tp: Int,
        val fp: Int,
        val fn: Int,
        val pairs: List<Pair<Int, Int>>,
    )

    /**
     * Bipartite assignment maximizing the number of matches where role+kind agree; among
     * maximum-cardinality matchings, minimize the normalized center distance (tie-break only).
     */
    fun match(pred: List<EvComponent>, truth: List<EvComponent>): SemanticMatch {
        val n = pred.size
        val m = truth.size
        val s = 0
        val predStart = 1
        val truthStart = predStart + n
        val t = truthStart + m
        val numNodes = t + 1

        data class Edge(val to: Int, var cap: Long, val cost: Double, val rev: Int)
        val g = Array(numNodes) { mutableListOf<Edge>() }
        fun addEdge(u: Int, v: Int, cap: Long, cost: Double) {
            g[u].add(Edge(v, cap, cost, g[v].size))
            g[v].add(Edge(u, 0, -cost, g[u].size - 1))
        }
        for (i in 0 until n) addEdge(s, predStart + i, 1, 0.0)
        for (i in 0 until n) {
            for (j in 0 until m) {
                val p = pred[i]
                val gv = truth[j]
                if (p.role == gv.role && p.kind == gv.kind) {
                    // cost in [0, 1): normalized center distance — pure tie-break, never a gate.
                    val dx = p.box.cx - gv.box.cx
                    val dy = p.box.cy - gv.box.cy
                    val dist = hypot(dx, dy) / 1000.0 // small enough to never flip a maximum-cardinality answer
                    addEdge(predStart + i, truthStart + j, 1, dist)
                }
            }
        }
        for (j in 0 until m) addEdge(truthStart + j, t, 1, 0.0)

        val inf = Double.POSITIVE_INFINITY
        var tp = 0
        while (true) {
            val dist = DoubleArray(numNodes) { inf }
            val inQueue = BooleanArray(numNodes)
            val prevNode = IntArray(numNodes) { -1 }
            val prevEdge = IntArray(numNodes) { -1 }
            dist[s] = 0.0
            val queue = ArrayDeque<Int>()
            queue.addLast(s)
            inQueue[s] = true
            while (queue.isNotEmpty()) {
                val u = queue.removeFirst()
                inQueue[u] = false
                for ((ei, e) in g[u].withIndex()) {
                    if (e.cap > 0 && dist[u] + e.cost < dist[e.to] - 1e-12) {
                        dist[e.to] = dist[u] + e.cost
                        prevNode[e.to] = u
                        prevEdge[e.to] = ei
                        if (!inQueue[e.to]) { inQueue[e.to] = true; queue.addLast(e.to) }
                    }
                }
            }
            if (dist[t] == inf) break
            var v = t
            while (v != s) {
                val u = prevNode[v]
                val ei = prevEdge[v]
                val e = g[u][ei]
                e.cap -= 1
                g[v][e.rev].cap += 1
                v = u
            }
            tp++
        }
        val pairs = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until n) {
            for (e in g[predStart + i]) {
                if (e.to in truthStart until truthStart + m && e.cap == 0L) {
                    pairs.add(i to (e.to - truthStart))
                }
            }
        }
        return SemanticMatch(tp = tp, fp = n - tp, fn = m - tp, pairs = pairs)
    }

    fun f1(m: SemanticMatch): Double {
        if (m.tp == 0) return 0.0
        val precision = m.tp.toDouble() / (m.tp + m.fp)
        val recall = m.tp.toDouble() / (m.tp + m.fn)
        return if (precision + recall == 0.0) 0.0 else 2.0 * precision * recall / (precision + recall)
    }

    /**
     * Fraction of MATCHED component pairs whose coarse relative layout agrees between the two
     * diagrams: for every pair (a, b) of matches, component a being left-of / above b in the truth
     * must hold in the prediction (equality tolerates equal centers). 1.0 = same diagram structure.
     */
    fun relationConsistency(pred: List<EvComponent>, truth: List<EvComponent>, m: SemanticMatch): Double {
        if (m.pairs.size < 2) return 0.0
        var agree = 0
        var total = 0
        for (i in 0 until m.pairs.size) {
            val (pa, ta) = m.pairs[i]
            for (k in i + 1 until m.pairs.size) {
                val (pb, tb) = m.pairs[k]
                val pxAgree = (pred[pa].box.cx < pred[pb].box.cx) == (truth[ta].box.cx < truth[tb].box.cx)
                val pyAgree = (pred[pa].box.cy < pred[pb].box.cy) == (truth[ta].box.cy < truth[tb].box.cy)
                if (pxAgree && pyAgree) agree++
                total++
            }
        }
        return if (total == 0) 0.0 else agree.toDouble() / total
    }
}