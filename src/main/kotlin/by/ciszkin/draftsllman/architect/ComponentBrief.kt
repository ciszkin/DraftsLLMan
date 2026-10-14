package by.ciszkin.draftsllman.architect

import by.ciszkin.draftsllman.scene.ResolvedBox
import kotlinx.serialization.Serializable

/**
 * One piece of a decomposition: what to draw ([description]), its [semanticRole], and the canvas
 * [region]. Retained for the Draftsman path (Slice 5 detail-addition); the Slice 4 Architect emits an
 * [EmitSkeleton] instead.
 */
@Serializable
data class ComponentBrief(
    val id: String,
    val semanticRole: String,
    val description: String,
    val region: ResolvedBox,
)
