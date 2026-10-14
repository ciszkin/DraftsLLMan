package by.ciszkin.draftsllman.draftsman

import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneNode
import by.ciszkin.draftsllman.solver.ConstraintSet

/**
 * One component drawn by a Draftsman from a `ComponentBrief`: the LOCAL scene fragment (nodes + constraints,
 * in coordinates within the Brief's region-sized canvas) plus the [region] Slice H will offset it by to place
 * it on the global canvas. The planner->merge handoff for the §9.4 multi-agent hierarchy.
 */
data class Component(
    val briefId: String,
    val region: ResolvedBox,
    val nodes: Map<String, SceneNode>,
    val constraints: ConstraintSet,
)

/** How a Draftsman run ended. */
enum class DraftsmanStatus { DRAWN, EMPTY, NO_OP, MAX_ITERATIONS }

/** Outcome of a Draftsman run: the drawn [Component] (null if nothing was drawn) and how the loop ended. */
data class ComponentOutcome(val component: Component?, val status: DraftsmanStatus)
