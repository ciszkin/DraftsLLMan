package by.ciszkin.draftsllman.coordination

import by.ciszkin.draftsllman.draftsman.Component
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.GroupNode
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.SceneNode
import by.ciszkin.draftsllman.solver.ConstraintSet

/**
 * Composes drawn [Component]s into one global [SceneGraph] for export. Each component's local fragment is
 * offset by its region (placing it on the global canvas) and its node ids are namespaced
 * (`"${briefId}__${nodeId}"`) to avoid collisions across components (brief ids must be unique across the input; enforced upstream by validateBriefs). Constraints are dropped —
 * [by.ciszkin.draftsllman.scene.svg.SvgExporter] emits nodes only, and each component's positions are
 * already solved by its Draftsman.
 *
 * `rootIds` is derived as the non-child nodes so SvgExporter emits each node exactly once (the engine's
 * "every node is a root" style would double-emit grouped children).
 */
fun merge(components: List<Component>, canvas: CanvasSpec): SceneGraph {
    val nodes = mutableMapOf<String, SceneNode>()
    val children = mutableMapOf<String, List<String>>()
    for (component in components) {
        val region = component.region
        val idMap = component.nodes.keys.associateWith { nodeId -> "${component.briefId}__$nodeId" }
        for ((nodeId, node) in component.nodes) {
            val newId = idMap.getValue(nodeId)
            nodes[newId] = node.withBox(node.box.translate(region.x, region.y)).withId(newId)
        }
        component.nodes.values.filterIsInstance<GroupNode>().forEach { group ->
            children[idMap.getValue(group.id)] = group.childIds.mapNotNull { idMap[it] }
        }
    }
    val childIds = children.values.flatten().toSet()
    val rootIds = nodes.keys.filter { it !in childIds }
    return SceneGraph(
        nodes = nodes,
        rootIds = rootIds,
        children = children,
        canvas = canvas,
        constraints = ConstraintSet.EMPTY,
    )
}
