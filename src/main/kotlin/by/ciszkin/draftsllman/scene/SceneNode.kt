package by.ciszkin.draftsllman.scene

import by.ciszkin.draftsllman.scene.svg.SvgAttr
import by.ciszkin.draftsllman.scene.svg.toSvgCoord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface SceneNode {
    val id: String
    val semanticRole: String
    val box: ResolvedBox
    val style: Style
    fun toSvgString(): String
    /** Repositions/resizes the node to [box]. Semantics are per-implementation — see each node's
     *  KDoc: Circle/Rect/Group set the box, Line affine-scales its endpoints into it, Curve/Shape
     *  only translate their points, Instance moves its position. Do not rely on size changes for
     *  the translate-only kinds. */
    fun withBox(box: ResolvedBox): SceneNode
    fun withId(id: String): SceneNode
}

@Serializable
@SerialName("circle")
data class CircleNode(
    override val id: String,
    override val semanticRole: String,
    override val box: ResolvedBox,
    override val style: Style,
) : SceneNode {
    /** Radius derived from the smaller box dimension — single source for SVG emit and Skia raster. */
    val radius: Double get() = minOf(box.width, box.height) / 2.0
    override fun toSvgString(): String = buildString {
        append("<circle"); append(SvgAttr.attr("id", id)); append(SvgAttr.attr("data-role", semanticRole))
        append(SvgAttr.attr("cx", box.cx)); append(SvgAttr.attr("cy", box.cy))
        append(SvgAttr.attr("r", radius))
        SvgAttr.style(this, style); append("/>")
    }
    override fun withBox(box: ResolvedBox) = copy(box = box)
    override fun withId(id: String) = copy(id = id)
}

@Serializable
@SerialName("rect")
data class RectNode(
    override val id: String,
    override val semanticRole: String,
    override val box: ResolvedBox,
    override val style: Style,
) : SceneNode {
    override fun toSvgString(): String = buildString {
        append("<rect"); append(SvgAttr.attr("id", id)); append(SvgAttr.attr("data-role", semanticRole))
        append(SvgAttr.attr("x", box.x)); append(SvgAttr.attr("y", box.y))
        append(SvgAttr.attr("width", box.width)); append(SvgAttr.attr("height", box.height))
        if (style.cornerRadius > 0.0) append(SvgAttr.attr("rx", style.cornerRadius))
        SvgAttr.style(this, style); append("/>")
    }
    override fun withBox(box: ResolvedBox) = copy(box = box)
    override fun withId(id: String) = copy(id = id)
}

@Serializable
@SerialName("line")
data class LineNode(
    override val id: String,
    override val semanticRole: String,
    val start: Pair<Double, Double>,
    val end: Pair<Double, Double>,
    override val style: Style = Style(),
) : SceneNode {
    /**
     * Tight bounding box of [start] and [end]. A line is NOT forced axis-aligned: storing explicit
     * endpoints means a `/` diagonal renders with its true slope (the previous box-only model always
     * emitted min-corner→max-corner, mirroring `/` lines into `\` lines).
     */
    override val box: ResolvedBox get() = ResolvedBox.fromPoints(listOf(start, end))

    override fun toSvgString(): String = buildString {
        append("<line"); append(SvgAttr.attr("id", id)); append(SvgAttr.attr("data-role", semanticRole))
        append(SvgAttr.attr("x1", start.first)); append(SvgAttr.attr("y1", start.second))
        append(SvgAttr.attr("x2", end.first)); append(SvgAttr.attr("y2", end.second))
        SvgAttr.style(this, style); append("/>")
    }

    /** Maps the line into [newBox]: endpoints affine-scaled from the current box (per-axis,
     *  anchored at the new origin). For a two-point line the endpoints sit at opposite corners
     *  of the derived box, so they land on the new box's opposite corners — slope preserved,
     *  derived box == [newBox] for non-degenerate lines up to float round-trip (ulp-level)
     *  (solver-solved geometry is what renders). A degenerate old axis (width or height 0)
     *  keeps scale 1.0: the line stays degenerate and the derived box may be tighter than
     *  [newBox] on that axis. A negative [newBox] extent mirrors the line across that axis —
     *  the solver does not sign-check boxes. */
    override fun withBox(newBox: ResolvedBox): SceneNode {
        val cur = box
        val sx = if (cur.width > 0.0) newBox.width / cur.width else 1.0
        val sy = if (cur.height > 0.0) newBox.height / cur.height else 1.0
        if (sx == 1.0 && sy == 1.0 && newBox.x == cur.x && newBox.y == cur.y) return this
        fun map(p: Pair<Double, Double>) =
            newBox.x + (p.first - cur.x) * sx to newBox.y + (p.second - cur.y) * sy
        return copy(start = map(start), end = map(end))
    }

    override fun withId(id: String) = copy(id = id)
}

