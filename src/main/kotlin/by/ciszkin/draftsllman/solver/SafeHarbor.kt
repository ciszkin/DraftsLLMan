package by.ciszkin.draftsllman.solver

import by.ciszkin.draftsllman.architect.EmitSkeleton
import by.ciszkin.draftsllman.architect.SkeletonBuilder
import by.ciszkin.draftsllman.architect.SkeletonConstraint
import by.ciszkin.draftsllman.engine.DraftsEngine
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.GroupNode
import by.ciszkin.draftsllman.scene.LineNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import kotlin.math.hypot

/** Which [SafeHarbor.connectHonoringSpread] tier produced a result, for honest tracing. */
enum class SafeHarborTier(val label: String) {
    CONNECT_TREE("connect-honoring (spanning-tree solve)"),
    CONNECT_SEPARATION("connect-honoring (tree + sibling separation)"),
    ROW_SPREAD("row-spread fallback (connect-honoring tiers exhausted)"),
}

/** A [SafeHarbor.connectHonoringSpread] outcome: the solved graph + which tier produced it. */
data class SafeHarborResult(val graph: SceneGraph, val tier: SafeHarborTier)

/** What [SafeHarbor.degeneracy] found: why the layout is degenerate and which components are involved. */
data class Degeneracy(val reason: String, val componentIds: List<String>)

/**
 * The Safe-Harbor — a model-independent safety net for the Zero-Coordinate anti-collapse work (Slice 6).
 *
 * Even with `distance_ratio` separation, a weak LLM may emit a skeleton the Cassowary solver either
 * cannot satisfy (over-constrained → [SolveResult.Unsatisfiable]) or solves to a degenerate layout
 * (components collapsed onto each other — e.g. two wheels whose `connect` constraints pin their centers
 * together). In either case the drawing would render as one unrecognizable blob. The Safe-Harbor replaces
 * that blob with a DETERMINISTIC non-overlapping row-assignment spread: each component lands in its own
 * canvas slot, preserving its declared size (clamped to fit). No solver changes, no LLM — pure geometry.
 *
 * The Coordinator applies it after the Phase-2 global solve whenever the solve is unsatisfiable or the
 * solved graph is degenerate. Two spreads are offered:
 *  - [connectHonoringSpread] (tried first) — keeps the `connect` topology so connected components stay
 *    attached (e.g. a bicycle frame still spans the wheels) instead of becoming a disconnected row.
 *  - [spread] (the fallback) — the deterministic non-overlapping row-assignment above (disconnected but
 *    guaranteed non-overlap; the safety net's safety net).
 */
object SafeHarbor {
    /**
     * True if the solved [graph] would render as a blob: any two distinct leaf nodes' centers are within
     * [eps] (Euclidean) of each other, OR any leaf node has collapsed to a point (BOTH width and height
     * < [eps]).
     *
     * The area check requires BOTH dimensions degenerate so a legitimate 1D component — a horizontal or
     * vertical line (one zero dimension) such as a bicycle frame — does NOT trip it; only a true point /
     * collapsed-2D node does. The pairwise-coincident-centers check is therefore the primary collapse
     * signal. Structural [GroupNode]s are excluded: their box is derived from children (not
     * solver-positioned) and an empty skeleton group has zero area, so including them would false-positive
     * on every group component — mirroring [ConstraintSolver]'s own `filterValues { it !is GroupNode }`.
     * Returns false for fewer than two leaves (nothing to collapse onto).
     */
    fun isDegenerate(graph: SceneGraph, eps: Double = 1.0): Boolean = degeneracy(graph, eps) != null

    /**
     * The richer form of [isDegenerate]: reports the first degeneracy found instead of a bare boolean,
     * so the Architect's revise-loop diagnostic can name the offending components. Semantics and check
     * order are identical to [isDegenerate]: a leaf collapsed to a point (BOTH width and height
     * < [eps]) first, then any two distinct leaves' centers within [eps] (Euclidean). Structural
     * [GroupNode]s are excluded (see [isDegenerate]). Null when the layout is fine.
     */
    fun degeneracy(graph: SceneGraph, eps: Double = 1.0): Degeneracy? {
        val leaves = graph.nodes.values.filter { it !is GroupNode }
        leaves.firstOrNull { it.box.width < eps && it.box.height < eps }?.let {
            return Degeneracy("collapsed to a point", listOf(it.id))
        }
        for (i in leaves.indices) {
            for (j in i + 1 until leaves.size) {
                val a = leaves[i].box
                val b = leaves[j].box
                if (hypot(a.cx - b.cx, a.cy - b.cy) < eps) {
                    return Degeneracy("coincident centers", listOf(leaves[i].id, leaves[j].id))
                }
            }
        }
        return null
    }

