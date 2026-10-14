package by.ciszkin.draftsllman.sketch

import by.ciszkin.draftsllman.architect.EmitSkeleton
import by.ciszkin.draftsllman.scene.SceneGraph

/** How a [SketchRunner] run ended. */
enum class SketchStatus { SKELETON, NO_SKELETON, MAX_ITERATIONS }

/** Result of a [SketchRunner] run: the solved scene (or empty), the emitted skeleton, and the status. */
data class SketchOutcome(
    val graph: SceneGraph,
    val skeleton: EmitSkeleton?,
    val status: SketchStatus,
    /** false when the layout had to be produced by Safe-Harbor (unsatisfiable/degenerate solve). */
    val solvedDirectly: Boolean = false,
)
