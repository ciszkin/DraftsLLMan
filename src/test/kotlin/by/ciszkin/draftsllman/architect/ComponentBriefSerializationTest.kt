package by.ciszkin.draftsllman.architect

import by.ciszkin.draftsllman.scene.ResolvedBox
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

class ComponentBriefSerializationTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test fun `ComponentBrief round-trips through JSON`() {
        val brief = ComponentBrief("wheel", "wheel", "a spoked bicycle wheel", ResolvedBox(10.0, 10.0, 80.0, 80.0))
        val encoded = json.encodeToString(serializer(), brief)
        val decoded = json.decodeFromString(serializer<ComponentBrief>(), encoded)
        assertEquals(brief, decoded)
    }
}
