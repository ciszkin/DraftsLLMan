package by.ciszkin.draftsllman.contract

import by.ciszkin.draftsllman.scene.Anchor
import by.ciszkin.draftsllman.scene.AnchorAxis
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.solver.ConstraintSet
import by.ciszkin.draftsllman.solver.SceneConstraint
import by.ciszkin.draftsllman.solver.resolveAnchorName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Shared JSON config for encoding/decoding [ToolArgs] variants (with the `type` discriminator).
 * Both the encode side ([by.ciszkin.draftsllman.agent.DraftsTools] `dispatch`) and the decode side
 * ([by.ciszkin.draftsllman.engine.ToolExecutor]) MUST use this exact config so the discriminator
 * the LLM emits round-trips identically.
 */
internal val toolArgsJson: Json = Json { ignoreUnknownKeys = false; explicitNulls = false; classDiscriminator = "type" }

@Serializable
sealed interface ToolArgs {
    fun semanticValidate(graph: SceneGraph, constraints: ConstraintSet): List<ValidationError>

    /** preferredX/preferredY are NON-authoritative solver region hints (default = canvas center). */
    @Serializable @SerialName("add_node")
    data class AddNode(
        val id: String, val role: String, val kind: String,   // circle|rect|line|group
        val width: Double, val height: Double,
        val preferredX: Double? = null,
        val preferredY: Double? = null,
        val childIds: List<String> = emptyList(),
        val anchors: Map<String, Anchor> = emptyMap(),   // named-anchor aliases: name -> geometric anchor
    ) : ToolArgs {
        override fun semanticValidate(graph: SceneGraph, constraints: ConstraintSet): List<ValidationError> = buildList {
            if (id.isBlank()) add(ValidationError("id", "id is blank"))
            if (graph.nodes.containsKey(id)) add(ValidationError("id", "id '$id' already exists"))
            if (kind !in setOf("circle", "rect", "line", "group"))
                add(ValidationError("kind", "unknown/unsupported kind '$kind' (path is deferred)"))
            if (width <= 0.0 || height <= 0.0) add(ValidationError("size", "width/height must be positive"))
        }
    }

    @Serializable @SerialName("add_constraint")
    data class AddConstraint(
        val id: String, val kind: String,   // align|distance|connect (NOT "type" — collides with discriminator)
        val sourceId: String,
        // A geometric anchor name ("CENTER", "TOP_CENTER", …) or a declared alias ("hub"). Resolved by the
        // engine via [resolveAnchorName]; geometric names win over aliases.
        val sourceAnchor: String,
        val targetId: String,
        val targetAnchor: String,
        val pixels: Double = 0.0,
    ) : ToolArgs {
        override fun semanticValidate(graph: SceneGraph, constraints: ConstraintSet): List<ValidationError> = buildList {
            if (id.isBlank()) add(ValidationError("id", "id is blank"))
            if (constraints.constraints.any { it.id == id }) add(ValidationError("id", "id '$id' already exists"))
            if (sourceId !in graph.nodes) add(ValidationError("sourceId", "unknown node '$sourceId'"))
            if (targetId !in graph.nodes) add(ValidationError("targetId", "unknown node '$targetId'"))
            if (kind !in setOf("align", "distance", "connect"))
                add(ValidationError("kind", "unknown constraint kind '$kind'"))
            val src = runCatching { resolveAnchorName(sourceAnchor, sourceId, graph.anchorAliases) }
            val tgt = runCatching { resolveAnchorName(targetAnchor, targetId, graph.anchorAliases) }
            if (src.isFailure) add(ValidationError("sourceAnchor", "unknown anchor '$sourceAnchor' on '$sourceId'"))
            if (tgt.isFailure) add(ValidationError("targetAnchor", "unknown anchor '$targetAnchor' on '$targetId'"))
            if (src.isSuccess && tgt.isSuccess) {
                val s = src.getOrNull()!!; val t = tgt.getOrNull()!!
                val axisErr = when (kind) {
                    "align" -> s.axis != t.axis
                    "distance" -> s.axis == AnchorAxis.POINT || t.axis == AnchorAxis.POINT || s.axis != t.axis
                    "connect" -> s.axis != AnchorAxis.POINT || t.axis != AnchorAxis.POINT
                    else -> false
                }
                if (axisErr) add(ValidationError("anchors", "anchors incompatible with kind '$kind': $s / $t"))
            }
        }

        /** Resolves both anchor names against [graph]'s aliases and builds the [SceneConstraint]. */
        fun toConstraint(graph: SceneGraph): SceneConstraint {
            val s = resolveAnchorName(sourceAnchor, sourceId, graph.anchorAliases)
            val t = resolveAnchorName(targetAnchor, targetId, graph.anchorAliases)
            return when (kind) {
                "align" -> SceneConstraint.Align(id, sourceId, s, targetId, t)
                "distance" -> SceneConstraint.Distance(id, sourceId, s, targetId, t, pixels)
                "connect" -> SceneConstraint.Connect(id, sourceId, s, targetId, t)
                else -> error("unknown constraint kind '$kind'")
            }
        }
    }

    @Serializable @SerialName("remove_node") data class RemoveNode(val id: String) : ToolArgs {
        override fun semanticValidate(graph: SceneGraph, constraints: ConstraintSet) = buildList {
            if (id !in graph.nodes) add(ValidationError("id", "unknown node '$id'"))
        }
    }

    @Serializable @SerialName("remove_constraint") data class RemoveConstraint(val id: String) : ToolArgs {
        override fun semanticValidate(graph: SceneGraph, constraints: ConstraintSet) = buildList {
            if (constraints.constraints.none { it.id == id }) add(ValidationError("id", "unknown constraint '$id'"))
        }
    }

