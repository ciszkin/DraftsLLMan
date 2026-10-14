package by.ciszkin.draftsllman.scene

import by.ciszkin.draftsllman.solver.ConstraintSet
import kotlinx.serialization.Serializable

@Serializable
data class SceneGraph(
    val nodes: Map<String, SceneNode> = emptyMap(),
    val rootIds: List<String> = emptyList(),
    val children: Map<String, List<String>> = emptyMap(),
    val canvas: CanvasSpec = CanvasSpec.DEFAULT,
    val constraints: ConstraintSet = ConstraintSet.EMPTY,
    val anchorAliases: Map<String, Map<String, Anchor>> = emptyMap(),
) {
    fun withNode(node: SceneNode): SceneGraph = copy(nodes = nodes + (node.id to node))
    fun withRoot(id: String): SceneGraph = copy(rootIds = rootIds + id)
    fun withoutNode(id: String): SceneGraph = copy(
        nodes = nodes - id,
        rootIds = rootIds.filter { it != id },
        children = children - id,
        anchorAliases = anchorAliases - id,
    )
    fun withConstraints(c: ConstraintSet): SceneGraph = copy(constraints = c)

    /** Records named-anchor aliases for [nodeId] (name -> geometric anchor). No-op for an empty map. */
    fun withAnchorAliases(nodeId: String, aliases: Map<String, Anchor>): SceneGraph =
        if (aliases.isEmpty()) this else copy(anchorAliases = anchorAliases + (nodeId to aliases))

    /** Union bounding box of a group's children (recursive). Null if no leaf children. */
    fun groupBox(id: String): ResolvedBox? {
        val node = nodes[id] ?: return null
        if (node !is GroupNode) return node.box
        val leaves = children[id].orEmpty().mapNotNull { groupBox(it) }
        return ResolvedBox.unionOf(leaves)
    }

    companion object { val EMPTY = SceneGraph() }
}
