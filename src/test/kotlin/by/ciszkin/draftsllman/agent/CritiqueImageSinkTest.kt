package by.ciszkin.draftsllman.agent

import by.ciszkin.draftsllman.engine.CritiqueResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CritiqueImageSinkTest {
    private fun critique(global: String, roi: String? = null) = CritiqueResult(global, roi, null)

    @Test fun `poll returns null when nothing was offered`() {
        assertNull(CritiqueImageSink().poll())
    }

    @Test fun `poll returns the offered critique and clears the slot`() {
        val sink = CritiqueImageSink()
        sink.offer(critique("g1"))
        assertEquals(critique("g1"), sink.poll())
        assertNull(sink.poll(), "a second poll must be null — the slot was cleared")
    }

    @Test fun `a later offer overwrites an unconsumed earlier one`() {
        val sink = CritiqueImageSink()
        sink.offer(critique("g1"))
        sink.offer(critique("g2", "r2"))
        assertEquals(critique("g2", "r2"), sink.poll())
    }
}
