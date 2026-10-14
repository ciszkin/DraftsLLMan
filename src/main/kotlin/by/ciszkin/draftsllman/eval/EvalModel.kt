package by.ciszkin.draftsllman.eval

import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.ResolvedBox
import kotlinx.serialization.Serializable

/**
 * M1 eval-fixture shared value types (the cross-module contract).
 *
 * These mirror the eval-framework's `TestCase`/`GroundTruth` models (Obsidian, §9.1) and are consumed
 * by [WireframeDSL] (ground-truth producer), [Sketchify] (rasterizer), and the [EvalScorer] suite
 * (T0 validity + T3 geometric fidelity). They are deliberately primitive — all matching/scoring
 * LOGIC lives in EvalScorer so the model stays a dumb, trivially-serializable data contract.
 */

/** Primitive kind a ground-truth or predicted component resolves to. */
@Serializable
enum class PrimitiveKind { CIRCLE, RECT, LINE, POLYGON, PATH, ANNOTATION }

/**
 * One component in a ground truth or a prediction. [role] is a COARSE semantic label (e.g.
 * "button", "sidebar", "annotation") — not a free-form description — so component matching can gate
 * on role equality, per the eval-framework §5 component-matching rule.
 */
@Serializable
data class EvComponent(
    val id: String,
    val role: String,
    val kind: PrimitiveKind,
    val box: ResolvedBox,
)

/** A relational edge between two component ids. */
@Serializable
data class EvEdge(val from: String, val to: String, val relation: EdgeRelation)

@Serializable
enum class EdgeRelation { CONTAINS, ALIGNS, CONNECTS }

/**
 * Ground truth for one synthetic case: what a perfect pipeline must recover from the sketch.
 * [annotationIds] names the pointer/scribble marks that a vision-first pipeline MUST treat as
 * annotations and exclude from the recovered component set (eval-framework §4, T1 discrimination).
 */
@Serializable
data class GroundTruth(
    val components: List<EvComponent>,
    val edges: List<EvEdge>,
    val annotationIds: Set<String>,
    val canvas: CanvasSpec,
    /** Geometry of each annotation mark — needed to LOCATE it in a sketch (e.g. the
     *  [IntegrityGate] presence check). Empty when unknown. */
    val annotationBoxes: Map<String, ResolvedBox> = emptyMap(),
)

/**
 * A predicted scene graph — what the pipeline under test produced, already flattened to primitives
 * with coarse roles (the harness flattens/coarsens any nested structure before scoring).
 */
@Serializable
data class Prediction(
    val components: List<EvComponent>,
    val fileName: String,
)

/** Difficulty bucket (eval-framework §8): component-count band + sketch messiness level. */
@Serializable
data class Difficulty(val componentCount: Int, val jitterLevel: Int)

/** One runnable test case: the sketch the pipeline sees + the truth it must recover. */
@Serializable
data class TestCase(
    val id: String,
    val sketchPng: ByteArray,
    val source: Source,
    val difficulty: Difficulty,
    val groundTruth: GroundTruth,
)

@Serializable
enum class Source { SYNTHETIC }
