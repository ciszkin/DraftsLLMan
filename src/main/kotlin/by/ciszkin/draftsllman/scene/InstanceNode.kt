package by.ciszkin.draftsllman.scene

import by.ciszkin.draftsllman.library.Master
import by.ciszkin.draftsllman.scene.svg.SvgAttr
import by.ciszkin.draftsllman.scene.svg.toSvgCoord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A scene node that instantiates a [Master] from a [ComponentLibrary] at a position with a scale
 * override. Emits a `<use>` element referencing the master's `<defs>` group. The "Instance" in the
 * Prototype + Override model (§3.3 / §9.6).
 *
 * [box] is derived from [x]/[y] (a point at the instance position). [withBox] updates the position.
 * [scale] 1.0 = no transform.
 */
@Serializable
@SerialName("instance")
data class InstanceNode(
    override val id: String,
    override val semanticRole: String,
    val masterId: String,
    val x: Double,
    val y: Double,
    val scale: Double = 1.0,
    override val style: Style = Style(),
) : SceneNode {
    override val box: ResolvedBox get() = ResolvedBox(x, y, 0.0, 0.0)

    override fun toSvgString(): String = buildString {
        append("<use")
        append(SvgAttr.attr("id", id))
        append(SvgAttr.attr("data-role", semanticRole))
        append(SvgAttr.attr("href", "#$masterId"))
        append(SvgAttr.attr("x", x))
        append(SvgAttr.attr("y", y))
        if (scale != 1.0) append(" transform=\"scale(${scale.toSvgCoord()})\"")
        SvgAttr.style(this, style)
        append("/>")
    }

    override fun withBox(box: ResolvedBox): SceneNode = copy(x = box.x, y = box.y)
    override fun withId(id: String): SceneNode = copy(id = id)
}
