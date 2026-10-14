package by.ciszkin.draftsllman.architect

import ai.koog.agents.core.tools.annotations.LLMDescription
import by.ciszkin.draftsllman.scene.ResolvedBox
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The Architect's connected-skeleton output (Zero-Coordinate Slice 4): the component primitives, their
 * named anchors, and the inter-component constraints that link them. Replaces the region-based
 * ComponentBrief flow for macro-layout. The LLM emits this; SkeletonBuilder turns it into a solvable
 * SceneGraph. See `docs/superpowers/specs/2026-08-02-slice4-connection-flow-design.md`.
 */
@Serializable
data class SkeletonComponent(
    val id: String,
    val role: String,
    val type: String,                                   // circle | rect | line | group
    // Sizes-are-pixels schema teaching (live-run finding 2026-08-21: a model declared wheels
    // 0.2x0.2 — every OTHER field in the contract is ratio-based, so sizes were misread as canvas
    // fractions and solved to points). @property: use-site target required (see anchors below).
    @property:LLMDescription("approximate size in canvas PIXELS — NOT a fraction of the canvas. " +
        "On a typical canvas declare a wheel ~60-100, a seat ~40; 0.2 means 0.2 pixels and " +
        "collapses to a point.")
    val width: Double,
    @property:LLMDescription("approximate size in canvas PIXELS — NOT a fraction of the canvas. " +
        "On a typical canvas declare a wheel ~60-100, a seat ~40; 0.2 means 0.2 pixels and " +
        "collapses to a point.")
    val height: Double,
    // Schema-level alias teaching (live-run finding: every run burned 1-2 emits on reserved-word
    // alias names despite the prose prohibition — models follow field descriptions far better).
    // @property: use-site target is REQUIRED — without it the annotation lands on the constructor
    // parameter (Kotlin's first-applicable-target rule) where the serialization plugin ignores it.
    @property:LLMDescription("alias name -> geometric anchor name (e.g. \"hub\" -> \"CENTER\"). Alias names must " +
        "NOT be reserved geometric anchor words (CENTER, TOP, BOTTOM, LEFT, RIGHT, TOP_LEFT, ...) — the " +
        "check is case-insensitive, so \"center\" or \"bottom\" is rejected. Use names like hubPt, " +
        "rearAxle, topJunc.")
    val anchors: Map<String, String> = emptyMap(),      // alias name -> geometric anchor name, e.g. "hub" -> "CENTER"
)

/**
 * Converts a solved skeleton component into a [ComponentBrief] for the Phase 3 Draftsman detail run.
 * [solvedBox] is the component's GLOBALLY-solved position+size (from the Phase 2 solved SceneGraph) —
 * it becomes the brief's region so [DraftsmanRunner.runOnSkeleton] can seed the engine at the right
 * global coordinates and report the detail cluster's home region. The description is generic detail
 * guidance; a live Draftsman LLM specializes it from the role (spokes for a wheel, rivets for a frame).
 */
fun SkeletonComponent.toDetailBrief(solvedBox: ResolvedBox): ComponentBrief = ComponentBrief(
    id = id,
    semanticRole = role,
    description = "Add detail to this $role using add_nodes (e.g. spokes, rivets, ribs, or texture).",
    region = solvedBox,
)

/** Constraint-ref vocabulary, schema-taught (live-run finding: models reference aliases they never
 *  declared on the component — every such ref is rejected as an unknown anchor). @property: use-site
 *  target is REQUIRED — without it the annotation lands on the constructor parameter where the
 *  serialization plugin ignores it. */
private const val REF_RULE =
    "\"componentId:anchorName\" where anchorName is a DECLARED alias from that component's anchors " +
    "map or an exact geometric anchor name (CENTER, TOP_CENTER, ..., CENTER_X, LEFT, ..., TOP, " +
    "BOTTOM, ...). Ad-hoc spellings like \"cx\" are rejected as unknown anchors."

@Serializable
data class SkeletonConstraint(
    val id: String,
    val kind: String,                                   // connect | align | distance_ratio | contain | relative_size | distribute
    @property:LLMDescription(REF_RULE + " For contain, this is the plain component id (no anchor).")
    val source: String = "",                            // "componentId:anchorName" | plain component id (contain/relative_size) | unused (distribute)
    @property:LLMDescription(REF_RULE + " For contain, this is the literal \"canvas\".")
    val target: String = "",                            // "componentId:anchorName" | "canvas" (contain) | plain id (relative_size) | unused (distribute)
    @property:LLMDescription("ratio value — distance_ratio: fraction of a canvas axis in (0.0, 1.0]; " +
        "relative_size: size multiplier in (0.0, 5.0] (e.g. 1.6 = 1.6x the target's size, both axes).")
    @SerialName("ratio") val ratio: Double = 0.0,       // distance_ratio: fraction of canvas dim in (0.0, 1.0]; relative_size: size multiplier in (0.0, 5.0]
    @SerialName("relative_to") val relativeTo: String = "canvas_width",  // distance_ratio only: canvas_width | canvas_height
    @SerialName("margin") val margin: Double = 0.0,     // contain only: fraction kept clear per side, in [0.0, 0.5]
    @property:LLMDescription("distribute only: the component ids in spacing order — left-to-right for " +
        "along=width, top-to-bottom for along=height. At least 3.")
    @SerialName("components") val components: List<String> = emptyList(),  // distribute only: ordered ids (left-to-right / top-to-bottom)
    @property:LLMDescription("distribute only: the spacing axis — \"width\" (horizontal) or \"height\" (vertical).")
    @SerialName("along") val along: String = "width",                      // distribute only: width | height
)

/** Args for the Architect's emit_skeleton tool (wired up in Slice 4 Part 2). */
@Serializable
@SerialName("emit_skeleton")
data class EmitSkeleton(
    val components: List<SkeletonComponent> = emptyList(),
    val constraints: List<SkeletonConstraint> = emptyList(),
)
