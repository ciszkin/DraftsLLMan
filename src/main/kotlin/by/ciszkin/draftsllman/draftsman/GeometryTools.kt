package by.ciszkin.draftsllman.draftsman

import by.ciszkin.draftsllman.contract.ToolArgs.AddNodesArgs
import by.ciszkin.draftsllman.geometry.GeometryEngine
import by.ciszkin.draftsllman.geometry.GeometryEngine.ComputedNode
import by.ciszkin.draftsllman.geometry.GeometryEngine.NodeType
import by.ciszkin.draftsllman.scene.Anchor
import by.ciszkin.draftsllman.scene.BezierPath
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.CurveNode
import by.ciszkin.draftsllman.scene.LineNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.SceneNode
import by.ciszkin.draftsllman.scene.ShapeNode
import by.ciszkin.draftsllman.scene.Style
import by.ciszkin.draftsllman.scene.computeQuadraticControl
import by.ciszkin.draftsllman.solver.resolveAnchorName
import kotlin.math.cos
import kotlin.math.sin

/**
 * Stateless dispatch glue between the `add_nodes` tool contract ([AddNodesArgs]) and the pure-math
 * [GeometryEngine]. For each call it:
 *   1. Reads the target node's SOLVED absolute [ResolvedBox] from the [SceneGraph].
 *   2. Resolves the LLM's anchor NAMES to concrete [Anchor]s (via [resolveAnchorName], so declared
 *      named aliases like "hub" work as well as geometric names like "CENTER") and then to absolute
 *      (x, y) points on the target box.
 *   3. Calls the matching [GeometryEngine] preset generator (single_node is built inline — the engine
 *      exposes no such generator) to get deterministic [ComputedNode] positions.
 *   4. Converts each [ComputedNode] into a concrete [SceneNode] ([CircleNode]/[LineNode]/
 *      [CurveNode]/[ShapeNode]).
 *
 * This object does NOT mutate the graph — it returns the fresh nodes; the tool layer (Task 5) adds
 * them. It assumes [AddNodesArgs.semanticValidate] already accepted the args.
 */
object GeometryTools {

    fun execute(args: AddNodesArgs, graph: SceneGraph): List<SceneNode> {
        val target = graph.nodes[args.targetId]
            ?: error("add_nodes: unknown target '${args.targetId}'")
        val box = target.box
        val nodeType = mapNodeType(args.nodeType)
        val aliases = graph.anchorAliases

        val computed: List<ComputedNode> = when (args.preset) {
            "single_node" -> {
                val anchor = resolveAnchorName(args.centerAnchor, args.targetId, aliases)
                val (x, y) = anchorToPosition(anchor, box)
                val size = maxOf(box.width, box.height) * args.sizeRatio
                listOf(
                    ComputedNode(
                        id = "${args.targetId}_${args.nodeName}_0",
                        role = args.nodeName,
                        type = nodeType,
                        position = Pair(x, y),
                        // Set size uniformly so computedToSceneNode reads it directly for every type
                        // (no duplicated default-length recompute on the single_node path).
                        size = Pair(size, size),
                    )
                )
            }

            "radial_pattern" -> {
                val count = args.count ?: error("add_nodes: radial_pattern requires count")
                val centerAnchor = resolveAnchorName(args.centerAnchor, args.targetId, aliases)
                // boundary_anchor is required by semanticValidate; defend defensively in case this is
                // ever called with an unvalidated args (never default silently — that hides mistakes).
                val boundaryAnchor = resolveAnchorName(
                    args.boundaryAnchor ?: error("add_nodes: radial_pattern requires boundary_anchor"),
                    args.targetId, aliases,
                )
                GeometryEngine.generateRadialPattern(
                    targetId = args.targetId,
                    nodeName = args.nodeName,
                    nodeType = nodeType,
                    centerAnchor = centerAnchor,
                    boundaryAnchor = boundaryAnchor,
                    count = count,
                    displacement = args.displacement,
                    sizeRatio = args.sizeRatio,
                    centerPoint = anchorToPosition(centerAnchor, box),
                    boundaryPoint = anchorToPosition(boundaryAnchor, box),
                )
            }

            "linear_pattern" -> {
                val count = args.count ?: error("add_nodes: linear_pattern requires count")
                val startAnchor = resolveAnchorName(args.startAnchor ?: error("add_nodes: linear_pattern requires start_anchor"), args.targetId, aliases)
                val endAnchor = resolveAnchorName(args.endAnchor ?: error("add_nodes: linear_pattern requires end_anchor"), args.targetId, aliases)
                GeometryEngine.generateLinearPattern(
                    targetId = args.targetId,
                    nodeName = args.nodeName,
                    nodeType = nodeType,
                    startAnchor = startAnchor,
                    endAnchor = endAnchor,
                    count = count,
                    displacement = args.displacement,
                    sizeRatio = args.sizeRatio,
                    startPoint = anchorToPosition(startAnchor, box),
                    endPoint = anchorToPosition(endAnchor, box),
                )
            }

            "grid_pattern" -> {
                val rows = args.rows ?: error("add_nodes: grid_pattern requires rows")
                val cols = args.cols ?: error("add_nodes: grid_pattern requires cols")
                val topLeftAnchor = resolveAnchorName(args.topLeftAnchor ?: error("add_nodes: grid_pattern requires top_left_anchor"), args.targetId, aliases)
                val bottomRightAnchor = resolveAnchorName(args.bottomRightAnchor ?: error("add_nodes: grid_pattern requires bottom_right_anchor"), args.targetId, aliases)
                GeometryEngine.generateGridPattern(
                    targetId = args.targetId,
                    nodeName = args.nodeName,
                    nodeType = nodeType,
                    topLeftAnchor = topLeftAnchor,
                    bottomRightAnchor = bottomRightAnchor,
                    rows = rows,
                    cols = cols,
                    sizeRatio = args.sizeRatio,
                    topLeftPoint = anchorToPosition(topLeftAnchor, box),
                    bottomRightPoint = anchorToPosition(bottomRightAnchor, box),
                )
            }

            "boundary_pattern" -> {
                val count = args.count ?: error("add_nodes: boundary_pattern requires count")
                val centerAnchor = resolveAnchorName(args.centerAnchor, args.targetId, aliases)
                val centerPoint = anchorToPosition(centerAnchor, box)
                val radius = minOf(box.width, box.height) / 2.0
                GeometryEngine.generateBoundaryPattern(
                    targetId = args.targetId,
                    nodeName = args.nodeName,
                    nodeType = nodeType,
                    count = count,
                    sizeRatio = args.sizeRatio,
                    centerPoint = centerPoint,
                    radius = radius,
                )
            }

            else -> error("add_nodes: unknown preset '${args.preset}'")
        }

        return computed.map { computedToSceneNode(it, args, box) }
    }

