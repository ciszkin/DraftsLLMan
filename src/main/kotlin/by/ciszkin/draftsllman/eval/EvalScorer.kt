package by.ciszkin.draftsllman.eval

import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.ResolvedBox
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * EvalScorer: the T0 (validity) + T3 (geometric fidelity) scoring core for the M1 eval-fixture.
 *
 * All scoring/matching LOGIC lives here (the [EvalModel] types stay a dumb data contract). The two
 * objects expose the EXACT signatures the integration layer depends on: [T0Validity] needs no ground
 * truth (well-formedness + in-canvas invariants), [T3Geometric] scores predicted components against
 * ground truth via axis-aligned IoU + role-gated bipartite matching.
 */

// ------------------------------------------------------------------------------------------------
// T0: validity (no ground truth needed)
// ------------------------------------------------------------------------------------------------

object T0Validity {

    private val TAGS: Set<String> = setOf("circle", "rect", "line", "polygon", "path", "svg")

    /**
     * Crude but deterministic well-formedness check: scan the SVG string, tracking nesting depth for
     * the small tag set {circle, rect, line, polygon, path, svg}. Self-closing tags (`<tag/>`) net
     * zero depth. Returns true iff depth never goes negative and all opened tags are closed (final
     * depth 0). Element-order / attribute validity are deliberately **not** checked.
     */
    fun svgWellFormed(svg: String): Boolean {
        var depth = 0
        var i = 0
        while (i < svg.length) {
            val lt = svg.indexOf('<', i)
            if (lt < 0) break
            val closing = lt + 1 < svg.length && svg[lt + 1] == '/'
            val nameStart = if (closing) lt + 2 else lt + 1
            var nameEnd = nameStart
            while (nameEnd < svg.length &&
                (svg[nameEnd].isLetter() || svg[nameEnd] == '-' || svg[nameEnd] == '_' || svg[nameEnd] == ':')
            ) {
                nameEnd++
            }
            val name = svg.substring(nameStart, nameEnd)
            if (name in TAGS) {
                if (closing) {
                    depth--
                    if (depth < 0) return false
                } else {
                    // Detect self-closing by scanning to the '>' (honouring quoted attributes),
                    // then checking the char immediately before it.
                    var selfClosing = false
                    var j = nameEnd
                    var inQuote = false
                    while (j < svg.length) {
                        val c = svg[j]
                        when {
                            c == '"' -> inQuote = !inQuote
                            c == '>' && !inQuote -> {
                                if (j > nameEnd && svg[j - 1] == '/') selfClosing = true
                                break
                            }
                        }
                        j++
                    }
                    if (!selfClosing) depth++
                }
            }
            val gt = svg.indexOf('>', lt)
            i = if (gt < 0) lt + 1 else gt + 1
        }
        return depth == 0
    }

    /**
     * Invariant checks for one prediction: every component box must lie inside the canvas
     * (left/top >= 0 and right/bottom <= width/height) and have non-negative width/height.
     * Returns a list of human-readable violation strings — empty means valid.
     */
    fun predictionInvariants(p: Prediction, canvas: CanvasSpec): List<String> {
        val violations = mutableListOf<String>()
        for (c in p.components) {
            val b = c.box
            val name = c.id
            if (b.x < 0) violations += "$name: left (${b.x}) is negative"
            if (b.y < 0) violations += "$name: top (${b.y}) is negative"
            if (b.width < 0) violations += "$name: width (${b.width}) is negative"
            if (b.height < 0) violations += "$name: height (${b.height}) is negative"
            if (b.right > canvas.width) {
                violations += "$name: right (${b.right}) exceeds canvas width (${canvas.width})"
            }
            if (b.bottom > canvas.height) {
                violations += "$name: bottom (${b.bottom}) exceeds canvas height (${canvas.height})"
            }
        }
        return violations
    }
}

// ------------------------------------------------------------------------------------------------
// T3: geometric fidelity
// ------------------------------------------------------------------------------------------------

/** Result of [T3Geometric.match]: true positives, false positives, false negatives and matched pairs. */
data class MatchResult(
    val tp: Int,
    val fp: Int,
    val fn: Int,
    val pairs: List<Pair<Int, Int>>,
)

object T3Geometric {

    /**
     * Standard axis-aligned intersection-over-union. Degenerate (zero/negative intersection) clamps
     * to 0.0; the returned value is never negative.
     */
    fun iou(a: ResolvedBox, b: ResolvedBox): Double {
        val interLeft = max(a.left, b.left)
        val interRight = min(a.right, b.right)
        val interTop = max(a.top, b.top)
        val interBottom = min(a.bottom, b.bottom)
        val iw = interRight - interLeft
        val ih = interBottom - interTop
        val interArea = if (iw <= 0 || ih <= 0) 0.0 else iw * ih
        val union = a.width * a.height + b.width * b.height - interArea
        if (union <= 0.0) return 0.0
        val result = interArea / union
        return if (result < 0.0) 0.0 else result
    }