    /**
     * Deterministic non-overlapping layout. Builds the skeleton's nodes (declared sizes) via
     * [SkeletonBuilder.build], then repositions each component into a distinct horizontal slot:
     *   `cx_i = (i + 1) * canvas.width / (N + 1)`, `cy = canvas.height / 2`,
     * components taken in id-sorted order (stable regardless of LLM emission order), so adjacent slots
     * never overlap. The graph is NOT re-solved: positions are baked directly.
     *
     * Per node type (`SkeletonBuilder` emits only Circle/Rect/Line/Group; Group is structural):
     *  - **Circle / Rect** — resized to a clamped box via [SceneNode.withBox], which sets the box directly.
     *    Oversized nodes (max dimension exceeding `0.8 * canvas.width / (N + 1)`) are scaled proportionally
     *    (aspect preserved); non-oversized nodes keep their declared size. The clamped box is centered on
     *    the slot, so the node's center lands at `(cx_i, cy)`.
     *  - **Line** — handled separately by CHOICE, not necessity: `LineNode.withBox` box-scales into the
     *    target box, but the spread wants a LENGTH clamp — a diagonal line's length is not its box
     *    diagonal, so box-scaling would change the clamped result. The line's LENGTH is clamped to
     *    `maxDim` PRESERVING its orientation/angle (scaled about its center via the unit vector), then it
     *    is re-centered at `(cx_i, cy)` by rebuilding `start`/`end`. Non-oversized lines keep their length.
     */
    fun spread(skeleton: EmitSkeleton, canvas: CanvasSpec): SceneGraph {
        val base = SkeletonBuilder.build(skeleton, canvas)
        val order = skeleton.components.sortedBy { it.id }
        val n = order.size
        if (n == 0) return base
        val maxDim = 0.8 * canvas.width / (n + 1)
        val slotCy = canvas.height / 2.0
        val positioned = base.nodes.toMutableMap()
        for ((i, comp) in order.withIndex()) {
            val node = base.nodes[comp.id] ?: continue
            val slotCx = (i + 1) * canvas.width / (n + 1)
            positioned[comp.id] = when (node) {
                is LineNode -> clampAndCenterLine(node, slotCx, slotCy, maxDim)
                else -> {
                    val box = node.box
                    val (w, h) = if (maxOf(box.width, box.height) > maxDim) {
                        val scale = maxDim / maxOf(box.width, box.height)
                        box.width * scale to box.height * scale
                    } else {
                        box.width to box.height
                    }
                    node.withBox(ResolvedBox(slotCx - w / 2.0, slotCy - h / 2.0, w, h))
                }
            }
        }
        return base.copy(nodes = positioned)
    }

