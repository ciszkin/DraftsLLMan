package by.ciszkin.draftsllman.eval

import by.ciszkin.draftsllman.scene.ResolvedBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SemanticScorer contract: recovery of the SAME STRUCTURE (role+kind, and coarse relative layout)
 * must score as success even when the reference geometry differs — the user-facing framing of the
 * eval ("assess semantic equality, not dimensions"). IoU-based F1 deliberately stays a separate
 * diagnostic.
 */
class SemanticScorerTest {

    private val canvasBoxes = listOf(
        // sidebar, main, chart, badge — one of each role/kind
        EvComponent("sidebar", "container", PrimitiveKind.RECT, ResolvedBox(20.0, 20.0, 180.0, 500.0)),
        EvComponent("main", "container", PrimitiveKind.RECT, ResolvedBox(240.0, 20.0, 600.0, 500.0)),
        EvComponent("chart", "chart", PrimitiveKind.CIRCLE, ResolvedBox(320.0, 140.0, 160.0, 160.0)),
        EvComponent("badge", "badge", PrimitiveKind.POLYGON, ResolvedBox(700.0, 60.0, 60.0, 20.0)),
    )

    private fun truth() = canvasBoxes

    /** Same diagram, but shifted (+40,+30) and slightly scaled — geometry disagrees, semantics don't. */
    private fun shiftedPrediction() = canvasBoxes.mapIndexed { i, c ->
        c.copy(
            id = "p$i",
            box = ResolvedBox(c.box.x + 40, c.box.y + 30, c.box.width * 1.1, c.box.height * 1.1),
        )
    }

    @Test
    fun `shifted and rescaled equivalent diagram scores full semantic F1 and relations`() {
        val m = SemanticScorer.match(shiftedPrediction(), truth())
        assertEquals(4, m.tp)
        assertEquals(0, m.fp)
        assertEquals(0, m.fn)
        assertEquals(1.0, SemanticScorer.f1(m), 1e-9)
        assertEquals(1.0, SemanticScorer.relationConsistency(shiftedPrediction(), truth(), m), 1e-9)
    }

    @Test
    fun `a missing component and an invented one drop semantic F1`() {
        val pred = truth().drop(2).mapIndexed { i, c -> c.copy(id = "p$i") } + listOf(
            EvComponent("extra", "icon", PrimitiveKind.CIRCLE, ResolvedBox(30.0, 30.0, 20.0, 20.0)),
        )
        val m = SemanticScorer.match(pred, truth())
        assertEquals(2, m.tp) // sidebar + main recovered; chart + badge missed; icon invented
        assertEquals(1, m.fp)
        assertEquals(2, m.fn)
        val f1 = SemanticScorer.f1(m)
        assertTrue(f1 < 0.6 && f1 > 0.5, "expected ~0.57, got $f1")
    }

    @Test
    fun `a role mismatch never matches even at the same place`() {
        val pred = listOf(
            EvComponent("mislabeled", "icon", PrimitiveKind.CIRCLE, ResolvedBox(320.0, 140.0, 160.0, 160.0)),
        )
        val m = SemanticScorer.match(pred, truth())
        assertEquals(0, m.tp)
        assertEquals(0.0, SemanticScorer.f1(m), 1e-9)
    }

    @Test
    fun `moving the chart across the diagram breaks relation consistency but not recovery`() {
        // Same component SET, but the chart is placed far LEFT of the sidebar: the recovery is
        // still semantically complete (role+kind match), yet the relative layout disagrees — the
        // relation axis must catch exactly this, at a level geometry-IoU cannot distinguish.
        val b = canvasBoxes
        val moved = b.mapIndexed { i, c ->
            if (c.role == "chart") c.copy(id = "p$i", box = ResolvedBox(10.0, 10.0, 160.0, 160.0))
            else c.copy(id = "p$i")
        }
        val m = SemanticScorer.match(moved, truth())
        assertEquals(4, m.tp, "all components still recovered semantically")
        val rel = SemanticScorer.relationConsistency(moved, truth(), m)
        assertTrue(rel in 0.3..0.85, "cross-diagram placement must break relation consistency; got $rel")
    }
}