package by.ciszkin.draftsllman.scene.svg

import by.ciszkin.draftsllman.scene.Style
import java.util.Locale

/** Export-only coordinate formatting (Zero-Coordinate policy §9): exactly two decimals,
 *  Locale.US, HALF_UP. Runtime geometry is exact Double — this is the ONLY rounding, at
 *  serialization. Negative zero (mirrored boxes, Cassowary residuals) normalizes to "0.00". */
internal fun Double.toSvgCoord(): String =
    String.format(Locale.US, "%.2f", this).let { if (it == "-0.00") "0.00" else it }

/** Attribute helpers used by SceneNode.toSvgString() and SvgExporter. Backed by SvgSanitizer. */
object SvgAttr {
    fun attr(name: String, value: String): String = """ $name="${SvgSanitizer.escapeAttr(value)}""""
    fun attr(name: String, value: Double): String = attr(name, value.toSvgCoord())
    fun style(sb: StringBuilder, style: Style) {
        // No fill attribute emitted when fill is null (SVG default applies).
        style.fill?.let { sb.append(attr("fill", it.toCss())) }
        style.stroke?.let {
            sb.append(attr("stroke", it.toCss()))
            sb.append(attr("stroke-width", style.strokeWidth))
        }
    }
}