    /**
     * Connect-honoring spread (Slice 6 refinement): like [spread] but tries to KEEP the skeleton's
     * `connect` topology so connected components stay attached (e.g. a bicycle frame still spans its
     * wheels) instead of becoming a disconnected row of shapes. Tried BEFORE [spread] by the
     * Coordinator; [spread] remains the guaranteed non-overlapping fallback.
     *
     * Three tiers — first non-degenerate Success wins:
     *  1. **Spanning-tree solve (pinned sizes).** The over-constraint that triggered Safe-Harbor almost
     *     always came from a CYCLE among `connect`s; dropping back-edges leaves an acyclic tree that is
     *     guaranteed satisfiable. A spanning element (a frame/tube whose two anchors connect to
     *     different leaves) already separates its leaves via its pinned size, so this tier alone handles
     *     the common "over-constrained bicycle" case.
     *  2. **Tree + injected sibling separations (un-pinned sizes).** For each sibling group (components
     *     sharing a connect-hub) a `distance_ratio` is injected between consecutive siblings so they
     *     spread rather than stack. Sizes are left unpinned so a spanning element may STRETCH to the
     *     injected gap (a pinned size would re-create a REQUIRED-vs-REQUIRED conflict). This is the only
     *     tier where injection helps: when a zero/tiny spanning element collapsed its leaves.
     *  3. **[spread]** (deterministic disconnected row-spread) — the safety net's safety net.
     *
     * Every tier KEEPS the skeleton's original `distance_ratio` and `align` constraints verbatim — those
     * are the canvas anchors and size/alignment intent. Only the non-tree (back-edge) `connect`s are
     * dropped: the spanning tree removes the `connect` *cycles* (the likely over-constraint source) while
     * the anchors survive so the tree solve has something to pin against. Without them, disconnected
     * components (positioned only by `distance_ratio`/`align`, e.g. two wheels whose wheelbase is a
     * `distance_ratio`) float to the origin and collapse → `isDegenerate` → row-spread.
     *
     * The tree is built by BFS starting from the HIGHEST-DEGREE component (the likely hub), ties broken
     * by id, so the hub's structural edges become tree edges and only cycle-closing (extra/conflicting)
     * edges are dropped as back-edges.
     *
     * Returns a [SafeHarborResult] whose [SafeHarborTier] names the producing tier, so the Coordinator
     * trace line honestly distinguishes a connect-honoring success from a row-spread fallback.
     */
    fun connectHonoringSpread(skeleton: EmitSkeleton, canvas: CanvasSpec): SafeHarborResult {
        tryConnectSolve(skeleton, canvas, injectSeparations = false, pinSizes = true)?.let {
            return SafeHarborResult(it, SafeHarborTier.CONNECT_TREE)
        }
        tryConnectSolve(skeleton, canvas, injectSeparations = true, pinSizes = false)?.let {
            return SafeHarborResult(it, SafeHarborTier.CONNECT_SEPARATION)
        }
        return SafeHarborResult(spread(skeleton, canvas), SafeHarborTier.ROW_SPREAD)
    }

