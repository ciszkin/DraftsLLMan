package by.ciszkin.draftsllman.draftsman

import by.ciszkin.draftsllman.architect.SkeletonConstraint
import by.ciszkin.draftsllman.scene.Anchor
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.GroupNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.Style
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The coordinate-free Draftsman handoff (spec 2026-08-19, policy #2): qualitative size bands,
 *  declared anchors, semantic connections — provably NO absolute coordinates. */
class ComponentHandoffTest {
    private val canvas = CanvasSpec(500.0, 500.0)
    private val node = CircleNode("rear_wheel", "wheel", ResolvedBox(100.0, 100.0, 100.0, 100.0), Style())

    @Test fun `renders role type size band anchors and connections`() {
        val h = componentHandoff(
            "rear_wheel", "wheel", node, node.box, canvas,
            anchors = mapOf("hub" to Anchor.CENTER, "rim" to Anchor.RIGHT_CENTER),
            connections = listOf(ComponentConnection("connect", "rear_wheel:hub", "frame:rearAxle")),
        )
        assertTrue(h.contains("Component 'rear_wheel' (role: wheel, type: circle)"), "header:\n$h")
        assertTrue(h.contains("occupies ~20% of canvas width, ~20% of canvas height"), "size band:\n$h")
        assertTrue(h.contains("Declared anchors: hub (CENTER), rim (RIGHT_CENTER)"), "anchors:\n$h")
        assertTrue(h.contains("Connections: rear_wheel:hub ↔ frame:rearAxle (connect)"), "connections:\n$h")
    }

    /** §13-style sanitization assertion: the handoff must match NO coordinate pattern. */
    @Test fun `contains no absolute coordinates`() {
        val h = componentHandoff(
            "rear_wheel", "wheel", node, node.box, canvas,
            anchors = mapOf("hub" to Anchor.CENTER),
            connections = listOf(ComponentConnection("distance_ratio", "rear_wheel:CENTER_X", "front_wheel:CENTER_X")),
        )
        assertFalse(Regex("\\(-?\\d+, -?\\d+\\)").containsMatchIn(h), "pair coordinates leaked: $h")
        assertFalse(Regex("\\d+×\\d+").containsMatchIn(h), "W×H size leaked: $h")
    }

    @Test fun `bands the size to 5 percent steps`() {
        // 112/500 = 22.4% -> nearest 5% band -> 20%.
        val odd = CircleNode("x", "r", ResolvedBox(0.0, 0.0, 112.0, 100.0), Style())
        val h = componentHandoff("x", "r", odd, odd.box, canvas, emptyMap(), emptyList())
        assertTrue(h.contains("~20% of canvas width"), "band:\n$h")
    }

    @Test fun `connectionsFor keeps both directions skips contain and other components`() {
        val constraints = listOf(
            SkeletonConstraint("c1", "connect", "frame:rearAxle", "rear_wheel:hub"),
            SkeletonConstraint("c2", "align", "rear_wheel:CENTER_Y", "front_wheel:CENTER_Y"),
            SkeletonConstraint("c3", "distance_ratio", "rear_wheel:CENTER_X", "front_wheel:CENTER_X"),
            SkeletonConstraint("c4", "contain", "rear_wheel", "canvas", margin = 0.02),
            SkeletonConstraint("c5", "connect", "seat:bottomPt", "seat_tube:topJunc"),
        )
        assertEquals(
            listOf(
                ComponentConnection("connect", "rear_wheel:hub", "frame:rearAxle"),
                ComponentConnection("align", "rear_wheel:CENTER_Y", "front_wheel:CENTER_Y"),
                ComponentConnection("distance_ratio", "rear_wheel:CENTER_X", "front_wheel:CENTER_X"),
            ),
            connectionsFor("rear_wheel", constraints),
        )
    }

    @Test fun `connectionsFor drops self-relations`() {
        // Nothing upstream forbids a self-connect (the validator only checks each side names a
        // KNOWN component) — the handoff must not render "wheel:hub ↔ wheel:rim (connect)".
        val constraints = listOf(
            SkeletonConstraint("c1", "connect", "wheel:hub", "wheel:rim"),
            SkeletonConstraint("c2", "align", "wheel:CENTER_X", "other:CENTER_X"),
        )
        assertEquals(
            listOf(ComponentConnection("align", "wheel:CENTER_X", "other:CENTER_X")),
            connectionsFor("wheel", constraints),
        )
    }

    @Test fun `a zero-by-zero box renders not-yet-determined instead of 0 percent`() {
        // Groups solve to a 0×0 box (the solver holds no variables for them) — "~0%" would read
        // as an authoritative "draw almost nothing" to a weak model.
        val group = GroupNode("cluster", "assembly", childIds = emptyList())
        val h = componentHandoff("cluster", "assembly", group, group.box, canvas, emptyMap(), emptyList())
        assertTrue(h.contains("size not yet determined (structural component)"), "group header:\n$h")
        assertFalse(h.contains("~0%"), "must not render an authoritative 0% size:\n$h")
    }

    @Test fun `bands clamp to 100 percent and single-zero dims stay zero`() {
        val huge = CircleNode("x", "r", ResolvedBox(0.0, 0.0, 700.0, 100.0), Style()) // 140% of width
        val h = componentHandoff("x", "r", huge, huge.box, canvas, emptyMap(), emptyList())
        assertTrue(h.contains("~100% of canvas width"), "clamp:\n$h")
        val flat = CircleNode("y", "r", ResolvedBox(0.0, 0.0, 100.0, 0.0), Style()) // one dim zero is legitimate
        val h2 = componentHandoff("y", "r", flat, flat.box, canvas, emptyMap(), emptyList())
        assertTrue(h2.contains("~0% of canvas height"), "single-zero dim keeps its band:\n$h2")
    }

    @Test fun `relative_size renders as a connection`() {
        val constraints = listOf(
            SkeletonConstraint("rs", "relative_size", "front_wheel", "rear_wheel", ratio = 1.6),
        )
        assertEquals(
            listOf(ComponentConnection("relative_size", "rear_wheel", "front_wheel")),
            connectionsFor("rear_wheel", constraints),
        )
    }

    /** The Connections line shows full "id:anchor" refs while add_nodes anchor params take BARE
     *  names — a model echoing a ref gets "Unknown anchor 'rear:hub'". The line must say so. */
    @Test fun `the connections line says anchor arguments take the bare name`() {
        val h = componentHandoff(
            "rear_wheel", "wheel", node, node.box, canvas,
            anchors = mapOf("hub" to Anchor.CENTER),
            connections = listOf(ComponentConnection("connect", "rear_wheel:hub", "frame:rearAxle")),
        )
        assertTrue(h.contains("bare name"), "must clarify the add_nodes argument form:\n$h")
    }
}
