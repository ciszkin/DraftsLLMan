package by.ciszkin.draftsllman.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoOpDetectorTest {
    @Test fun `fires after N consecutive unchanged hashes`() {
        val d = NoOpDetector(threshold = 3)
        assertFalse(d.record("h1"))
        assertFalse(d.record("h1"))
        assertTrue(d.record("h1"))
    }

    @Test fun `a change resets the count`() {
        val d = NoOpDetector(threshold = 3)
        d.record("h1"); d.record("h1")
        assertFalse(d.record("h2"))
        assertFalse(d.record("h2"))
        assertTrue(d.record("h2"))
    }
}