    /** Maps the LLM-facing node_type string to the type-safe [NodeType]. */
    private fun mapNodeType(s: String): NodeType = when (s) {
        "point" -> NodeType.Point
        "line" -> NodeType.Line
        "curve" -> NodeType.Curve
        "shape" -> NodeType.Shape
        else -> error("add_nodes: unknown node_type '$s'")
    }

    /**
     * Resolves a geometric [Anchor] to an absolute (x, y) on [box]. Covers every [Anchor] value:
     * POINT-axis anchors map to their exact corner/edge point; the pure X/Y-axis anchors (LEFT,
     * RIGHT, CENTER_X, TOP, BOTTOM, CENTER_Y) take their fixed coordinate on their own axis and the
     * box center on the other, so they still yield a usable point for detail-node placement.
     */
    private fun anchorToPosition(anchor: Anchor, box: ResolvedBox): Pair<Double, Double> = when (anchor) {
        Anchor.LEFT         -> Pair(box.left, box.cy)
        Anchor.RIGHT        -> Pair(box.right, box.cy)
        Anchor.CENTER_X     -> Pair(box.cx, box.cy)
        Anchor.TOP          -> Pair(box.cx, box.top)
        Anchor.BOTTOM       -> Pair(box.cx, box.bottom)
        Anchor.CENTER_Y     -> Pair(box.cx, box.cy)
        Anchor.TOP_LEFT     -> Pair(box.left, box.top)
        Anchor.TOP_RIGHT    -> Pair(box.right, box.top)
        Anchor.BOTTOM_LEFT  -> Pair(box.left, box.bottom)
        Anchor.BOTTOM_RIGHT -> Pair(box.right, box.bottom)
        Anchor.CENTER       -> Pair(box.cx, box.cy)
        Anchor.TOP_CENTER   -> Pair(box.cx, box.top)
        Anchor.BOTTOM_CENTER-> Pair(box.cx, box.bottom)
        Anchor.LEFT_CENTER  -> Pair(box.left, box.cy)
        Anchor.RIGHT_CENTER -> Pair(box.right, box.cy)
    }

    /**
     * Converts one [ComputedNode] into a concrete [SceneNode], keyed on [NodeType].
     *
     * Length source for Line/Curve: [GeometryEngine] attaches `size` only for Point/Shape nodes
     * (it is `null` for Line/Curve). When it is null, a scale-relative default length is derived
     * from the target box and [AddNodesArgs.sizeRatio] — the same proportion the engine uses for
     * Point/Shape size — so detail lines/curves scale with their target rather than using a
     * hardcoded constant.
     */
    private fun computedToSceneNode(
        node: ComputedNode,
        args: AddNodesArgs,
        targetBox: ResolvedBox,
    ): SceneNode {
        val pos = node.position ?: error("add_nodes: computed node ${node.id} has no position")
        val defaultLen = maxOf(targetBox.width, targetBox.height) * args.sizeRatio
        return when (node.type) {
            NodeType.Point -> {
                val s = node.size?.first ?: defaultLen
                val b = ResolvedBox(pos.first - s / 2.0, pos.second - s / 2.0, s, s)
                CircleNode(node.id, node.role, b, Style())
            }

            NodeType.Line -> {
                // A line STARTS at position and extends along `angle` for `length`. Pass explicit
                // endpoints so LineNode renders the true slope (a `/` diagonal is NOT mirrored).
                val len = node.size?.first ?: defaultLen
                val end = Pair(pos.first + len * cos(node.angle), pos.second + len * sin(node.angle))
                LineNode(node.id, node.role, start = pos, end = end, style = Style())
            }

            NodeType.Curve -> {
                val len = node.size?.first ?: defaultLen
                val start = pos
                val end = Pair(pos.first + len * cos(node.angle), pos.second + len * sin(node.angle))
                val control = computeQuadraticControl(start, end, args.tension)
                val path = BezierPath(startPoint = start, endPoint = end, controlPoint1 = control, tension = args.tension)
                CurveNode(node.id, node.role, path = path, style = Style())
            }

            NodeType.Shape -> {
                // A small axis-aligned square centered at position — the default detail "shape".
                val s = node.size?.first ?: defaultLen
                val half = s / 2.0
                val pts = listOf(
                    Pair(pos.first - half, pos.second - half),
                    Pair(pos.first + half, pos.second - half),
                    Pair(pos.first + half, pos.second + half),
                    Pair(pos.first - half, pos.second + half),
                )
                ShapeNode(node.id, node.role, points = pts, closed = true, style = Style())
            }
        }
    }
}
