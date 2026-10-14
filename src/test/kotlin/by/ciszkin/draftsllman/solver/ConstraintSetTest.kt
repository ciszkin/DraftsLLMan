package by.ciszkin.draftsllman.solver

import by.ciszkin.draftsllman.scene.Anchor
import by.ciszkin.draftsllman.scene.AnchorAxis
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConstraintSetTest {
    @Test fun `add and remove by id`() {
        val c = SceneConstraint.Align("c1", "a", Anchor.CENTER_X, "b", Anchor.CENTER_X)
        val set = ConstraintSet.EMPTY.add(c)
        assertEquals(listOf(c), set.constraints)
        assertEquals(emptyList<SceneConstraint>(), set.remove("c1").constraints)
    }

    @Test fun `referencing returns constraints touching a node`() {
        val align = SceneConstraint.Align("c1", "a", Anchor.LEFT, "b", Anchor.LEFT)
        val set = ConstraintSet.EMPTY.add(align)
        assertEquals(listOf(align), set.referencing("a"))
        assertTrue(set.referencing("zzz").isEmpty())
    }

    @Test fun `Align rejects mismatched anchor axes`() {
        assertFailsWith<IllegalArgumentException> {
            SceneConstraint.Align("c1", "a", Anchor.LEFT, "b", Anchor.BOTTOM) // X vs Y
        }
    }

    @Test fun `Distance rejects point anchors`() {
        assertFailsWith<IllegalArgumentException> {
            SceneConstraint.Distance("c1", "a", Anchor.CENTER, "b", Anchor.CENTER, 10.0)
        }
    }

    @Test fun `Connect requires point anchors`() {
        assertFailsWith<IllegalArgumentException> {
            SceneConstraint.Connect("c1", "a", Anchor.LEFT, "b", Anchor.LEFT)
        }
    }

    @Test fun `Contain rejects an out-of-range margin`() {
        assertFailsWith<IllegalArgumentException> { SceneConstraint.Contain("k1", "a", margin = 0.7) }
        assertFailsWith<IllegalArgumentException> { SceneConstraint.Contain("k1", "a", margin = -0.1) }
    }

    @Test fun `Contain references its node`() {
        val c = SceneConstraint.Contain("k1", "seat", margin = 0.02)
        assertEquals(setOf("seat"), c.references())
    }

    @Test fun `Contain round-trips through serialization`() {
        val set = ConstraintSet.EMPTY.add(SceneConstraint.Contain("k1", "seat", margin = 0.02))
        val json = Json.encodeToString(ConstraintSet.serializer(), set)
        assertEquals(set, Json.decodeFromString(ConstraintSet.serializer(), json))
    }

    @Test fun `RelativeSize rejects an out-of-range ratio`() {
        assertFailsWith<IllegalArgumentException> { SceneConstraint.RelativeSize("r", "a", "b", ratio = 5.5) }
        assertFailsWith<IllegalArgumentException> { SceneConstraint.RelativeSize("r", "a", "b", ratio = 0.0) }
    }

    @Test fun `Distribute rejects fewer than three components`() {
        assertFailsWith<IllegalArgumentException> {
            SceneConstraint.Distribute("d", listOf("a", "b"), AnchorAxis.X)
        }
    }

    @Test fun `RelativeSize references both members`() {
        assertEquals(setOf("front", "rear"), SceneConstraint.RelativeSize("r", "front", "rear", ratio = 1.6).references())
    }

    @Test fun `RelativeSize and Distribute round-trip through serialization`() {
        val set = ConstraintSet.EMPTY
            .add(SceneConstraint.RelativeSize("r", "front", "rear", ratio = 1.6))
            .add(SceneConstraint.Distribute("d", listOf("a", "b", "c"), AnchorAxis.X))
        val json = Json.encodeToString(ConstraintSet.serializer(), set)
        assertEquals(set, Json.decodeFromString(ConstraintSet.serializer(), json))
    }
}