    @Serializable @SerialName("set_view_box")
    data class SetViewBox(val width: Double, val height: Double) : ToolArgs {
        override fun semanticValidate(graph: SceneGraph, constraints: ConstraintSet) = buildList {
            if (width <= 0.0 || height <= 0.0) add(ValidationError("size", "viewbox must be positive"))
        }
    }

    @Serializable @SerialName("export_svg") data class ExportSvg(val dummy: Int = 0) : ToolArgs {
        override fun semanticValidate(graph: SceneGraph, constraints: ConstraintSet) = emptyList<ValidationError>()
    }

    @Serializable @SerialName("request_visual_critique")
    data class RequestVisualCritique(
        val nodeId: String? = null,
        val globalSize: Int = 256,
        val roiSize: Int = 512,
    ) : ToolArgs {
        override fun semanticValidate(graph: SceneGraph, constraints: ConstraintSet): List<ValidationError> = buildList {
            if (nodeId != null && nodeId !in graph.nodes)
                add(ValidationError("nodeId", "unknown node '$nodeId'"))
            if (globalSize <= 0 || roiSize <= 0)
                add(ValidationError("size", "sizes must be positive"))
        }
    }

    /**
     * Draftsman's unified detail-injection tool. The LLM declares *semantic intent*
     * (node type + preset + count); the GeometryEngine computes exact positions later.
     *
     * JSON keys are snake_case (LLM-facing). `node_type` stays [String] at this contract
     * boundary; conversion to `GeometryEngine.NodeType` is the dispatch layer's job.
     */
    @Serializable @SerialName("add_nodes")
    data class AddNodesArgs(
        @SerialName("target_id") val targetId: String,
        @SerialName("node_name") val nodeName: String,
        @SerialName("node_type") val nodeType: String,   // point|line|curve|shape
        val preset: String,                               // single_node|radial_pattern|linear_pattern|grid_pattern|boundary_pattern
        val count: Int? = null,
        val rows: Int? = null,
        val cols: Int? = null,
        @SerialName("center_anchor") val centerAnchor: String = "CENTER",
        @SerialName("boundary_anchor") val boundaryAnchor: String? = null,
        @SerialName("start_anchor") val startAnchor: String? = null,
        @SerialName("end_anchor") val endAnchor: String? = null,
        @SerialName("top_left_anchor") val topLeftAnchor: String? = null,
        @SerialName("bottom_right_anchor") val bottomRightAnchor: String? = null,
        val displacement: Double = 0.5,
        @SerialName("size_ratio") val sizeRatio: Double = 0.05,
        val tension: Double = 0.5,
    ) : ToolArgs {
        override fun semanticValidate(graph: SceneGraph, constraints: ConstraintSet): List<ValidationError> = buildList {
            if (targetId.isBlank()) add(ValidationError("target_id", "target_id is blank"))
            if (targetId !in graph.nodes) add(ValidationError("target_id", "unknown component '$targetId'"))
            if (nodeName.isBlank()) add(ValidationError("node_name", "node_name is blank"))
            if (nodeType !in setOf("point", "line", "curve", "shape"))
                add(ValidationError("node_type", "unknown node_type '$nodeType'"))
            if (preset !in setOf("single_node", "radial_pattern", "linear_pattern", "grid_pattern", "boundary_pattern"))
                add(ValidationError("preset", "unknown preset '$preset'"))
            when (preset) {
                "radial_pattern" -> {
                    if (count == null) add(ValidationError("count", "preset 'radial_pattern' requires count"))
                    else if (count <= 0) add(ValidationError("count", "count must be positive"))
                    if (boundaryAnchor == null) add(ValidationError("boundary_anchor", "preset 'radial_pattern' requires boundary_anchor"))
                }
                "boundary_pattern" -> {
                    if (count == null) add(ValidationError("count", "preset 'boundary_pattern' requires count"))
                    else if (count <= 0) add(ValidationError("count", "count must be positive"))
                }
                "linear_pattern" -> {
                    if (count == null) add(ValidationError("count", "preset 'linear_pattern' requires count"))
                    else if (count <= 0) add(ValidationError("count", "count must be positive"))
                    if (startAnchor == null) add(ValidationError("start_anchor", "preset 'linear_pattern' requires start_anchor"))
                    if (endAnchor == null) add(ValidationError("end_anchor", "preset 'linear_pattern' requires end_anchor"))
                }
                "grid_pattern" -> {
                    if (rows == null) add(ValidationError("rows", "preset 'grid_pattern' requires rows"))
                    else if (rows <= 0) add(ValidationError("rows", "rows must be positive"))
                    if (cols == null) add(ValidationError("cols", "preset 'grid_pattern' requires cols"))
                    else if (cols <= 0) add(ValidationError("cols", "cols must be positive"))
                    if (topLeftAnchor == null) add(ValidationError("top_left_anchor", "preset 'grid_pattern' requires top_left_anchor"))
                    if (bottomRightAnchor == null) add(ValidationError("bottom_right_anchor", "preset 'grid_pattern' requires bottom_right_anchor"))
                }
            }
            // Duplicate-id guard: every generated id has the form "${targetId}_${nodeName}_<index>"
            // (or "${targetId}_${nodeName}_<row>_<col>" for grid). Reject if any existing node id has
            // that prefix — a conservative superset of the exact-id check that catches a Draftsman retry
            // double-emitting the same detail (the engine appends, so a retry would collide). Matches
            // AddNode's "already exists" style. A different nodeName on the same target uses a different
            // prefix and is allowed (spokes then rivets on one wheel coexist).
            val idPrefix = "${targetId}_${nodeName}_"
            if (graph.nodes.keys.any { it.startsWith(idPrefix) })
                add(ValidationError("id", "generated ids '$idPrefix...' already exist in the scene graph"))
        }
    }
}
