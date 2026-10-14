package by.ciszkin.draftsllman.library

import by.ciszkin.draftsllman.scene.SceneNode
import kotlinx.serialization.Serializable

/**
 * An immutable, reusable component template: a named sub-graph of [SceneNode]s. Stored in a
 * [ComponentLibrary]; referenced by [InstanceNode]s. The design doc's "Prototype" in the
 * Prototype + Override model (§3.3 / §9.6).
 */
@Serializable
data class Master(val id: String, val nodes: List<SceneNode>)
