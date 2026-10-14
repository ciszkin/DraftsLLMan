package by.ciszkin.draftsllman.library

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * In-memory store of [Master] templates (the Component Library). Masters are keyed by id.
 * JSON persistence via [toJson] / [loadFromJson] (serializes the master list with polymorphic
 * SceneNode serialization, classDiscriminator "type").
 */
class ComponentLibrary {
    private val masters = mutableMapOf<String, Master>()

    /** Adds or replaces a master by its id. */
    fun put(master: Master) { masters[master.id] = master }

    /** Returns the master with [id], or null if absent. */
    fun get(id: String): Master? = masters[id]

    /** All master ids in the library. */
    fun ids(): Set<String> = masters.keys.toSet()

    /** Serializes the masters to a JSON string (a list of Master objects). */
    fun toJson(): String =
        json.encodeToString(ListSerializer(Master.serializer()), masters.values.toList())

    companion object {
        private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

        /** Deserializes a library from a JSON string produced by [toJson]. */
        fun loadFromJson(s: String): ComponentLibrary {
            val list = json.decodeFromString(ListSerializer(Master.serializer()), s)
            return ComponentLibrary().apply { list.forEach { put(it) } }
        }
    }
}