    /** One connect-honoring solve attempt; null if it cannot produce a non-degenerate layout. */
    private fun tryConnectSolve(
        skeleton: EmitSkeleton, canvas: CanvasSpec, injectSeparations: Boolean, pinSizes: Boolean,
    ): SceneGraph? {
        val componentIds = skeleton.components.map { it.id }.toSet()
        val connects = skeleton.constraints.filter { it.kind == "connect" }
        if (connects.isEmpty()) return null
        val tree = spanningTreeConnects(connects, componentIds)
        if (tree.isEmpty()) return null
        val separations = if (injectSeparations) siblingSeparations(tree) else emptyList()
        // Modified skeleton: tree connects (back-edges dropped) + ALL original distance_ratio/align
        // (the canvas anchors — kept verbatim) + any injected sibling separations. Dropping the
        // non-tree connects removes the connect cycles; the anchors survive so the solve is pinned.
        val anchors = skeleton.constraints.filter { it.kind != "connect" }
        val modified = EmitSkeleton(components = skeleton.components, constraints = tree + anchors + separations)
        return try {
            val graph = SkeletonBuilder.build(modified, canvas)
            val (solved, result) = DraftsEngine().solveInto(graph, pinSizes = pinSizes)
            if (result is SolveResult.Success && !isDegenerate(solved)) solved else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The `connect` constraints whose endpoint-pair lies on a BFS spanning tree of the connect graph.
     * Back-edges (cycle closers) are dropped. BFS starts from the highest-degree component (the hub)
     * so the hub's structural edges are kept and the extra/conflicting edges become back-edges.
     * Multi-connects between the same pair are all kept together (one graph edge).
     */
    private fun spanningTreeConnects(
        connects: List<SkeletonConstraint>, componentIds: Set<String>,
    ): List<SkeletonConstraint> {
        val degree = mutableMapOf<String, Int>()
        val adj = mutableMapOf<String, MutableList<String>>()
        for (c in connects) {
            val a = compId(c.source); val b = compId(c.target)
            if (a !in componentIds || b !in componentIds || a == b) continue
            adj.getOrPut(a) { mutableListOf() }.add(b)
            adj.getOrPut(b) { mutableListOf() }.add(a)
            degree[a] = (degree[a] ?: 0) + 1
            degree[b] = (degree[b] ?: 0) + 1
        }
        val visited = mutableSetOf<String>()
        val treeEdgeKeys = mutableSetOf<String>()
        // Hub first: highest degree, then id for determinism. Isolated nodes are harmless here.
        val starts = componentIds.sortedWith(
            compareByDescending<String> { degree[it] ?: 0 }.thenBy { it }
        )
        for (start in starts) {
            if (start in visited) continue
            visited += start
            val queue = ArrayDeque<String>().apply { addLast(start) }
            while (queue.isNotEmpty()) {
                val u = queue.removeFirst()
                for (v in adj[u].orEmpty().sorted()) {
                    if (v in visited) continue
                    visited += v
                    treeEdgeKeys += edgeKey(u, v)
                    queue.addLast(v)
                }
            }
        }
        return connects.filter { c ->
            val a = compId(c.source); val b = compId(c.target)
            a != b && a in componentIds && b in componentIds && edgeKey(a, b) in treeEdgeKeys
        }
    }

    /**
     * Injected `distance_ratio` separations between consecutive siblings (id-sorted) of each connect-hub.
     * A "sibling group" = the neighbors of a hub component that has ≥2 of them. Pairs get a 0.6 canvas
     * gap; larger groups spread across 80% of the canvas (`0.8 / (size-1)` per gap). X-axis / canvas_width
     * (most drawings spread horizontally). Only reached in tier 2 (un-pinned), so it never conflicts with
     * a pinned spanning size; inherently-stacked groups fall through to [spread].
     */
    private fun siblingSeparations(tree: List<SkeletonConstraint>): List<SkeletonConstraint> {
        val neighbors = mutableMapOf<String, MutableSet<String>>()
        for (c in tree) {
            val a = compId(c.source); val b = compId(c.target)
            neighbors.getOrPut(a) { mutableSetOf() }.add(b)
            neighbors.getOrPut(b) { mutableSetOf() }.add(a)
        }
        val separations = mutableListOf<SkeletonConstraint>()
        var n = 0
        for ((_, group) in neighbors) {
            val siblings = group.sorted()
            if (siblings.size < 2) continue
            val gap = if (siblings.size <= 2) 0.6 else 0.8 / (siblings.size - 1)
            for (i in 0 until siblings.size - 1) {
                separations += SkeletonConstraint(
                    id = "harbor_sep_$n",
                    kind = "distance_ratio",
                    source = "${siblings[i]}:CENTER_X",
                    target = "${siblings[i + 1]}:CENTER_X",
                    ratio = gap,
                    relativeTo = "canvas_width",
                )
                n += 1
            }
        }
        return separations
    }

    /** Extracts the component id from a "componentId:anchorName" reference. */
    private fun compId(ref: String): String = ref.substringBefore(':')

    /** Deterministic undirected edge key (id-sorted pair joined by '|'). */
    private fun edgeKey(a: String, b: String): String = if (a <= b) "$a|$b" else "$b|$a"

    /**
     * Clamps [node]'s length to [maxDim] preserving its orientation, then re-centers it at
     * `(slotCx, slotCy)` by rebuilding `start`/`end`. A zero-length line (point) is just moved to the slot.
     * Used instead of [LineNode.withBox] by choice: `withBox` box-scales (a diagonal's length is not
     * its box diagonal), while this clamps LENGTH — the spread's intended semantics.
     */
    private fun clampAndCenterLine(node: LineNode, slotCx: Double, slotCy: Double, maxDim: Double): LineNode {
        val (sx, sy) = node.start
        val (ex, ey) = node.end
        val dx = ex - sx
        val dy = ey - sy
        val len = hypot(dx, dy)
        if (len < 1e-9) return node.copy(start = slotCx to slotCy, end = slotCx to slotCy)
        val unitX = dx / len
        val unitY = dy / len
        val half = minOf(len, maxDim) / 2.0
        val newStart = (slotCx - unitX * half) to (slotCy - unitY * half)
        val newEnd = (slotCx + unitX * half) to (slotCy + unitY * half)
        return node.copy(start = newStart, end = newEnd)
    }
}
