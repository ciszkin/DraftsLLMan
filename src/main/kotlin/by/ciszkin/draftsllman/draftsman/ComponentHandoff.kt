package by.ciszkin.draftsllman.draftsman

import by.ciszkin.draftsllman.architect.SkeletonConstraint
import by.ciszkin.draftsllman.scene.Anchor
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.GroupNode
import by.ciszkin.draftsllman.scene.LineNode
import by.ciszkin.draftsllman.scene.RectNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneNode
import kotlin.math.roundToInt

/**
 * The coordinate-free Draftsman handoff (Zero-Coordinate policy #2, master spec §5 scoped
 * per-component): qualitative size, declared anchors, semantic connections — never absolute
 * geometry. Pure; rendered into the Phase-3 system prompt by [DraftsmanRunner.runOnSkeleton].
 */

/** One neighbor relation of the target component, in the skeleton's constraint vocabulary —
 *  anchor kinds carry full "componentId:anchor" refs; relative_size carries plain component ids. */
data class ComponentConnection(val kind: String, val myRef: String, val otherRef: String)

internal fun componentHandoff(
    id: String, role: String, node: SceneNode,
    solvedBox: ResolvedBox, canvas: CanvasSpec,
    anchors: Map<String, Anchor>, connections: List<ComponentConnection>,
): String = buildList {
    // A BOTH-dims-zero box means a structural component (groups solve to 0×0) — "~0%" would read
    // as an authoritative "draw almost nothing". A single zero dim (horizontal/vertical line) is
    // legitimate geometry and keeps its band.
    if (solvedBox.width <= 0.0 && solvedBox.height <= 0.0) {
        add("Component '$id' (role: $role, type: ${nodeTypeName(node)}) — size not yet determined (structural component).")
    } else {
        add(
            "Component '$id' (role: $role, type: ${nodeTypeName(node)}) — occupies " +
                "~${band(solvedBox.width / canvas.width)}% of canvas width, " +
                "~${band(solvedBox.height / canvas.height)}% of canvas height.",
        )
    }
    if (anchors.isEmpty()) {
        add("No declared aliases — exact geometric anchor names (CENTER, LEFT, RIGHT_CENTER, ...) are valid.")
    } else {
        val list = anchors.entries.sortedBy { it.key }.joinToString(", ") { (alias, geo) -> "$alias ($geo)" }
        add("Declared anchors: $list. Exact geometric anchor names (CENTER, LEFT, RIGHT_CENTER, ...) are also valid.")
    }
    if (connections.isNotEmpty()) {
        val list = connections.joinToString("; ") { "${it.myRef} ↔ ${it.otherRef} (${it.kind})" }
        add("Connections: $list. (Context only — add_nodes anchor arguments take the bare name, e.g. \"hub\".)")
    }
}.joinToString("\n")

/** Nearest 5% band, clamped to [0, 100]: `0.224 -> 20`, `0.16 -> 15`, `1.0 -> 100`. */
private fun band(ratio: Double): Int = ((ratio * 20).roundToInt() * 5).coerceIn(0, 100)

/** The else-branch is unreachable by construction — the validator restricts skeleton component
 *  types to circle/rect/line/group; it exists only to satisfy the sealed-hierarchy exhaustiveness. */
private fun nodeTypeName(node: SceneNode): String = when (node) {
    is CircleNode -> "circle"
    is RectNode -> "rect"
    is LineNode -> "line"
    is GroupNode -> "group"
    else -> node::class.simpleName ?: "node"
}

private val ANCHOR_KINDS = setOf("connect", "align", "distance_ratio", "relative_size")

/**
 * The component's neighbor relations from the skeleton's constraints (alias-form refs, LLM
 * vocabulary — they already resolved during Phase 2 by construction). Both directions are kept
 * (my side first); `contain` is skipped (canvas-bound, no neighbor); self-relations and
 * constraints not touching the component are dropped.
 */
internal fun connectionsFor(componentId: String, constraints: List<SkeletonConstraint>): List<ComponentConnection> =
    constraints.mapNotNull { c ->
        if (c.kind !in ANCHOR_KINDS) return@mapNotNull null
        val sComp = c.source.substringBefore(':')
        val tComp = c.target.substringBefore(':')
        if (sComp == tComp) return@mapNotNull null
        when (componentId) {
            sComp -> ComponentConnection(c.kind, c.source, c.target)
            tComp -> ComponentConnection(c.kind, c.target, c.source)
            else -> null
        }
    }