    /**
     * IoU that stays meaningful for degenerate (near-zero-area) boxes — the boxes a `LineNode`
     * derives from its endpoints (height or width 0). Each box is first given a nominal 1px
     * thickness per degenerate axis (the ink a real sketch line would occupy), so an identical
     * overwritten line scores ~1.0 and a separated line scores 0 — pure IoU would deny matching
     * thin primitives forever (IoU=0 even against themselves). Non-degenerate boxes pass through
     * unchanged (their extents are already >= 1px), so this is a strict extension of [iou].
     */
    fun effectiveIoU(a: ResolvedBox, b: ResolvedBox): Double {
        fun thick(box: ResolvedBox) =
            ResolvedBox(box.x, box.y, box.width.coerceAtLeast(1.0), box.height.coerceAtLeast(1.0))
        return iou(thick(a), thick(b))
    }

    /**
     * Bipartite assignment maximizing the number of matches where `role(pred) == role(truth)` AND
     * `iou >= iouTau`. Among maximum-cardinality matchings the total `1.0 - iou` cost is minimized.
     * Ineligible pairs are excluded entirely. `fp = pred.size - tp`, `fn = truth.size - tp`.
     *
     * Implemented as min-cost max-flow (successive shortest augmenting paths), so the returned
     * pairing is the globally best one — not a greedy approximation.
     */
    fun match(pred: List<EvComponent>, truth: List<EvComponent>, iouTau: Double = 0.5): MatchResult {
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
                val pu = pred[i]
                val tu = truth[j]
                val iouVal = effectiveIoU(pu.box, tu.box)
                val eligible = pu.role == tu.role && iouVal >= iouTau
                if (eligible) {
                    addEdge(predStart + i, truthStart + j, 1, 1.0 - iouVal)
                }
            }
        }
        for (j in 0 until m) addEdge(truthStart + j, t, 1, 0.0)

        val inf = Double.POSITIVE_INFINITY
        var tp = 0
        while (true) {
            // Shortest path s -> t (SPFA; sizes are tiny, costs are non-negative).
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
                        if (!inQueue[e.to]) {
                            inQueue[e.to] = true
                            queue.addLast(e.to)
                        }
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
        return MatchResult(tp = tp, fp = n - tp, fn = m - tp, pairs = pairs)
    }

    /** 2*P*R/(P+R); 0 when tp == 0. */
    fun f1(m: MatchResult): Double {
        if (m.tp == 0) return 0.0
        val precision = m.tp.toDouble() / (m.tp + m.fp)
        val recall = m.tp.toDouble() / (m.tp + m.fn)
        val denom = precision + recall
        return if (denom == 0.0) 0.0 else 2.0 * precision * recall / denom
    }

    /** kind x kind confusion over the SAME best-match pairing produced by [match]. */
    data class ConfusionMatrix(
        val counts: Array<Array<Int>>,
        val totalPredAnnotations: Int = 0,
    ) {
        /** Trace of the matrix / total matched (tp). */
        fun primitiveAccuracy(): Double {
            val n = PrimitiveKind.entries.size
            var trace = 0
            var total = 0
            for (i in 0 until n) {
                trace += counts[i][i]
                for (j in 0 until n) total += counts[i][j]
            }
            return if (total == 0) 0.0 else trace.toDouble() / total
        }

        /** Correctly-matched-annotation-as-annotation / total predicted annotations. */
        fun annotationPrecision(): Double {
            val correctlyMatched =
                counts[PrimitiveKind.ANNOTATION.ordinal][PrimitiveKind.ANNOTATION.ordinal]
            return if (totalPredAnnotations == 0) 0.0
            else correctlyMatched.toDouble() / totalPredAnnotations
        }
    }

    /** Kind x kind confusion over the best [match] pairing: `confusion[truthKind][predKind]`. */
    fun confusion(pred: List<EvComponent>, truth: List<EvComponent>): ConfusionMatrix {
        val n = PrimitiveKind.entries.size
        val counts = Array(n) { Array(n) { 0 } }
        val m = match(pred, truth)
        for ((pi, ti) in m.pairs) {
            val tKind = truth[ti].kind.ordinal
            val pKind = pred[pi].kind.ordinal
            counts[tKind][pKind]++
        }
        val totalPredAnnotations = pred.count { it.kind == PrimitiveKind.ANNOTATION }
        return ConfusionMatrix(counts, totalPredAnnotations)
    }

    /** Euclidean distance between box centers / canvas diagonal (normalized, so 0..~1). */
    fun centerError(a: ResolvedBox, b: ResolvedBox, canvas: CanvasSpec): Double {
        val dx = a.cx - b.cx
        val dy = a.cy - b.cy
        val diagonal = hypot(canvas.width, canvas.height)
        if (diagonal <= 0.0) return 0.0
        return hypot(dx, dy) / diagonal
    }
}
