package by.ciszkin.draftsllman

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainKtTest {
    @Test fun `slug lowercases, joins words, strips junk, caps length`() {
        assertEquals("a-bicycle", promptSlug("A BICYCLE!"))
        assertEquals("hello-world", promptSlug("  hello,   world? "))
        // 60 space-separated 'a's slug to 'a-a-a-…' capped at 40 chars (truncation, not padding).
        assertEquals("a-a-a-a-a-a-a-a-a-a-a-a-a-a-a-a-a-a-a-a-", promptSlug("a ".repeat(60)))
        assertEquals(40, promptSlug("word ".repeat(30)).length)
    }

    @Test fun `slug never empty and filesystem-safe`() {
        assertEquals("drawing", promptSlug("!!! ???"))
        assertTrue(promptSlug("!!! ???").matches(Regex("[a-z0-9-]+")))
    }
}
