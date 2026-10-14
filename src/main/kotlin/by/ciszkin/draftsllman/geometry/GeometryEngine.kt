package by.ciszkin.draftsllman.geometry

import by.ciszkin.draftsllman.scene.Anchor
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * Pure 2D vector math engine for Draftsman detail-node generation. Reads solved absolute
 * coordinates from SceneGraph nodes and computes positions per preset (radial, linear, grid,
 * boundary). Never sees the LLM; used only by GeometryTools. Deterministic trig.
 */
object GeometryEngine {

    /**
     * Node types for detail nodes. Sealed class provides type safety over magic strings.
     */
    sealed class NodeType(val value: String) {
        data object Point : NodeType("point")
        data object Line : NodeType("line")
        data object Curve : NodeType("curve")
        data object Shape : NodeType("shape")
    }

    /**
     * Result of a preset computation: enough data to create a SceneNode (id, role, type,
     * computed position, optional angle). Position is Pair<x, y>. Angle in radians (for curves).
     */
    data class ComputedNode(
        val id: String,
        val role: String,
        val type: NodeType,
        val position: Pair<Double, Double>?,  // null for curve (control points computed later)
        val angle: Double = 0.0,  // for radial/curve orientation
        val size: Pair<Double, Double>? = null,  // width, height (if applicable)
    )

    /**
     * Radial pattern: N nodes spaced evenly around a center→boundary vector.
     * Example: 16 spokes on a bicycle wheel (center = hub, boundary = rim).
     */
    fun generateRadialPattern(
        targetId: String,
        nodeName: String,
        nodeType: NodeType,
        centerAnchor: Anchor,
        boundaryAnchor: Anchor,
        count: Int,
        displacement: Double,
        sizeRatio: Double,
        centerPoint: Pair<Double, Double>,
        boundaryPoint: Pair<Double, Double>
    ): List<ComputedNode> {
        // Input validation
        require(count > 0) { "count must be positive: $count" }
        require(sizeRatio > 0) { "sizeRatio must be positive: $sizeRatio" }
        require(displacement >= 0.0 && displacement <= 1.0) {
            "displacement must be in [0, 1]: $displacement"
        }

        val (cx, cy) = centerPoint
        val (bx, by) = boundaryPoint
        val dx = bx - cx
        val dy = by - cy
        val radius = sqrt(dx * dx + dy * dy)
        val baseAngle = Math.atan2(dy, dx)

        val r = radius * displacement
        val size = radius * sizeRatio

        return (0 until count).map { i ->
            val angle = baseAngle + i * 2 * PI / count
            val x = cx + r * cos(angle)
            val y = cy + r * sin(angle)
            ComputedNode(
                id = "${targetId}_${nodeName}_$i",
                role = nodeName,
                type = nodeType,
                position = Pair(x, y),
                angle = angle,
                size = if (nodeType == NodeType.Point || nodeType == NodeType.Shape) Pair(size, size) else null
            )
        }
    }

    /**
     * Linear pattern: N nodes spaced evenly along a line.
     * Example: 5 rivets along a frame tube.
     */
    fun generateLinearPattern(
        targetId: String,
        nodeName: String,
        nodeType: NodeType,
        startAnchor: Anchor,
        endAnchor: Anchor,
        count: Int,
        displacement: Double,
        sizeRatio: Double,
        startPoint: Pair<Double, Double>,
        endPoint: Pair<Double, Double>
    ): List<ComputedNode> {
        // Input validation
        require(count > 0) { "count must be positive: $count" }
        require(sizeRatio > 0) { "sizeRatio must be positive: $sizeRatio" }
        require(displacement >= 0.0 && displacement <= 1.0) {
            "displacement must be in [0, 1]: $displacement"
        }

        val (sx, sy) = startPoint
        val (ex, ey) = endPoint
        val dx = ex - sx
        val dy = ey - sy
        val length = sqrt(dx * dx + dy * dy)
        val baseAngle = Math.atan2(dy, dx)

        val size = length * sizeRatio
        val step = 1.0 / (count + 1)

        return (1..count).map { i ->
            val t = i * step
            val x = sx + dx * t
            val y = sy + dy * t
            ComputedNode(
                id = "${targetId}_${nodeName}_$i",
                role = nodeName,
                type = nodeType,
                position = Pair(x, y),
                angle = baseAngle,
                size = if (nodeType == NodeType.Point || nodeType == NodeType.Shape) Pair(size, size) else null
            )
        }
    }

    /**
     * Grid pattern: rows × cols nodes in a rectangular grid.
     * Example: 3×4 vent holes on a frame panel.
     */
    fun generateGridPattern(
        targetId: String,
        nodeName: String,
        nodeType: NodeType,
        topLeftAnchor: Anchor,
        bottomRightAnchor: Anchor,
        rows: Int,
        cols: Int,
        sizeRatio: Double,
        topLeftPoint: Pair<Double, Double>,
        bottomRightPoint: Pair<Double, Double>
    ): List<ComputedNode> {
        // Input validation
        require(rows > 0) { "rows must be positive: $rows" }
        require(cols > 0) { "cols must be positive: $cols" }
        require(sizeRatio > 0) { "sizeRatio must be positive: $sizeRatio" }

        val (lx, ly) = topLeftPoint
        val (rx, ry) = bottomRightPoint
        val width = rx - lx
        val height = ry - ly

        val cellW = width / (cols + 1)
        val cellH = height / (rows + 1)
        val size = minOf(width, height) * sizeRatio

        return (1..rows).flatMap { row ->
            (1..cols).map { col ->
                val x = lx + col * cellW
                val y = ly + row * cellH
                ComputedNode(
                    id = "${targetId}_${nodeName}_${row}_${col}",
                    role = nodeName,
                    type = nodeType,
                    position = Pair(x, y),
                    size = if (nodeType == NodeType.Point || nodeType == NodeType.Shape) Pair(size, size) else null
                )
            }
        }
    }

    /**
     * Boundary pattern: N nodes evenly spaced along the perimeter of a circle.
     * Example: 12 reflective dots around a wheel rim.
     */
    fun generateBoundaryPattern(
        targetId: String,
        nodeName: String,
        nodeType: NodeType,
        count: Int,
        sizeRatio: Double,
        centerPoint: Pair<Double, Double>,
        radius: Double
    ): List<ComputedNode> {
        // Input validation
        require(count > 0) { "count must be positive: $count" }
        require(sizeRatio > 0) { "sizeRatio must be positive: $sizeRatio" }
        require(radius > 0) { "radius must be positive: $radius" }

        val (cx, cy) = centerPoint
        val size = radius * sizeRatio

        return (0 until count).map { i ->
            val angle = i * 2 * PI / count
            val x = cx + radius * cos(angle)
            val y = cy + radius * sin(angle)
            ComputedNode(
                id = "${targetId}_${nodeName}_$i",
                role = nodeName,
                type = nodeType,
                position = Pair(x, y),
                angle = angle,
                size = if (nodeType == NodeType.Point || nodeType == NodeType.Shape) Pair(size, size) else null
            )
        }
    }
}
