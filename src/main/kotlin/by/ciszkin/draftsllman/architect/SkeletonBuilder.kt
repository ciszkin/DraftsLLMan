package by.ciszkin.draftsllman.architect

import by.ciszkin.draftsllman.scene.AnchorAxis
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.GroupNode
import by.ciszkin.draftsllman.scene.LineNode
import by.ciszkin.draftsllman.scene.RectNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.SceneNode
import by.ciszkin.draftsllman.scene.Style
import by.ciszkin.draftsllman.solver.AnchorAliases
import by.ciszkin.draftsllman.solver.ConstraintSet
import by.ciszkin.draftsllman.solver.SceneConstraint
import by.ciszkin.draftsllman.solver.resolveAliasMap
import by.ciszkin.draftsllman.solver.resolveAnchorName

/**
 * Builds the skeleton SceneGraph for a global solve (Zero-Coordinate Slice 4): one SceneNode per
 * SkeletonComponent (position solver-determined; box at the origin with the component's size), the
 * components' named anchors recorded in SceneGraph.anchorAliases, and each SkeletonConstraint
 * resolved — via resolveAnchorName — to a SceneConstraint. Pure; the LLM-free core of the two-phase
 * connection flow. The existing ConstraintSolver then solves it globally.
 */
object SkeletonBuilder {
    fun build(skeleton: EmitSkeleton, canvas: CanvasSpec = CanvasSpec.DEFAULT): SceneGraph {
        val aliases: AnchorAliases = skeleton.components.associate { c -> c.id to resolveAliasMap(c.anchors) }
        val nodes: Map<String, SceneNode> = skeleton.components.associate { c -> c.id to node(c) }
        val constraints = skeleton.constraints.map { it.toSceneConstraint(aliases, canvas) }
        return SceneGraph(
            nodes = nodes,
            rootIds = skeleton.components.map { it.id },
            children = emptyMap(),
            canvas = canvas,
            constraints = ConstraintSet(constraints),
            anchorAliases = aliases,
        )
    }

    private fun node(c: SkeletonComponent): SceneNode {
        val box = ResolvedBox(0.0, 0.0, c.width, c.height) // position is solver-determined
        return when (c.type) {
            "circle" -> CircleNode(c.id, c.role, box, Style())
            "rect" -> RectNode(c.id, c.role, box, Style())
            // A line component spans its declared box as a top-left→bottom-right diagonal.
            "line" -> LineNode(c.id, c.role, start = Pair(0.0, 0.0), end = Pair(c.width, c.height), style = Style())
            "group" -> GroupNode(c.id, c.role, childIds = emptyList())
            else -> error("unsupported component type '${c.type}'")
        }
    }

    /** Splits a "componentId:anchorName" reference. */
    internal fun parseRef(ref: String): Pair<String, String> {
        val idx = ref.indexOf(':')
        require(idx > 0 && idx < ref.length - 1) { "malformed anchor ref '$ref' (expected 'componentId:anchorName')" }
        return ref.substring(0, idx) to ref.substring(idx + 1)
    }

    private fun SkeletonConstraint.toSceneConstraint(aliases: AnchorAliases, canvas: CanvasSpec): SceneConstraint {
        // contain is component-level: source IS the component id (no anchor ref to parse); the
        // margin stays a fraction — the solver multiplies by the graph canvas (spec 2026-08-17).
        if (kind == "contain") return SceneConstraint.Contain(id, source, margin)
        if (kind == "relative_size") return SceneConstraint.RelativeSize(id, source, target, ratio)
        if (kind == "distribute") return SceneConstraint.Distribute(
            id,
            components,
            when (along) {
                "width" -> AnchorAxis.X
                "height" -> AnchorAxis.Y
                else -> error("unsupported along '$along'")
            },
        )
        val (sComp, sName) = parseRef(source)
        val (tComp, tName) = parseRef(target)
        val s = resolveAnchorName(sName, sComp, aliases)
        val t = resolveAnchorName(tName, tComp, aliases)
        return when (kind) {
            "connect" -> SceneConstraint.Connect(id, sComp, s, tComp, t)
            "align" -> SceneConstraint.Align(id, sComp, s, tComp, t)
            "distance_ratio" -> {
                val dim = when (relativeTo) {
                    "canvas_width" -> canvas.width
                    "canvas_height" -> canvas.height
                    else -> error("unsupported relative_to '$relativeTo'")
                }
                val pixels = ratio * dim
                SceneConstraint.Distance(id, sComp, s, tComp, t, pixels)
            }
            else -> error("unsupported constraint kind '$kind'")
        }
    }
}
