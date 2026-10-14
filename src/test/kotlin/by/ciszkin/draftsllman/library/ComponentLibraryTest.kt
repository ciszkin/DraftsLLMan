package by.ciszkin.draftsllman.library

import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.Style
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ComponentLibraryTest {
    private fun wheel() = Master("wheel", listOf(
        CircleNode("rim", "rim", ResolvedBox(0.0, 0.0, 10.0, 10.0), Style()),
    ))

    @Test fun `put and get by id`() {
        val lib = ComponentLibrary()
        lib.put(wheel())
        val master = lib.get("wheel")
        assertNotNull(master)
        assertEquals("wheel", master.id)
        assertNull(lib.get("nonexistent"))
    }

    @Test fun `JSON round-trip preserves masters`() {
        val lib = ComponentLibrary().apply {
            put(wheel())
            put(Master("frame", listOf()))
        }
        val json = lib.toJson()
        val loaded = ComponentLibrary.loadFromJson(json)
        assertNotNull(loaded.get("wheel"))
        assertEquals("wheel", loaded.get("wheel")!!.id)
        assertNotNull(loaded.get("frame"))
    }
}
