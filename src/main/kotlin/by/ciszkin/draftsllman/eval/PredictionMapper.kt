package by.ciszkin.draftsllman.eval

import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.CurveNode
import by.ciszkin.draftsllman.scene.GroupNode
import by.ciszkin.draftsllman.scene.InstanceNode
import by.ciszkin.draftsllman.scene.LineNode
import by.ciszkin.draftsllman.scene.RectNode
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.ShapeNode

/**
 * Maps a SOLVED SceneGraph (the S1 SketchRunner output) into the eval's [Prediction]: leaf
 * primitives -> [EvComponent] with coarse role + primitive kind + solved box, so T0/T3 scorers can
 * match it against the fixture truth. Structural nodes (groups, instances) are skipped — they have
 * no primitive geometry of their own.
 */
object PredictionMapper {

    fun from(graph: SceneGraph, fileName: String = "sketch-run"): Prediction {
        val components = graph.nodes.values.mapNotNull { node ->
            val kind = kindOf(node) ?: return@mapNotNull null
            EvComponent(
                id = node.id,
                role = node.semanticRole,
                kind = kind,
                box = node.box,
            )
        }
        return Prediction(components = components, fileName = fileName)
    }

    private fun kindOf(node: by.ciszkin.draftsllman.scene.SceneNode): PrimitiveKind? = when (node) {
        is CircleNode -> PrimitiveKind.CIRCLE
        is RectNode -> PrimitiveKind.RECT
        is LineNode -> PrimitiveKind.LINE
        is CurveNode -> PrimitiveKind.PATH
        is ShapeNode -> if (node.closed) PrimitiveKind.POLYGON else PrimitiveKind.PATH
        is GroupNode, is InstanceNode -> null
        else -> null
    }
}