/** Structural grouping. No solver vars; box is the union of children (computed by SceneGraph/Engine). */
@Serializable
@SerialName("group")
data class GroupNode(
    override val id: String,
    override val semanticRole: String,
    val childIds: List<String>,
    override val box: ResolvedBox = ResolvedBox(0.0, 0.0, 0.0, 0.0),
    override val style: Style = Style(),
) : SceneNode {
    override fun toSvgString(): String = "" // emitted by SvgExporter with children
    override fun withBox(box: ResolvedBox) = copy(box = box)
    override fun withId(id: String) = copy(id = id)
}

/**
 * A bezier [path] drawn as an SVG `<path>`. Quadratic (`M .. Q ..`) when [BezierPath.controlPoint2]
 * is null, cubic (`M .. C .. ..`) otherwise. [box] is the tight bounding box of the path's anchor
 * and control points; [withBox] translates every anchor/control point by the origin delta so the
 * node is translatable for layout (e.g. by `ComponentMerge`).
 */
@Serializable
@SerialName("curve")
data class CurveNode(
    override val id: String,
    override val semanticRole: String,
    val path: BezierPath,
    override val style: Style = Style(),
) : SceneNode {
    override val box: ResolvedBox
        get() {
            val pts = buildList {
                add(path.startPoint)
                add(path.endPoint)
                add(path.controlPoint1)
                path.controlPoint2?.let { add(it) }
            }
            return ResolvedBox.fromPoints(pts)
        }

    override fun toSvgString(): String = buildString {
        val (sx, sy) = path.startPoint
        val (ex, ey) = path.endPoint
        val (c1x, c1y) = path.controlPoint1
        val d = if (path.isCubic) {
            val c2 = path.controlPoint2!!
            "M ${sx.toSvgCoord()} ${sy.toSvgCoord()} C ${c1x.toSvgCoord()} ${c1y.toSvgCoord()} " +
                "${c2.first.toSvgCoord()} ${c2.second.toSvgCoord()} ${ex.toSvgCoord()} ${ey.toSvgCoord()}"
        } else {
            "M ${sx.toSvgCoord()} ${sy.toSvgCoord()} Q ${c1x.toSvgCoord()} ${c1y.toSvgCoord()} ${ex.toSvgCoord()} ${ey.toSvgCoord()}"
        }
        append("<path")
        append(SvgAttr.attr("id", id))
        append(SvgAttr.attr("data-role", semanticRole))
        append(SvgAttr.attr("d", d))
        SvgAttr.style(this, style)
        append("/>")
    }

    /** Pure translate: shifts every anchor/control point by the origin delta of [newBox]. */
    override fun withBox(newBox: ResolvedBox): SceneNode {
        val current = box
        val dx = newBox.x - current.x
        val dy = newBox.y - current.y
        if (dx == 0.0 && dy == 0.0) return this
        fun shift(p: Pair<Double, Double>) = p.first + dx to p.second + dy
        return copy(path = path.copy(
            startPoint = shift(path.startPoint),
            endPoint = shift(path.endPoint),
            controlPoint1 = shift(path.controlPoint1),
            controlPoint2 = path.controlPoint2?.let { shift(it) },
        ))
    }

    override fun withId(id: String): SceneNode = copy(id = id)
}

/**
 * A closed (`<polygon>`) or open (`<path>` polyline) shape through [points]. Requires at least three
 * points. [box] is the tight bounding box of the points; [withBox] translates every point by the
 * origin delta so the node is translatable for layout (e.g. by `ComponentMerge`).
 */
@Serializable
@SerialName("shape")
data class ShapeNode(
    override val id: String,
    override val semanticRole: String,
    val points: List<Pair<Double, Double>>,
    val closed: Boolean = true,
    override val style: Style = Style(),
) : SceneNode {
    init {
        require(points.size >= 3) { "ShapeNode requires at least 3 points, got ${points.size}" }
    }

    override val box: ResolvedBox get() = ResolvedBox.fromPoints(points)

    override fun toSvgString(): String = buildString {
        append(if (closed) "<polygon" else "<path")
        append(SvgAttr.attr("id", id))
        append(SvgAttr.attr("data-role", semanticRole))
        if (closed) {
            append(SvgAttr.attr("points", points.joinToString(" ") { "${it.first.toSvgCoord()} ${it.second.toSvgCoord()}" }))
        } else {
            val first = points.first()
            val d = buildString {
                append("M ${first.first.toSvgCoord()} ${first.second.toSvgCoord()}")
                for (i in 1 until points.size) {
                    append(" L ${points[i].first.toSvgCoord()} ${points[i].second.toSvgCoord()}")
                }
            }
            append(SvgAttr.attr("d", d))
        }
        SvgAttr.style(this, style)
        append("/>")
    }

    /** Pure translate: shifts every point by the origin delta of [newBox]. */
    override fun withBox(newBox: ResolvedBox): SceneNode {
        val current = box
        val dx = newBox.x - current.x
        val dy = newBox.y - current.y
        if (dx == 0.0 && dy == 0.0) return this
        return copy(points = points.map { (x, y) -> x + dx to y + dy })
    }

    override fun withId(id: String): SceneNode = copy(id = id)
}
