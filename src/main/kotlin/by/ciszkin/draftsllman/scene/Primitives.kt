package by.ciszkin.draftsllman.scene

import kotlinx.serialization.Serializable

@Serializable
data class CanvasSpec(val width: Double, val height: Double) {
    companion object { val DEFAULT = CanvasSpec(800.0, 600.0) }
}

@Serializable
data class ResolvedBox(val x: Double, val y: Double, val width: Double, val height: Double) {
    val cx: Double get() = x + width / 2.0
    val cy: Double get() = y + height / 2.0
    val left: Double get() = x
    val right: Double get() = x + width
    val top: Double get() = y
    val bottom: Double get() = y + height

    fun translate(dx: Double, dy: Double): ResolvedBox = copy(x = x + dx, y = y + dy)

    companion object {
        /** Tight bounding union of the given boxes, or null if the list is empty. */
        fun unionOf(boxes: List<ResolvedBox>): ResolvedBox? {
            if (boxes.isEmpty()) return null
            val minLeft = boxes.minOf { it.left }
            val minTop = boxes.minOf { it.top }
            return ResolvedBox(
                x = minLeft, y = minTop,
                width = boxes.maxOf { it.right } - minLeft,
                height = boxes.maxOf { it.bottom } - minTop,
            )
        }

        /** Axis-aligned bounding box of a non-empty point list (min/max over x and y). */
        fun fromPoints(points: List<Pair<Double, Double>>): ResolvedBox {
            require(points.isNotEmpty()) { "fromPoints requires a non-empty point list" }
            val minX = points.minOf { it.first }
            val minY = points.minOf { it.second }
            val maxX = points.maxOf { it.first }
            val maxY = points.maxOf { it.second }
            return ResolvedBox(x = minX, y = minY, width = maxX - minX, height = maxY - minY)
        }
    }
}

enum class AnchorAxis { X, Y, POINT }

@Serializable
enum class Anchor(val axis: AnchorAxis) {
    LEFT(AnchorAxis.X), RIGHT(AnchorAxis.X), CENTER_X(AnchorAxis.X),
    TOP(AnchorAxis.Y), BOTTOM(AnchorAxis.Y), CENTER_Y(AnchorAxis.Y),
    TOP_LEFT(AnchorAxis.POINT), TOP_RIGHT(AnchorAxis.POINT),
    BOTTOM_LEFT(AnchorAxis.POINT), BOTTOM_RIGHT(AnchorAxis.POINT),
    CENTER(AnchorAxis.POINT),
    TOP_CENTER(AnchorAxis.POINT), BOTTOM_CENTER(AnchorAxis.POINT),
    LEFT_CENTER(AnchorAxis.POINT), RIGHT_CENTER(AnchorAxis.POINT);
}

/** ARGB int (0xAARRGGBB), Skia's convention. Phase 1: opaque only (alpha == 0xFF). */
@Serializable
@JvmInline
value class Color(val argb: Int) {
    init { require((argb ushr 24) == 0xFF) { "Phase 1 supports opaque colors only (alpha must be 0xFF)" } }

    fun toCss(): String = "#" + "%06X".format(argb and 0x00FFFFFF)

    companion object {
        val BLACK = Color(0xFF000000.toInt())
        val WHITE = Color(0xFFFFFFFF.toInt())
        fun rgb(r: Int, g: Int, b: Int): Color = Color((0xFF shl 24) or (r shl 16) or (g shl 8) or b)
        fun fromCss(css: String): Color {
            val hex = css.removePrefix("#")
            require(hex.length == 6 || hex.length == 8) { "expected #RRGGBB or #AARRGGBB, got $css" }
            val (a, rest) = if (hex.length == 8) hex.substring(0, 2) to hex.substring(2) else "FF" to hex
            require(a.uppercase() == "FF") { "Phase 1 supports opaque colors only" }
            val r = rest.substring(0, 2).toInt(16)
            val g = rest.substring(2, 4).toInt(16)
            val b = rest.substring(4, 6).toInt(16)
            return rgb(r, g, b)
        }
    }
}

@Serializable
data class Style(
    val fill: Color? = null,
    val stroke: Color? = null,
    val strokeWidth: Double = 1.0,
    val cornerRadius: Double = 0.0,
)
