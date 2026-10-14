package by.ciszkin.draftsllman.scene.svg

import by.ciszkin.draftsllman.library.ComponentLibrary
import by.ciszkin.draftsllman.scene.GroupNode
import by.ciszkin.draftsllman.scene.InstanceNode
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.svg.toSvgCoord

object SvgExporter {
    fun emit(graph: SceneGraph): String = emit(graph, ComponentLibrary())

    fun emit(graph: SceneGraph, library: ComponentLibrary): String {
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${graph.canvas.width.toSvgCoord()} ${graph.canvas.height.toSvgCoord()}">""")
        // <defs> for masters referenced by InstanceNodes
        val referencedMasterIds = graph.nodes.values.filterIsInstance<InstanceNode>().map { it.masterId }.toSet()
        if (referencedMasterIds.isNotEmpty()) {
            sb.append("<defs>")
            for (masterId in referencedMasterIds) {
                val master = library.get(masterId) ?: continue
                sb.append("""<g id="$masterId">""")
                for (node in master.nodes) sb.append(node.toSvgString())
                sb.append("</g>")
            }
            sb.append("</defs>")
        }
        for (id in graph.rootIds) emitNode(graph, id, sb)
        sb.append("</svg>")
        return sb.toString()
    }

    private fun emitNode(graph: SceneGraph, id: String, sb: StringBuilder) {
        val node = graph.nodes[id] ?: return
        when (node) {
            is GroupNode -> {
                sb.append("""<g${SvgAttr.attr("id", node.id)}${SvgAttr.attr("data-role", node.semanticRole)}>""")
                for (child in graph.children[node.id].orEmpty()) emitNode(graph, child, sb)
                sb.append("</g>")
            }
            else -> sb.append(node.toSvgString()) // Circle/Rect/Line already carry id + data-role
        }
    }
}
