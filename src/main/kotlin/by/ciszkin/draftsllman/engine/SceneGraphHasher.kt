package by.ciszkin.draftsllman.engine

import by.ciszkin.draftsllman.scene.SceneGraph
import kotlinx.serialization.json.Json
import java.security.MessageDigest

object SceneGraphHasher {
    private val json = Json { encodeDefaults = true; prettyPrint = false; classDiscriminator = "type" }
    fun hash(graph: SceneGraph): String {
        val canonical = json.encodeToString(SceneGraph.serializer(), graph)
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
