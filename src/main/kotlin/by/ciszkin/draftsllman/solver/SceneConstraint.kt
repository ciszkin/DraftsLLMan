package by.ciszkin.draftsllman.solver

import by.ciszkin.draftsllman.scene.Anchor
import by.ciszkin.draftsllman.scene.AnchorAxis
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface SceneConstraint {
    val id: String
    fun references(): Set<String>

    @Serializable @SerialName("align")
    data class Align(
        override val id: String,
        val sourceId: String, val sourceAnchor: Anchor,
        val targetId: String, val targetAnchor: Anchor,
    ) : SceneConstraint {
        init {
            require(sourceAnchor.axis == targetAnchor.axis) {
                "Align requires same-axis anchors: $sourceAnchor vs $targetAnchor"
            }
        }
        override fun references() = setOf(sourceId, targetId)
    }

    @Serializable @SerialName("distance")
    data class Distance(
        override val id: String,
        val nodeA: String, val anchorA: Anchor,
        val nodeB: String, val anchorB: Anchor,
        val pixels: Double,
    ) : SceneConstraint {
        init {
            require(anchorA.axis != AnchorAxis.POINT && anchorB.axis != AnchorAxis.POINT) {
                "Distance requires X or Y anchors (axis-aligned gap), not point anchors"
            }
            require(anchorA.axis == anchorB.axis) { "Distance anchors must share an axis" }
        }
        override fun references() = setOf(nodeA, nodeB)
    }

    @Serializable @SerialName("connect")
    data class Connect(
        override val id: String,
        val nodeA: String, val anchorA: Anchor,
        val nodeB: String, val anchorB: Anchor,
    ) : SceneConstraint {
        init {
            require(anchorA.axis == AnchorAxis.POINT && anchorB.axis == AnchorAxis.POINT) {
                "Connect requires point anchors (TOP_LEFT/CENTER/...), got $anchorA / $anchorB"
            }
        }
        override fun references() = setOf(nodeA, nodeB)
    }

    /** Component-level canvas bound (spec 2026-08-17): the node's whole box stays inside
     *  [margin .. (1-margin)] of the canvas on BOTH axes. [margin] is a FRACTION of canvas
     *  width/height — the solver multiplies by the graph canvas (the LLM never sees pixels).
     *  Margin 0.5 allows a zero-width/height band; a pinned-size node becomes Unsatisfiable
     *  (REQUIRED size stay vs REQUIRED inequalities) and an unpinned node collapses to a point
     *  (caught downstream by the degeneracy guard). */
    @Serializable @SerialName("contain")
    data class Contain(
        override val id: String,
        val nodeId: String,
        val margin: Double,
    ) : SceneConstraint {
        init {
            require(margin in 0.0..0.5) { "contain margin must be in [0.0, 0.5], got $margin" }
        }
        override fun references() = setOf(nodeId)
    }

    /** Component-level size relation (spec 2026-08-20): [nodeA]'s (the SOURCE's) size is
     *  [ratio] × [nodeB]'s (the TARGET's) on BOTH axes (uniform scale — width-only would stretch
     *  wheels into ellipses). The TARGET is the reference: under pinSizes only the SOURCE's size
     *  stay demotes to STRONG (the strut rule, see ConstraintSolver), so the ratio scales the
     *  source onto the target's authoritative declared size. */
    @Serializable @SerialName("relative_size")
    data class RelativeSize(
        override val id: String,
        val nodeA: String,
        val nodeB: String,
        val ratio: Double,
    ) : SceneConstraint {
        init {
            require(ratio > 0.0 && ratio <= 5.0) { "relative_size ratio must be in (0.0, 5.0], got $ratio" }
        }
        override fun references() = setOf(nodeA, nodeB)
    }

    /** Component-level even spacing (spec 2026-08-20): consecutive gaps between [nodeIds] (in
     *  list order) are equal along [axis]. Positions only — sizes untouched. */
    @Serializable @SerialName("distribute")
    data class Distribute(
        override val id: String,
        val nodeIds: List<String>,
        val axis: AnchorAxis,
    ) : SceneConstraint {
        init {
            require(nodeIds.size >= 3) { "distribute requires at least 3 components, got ${nodeIds.size}" }
        }
        override fun references() = nodeIds.toSet()
    }
}

@Serializable
data class ConstraintSet(val constraints: List<SceneConstraint> = emptyList()) {
    fun add(c: SceneConstraint): ConstraintSet = copy(constraints = constraints + c)
    fun remove(id: String): ConstraintSet = copy(constraints = constraints.filterNot { it.id == id })
    fun referencing(nodeId: String): List<SceneConstraint> = constraints.filter { nodeId in it.references() }
    companion object { val EMPTY = ConstraintSet() }
}
