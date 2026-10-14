package by.ciszkin.draftsllman.agent

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CritiqueImagePrunerTest {
    private fun img(b64: String): AttachmentSource.Image =
        AttachmentSource.Image(content = AttachmentContent.Binary.Base64(b64), format = "png")

    private fun imageCount(prompt: Prompt): Int = prompt.messages
        .flatMap { it.parts }
        .filterIsInstance<MessagePart.Attachment>()
        .count { it.source is AttachmentSource.Image }

    private fun discardCount(prompt: Prompt): Int = prompt.messages
        .flatMap { it.parts }
        .filterIsInstance<MessagePart.Text>()
        .count { it.text.contains("discarded") }

    @Test fun `strips image parts from image-bearing user messages, keeps text, adds a discard notice`() {
        val prompt = Prompt.build(id = "test") {
            user { text("critique one"); image(img("a")) }
            user { text("plain user message, no image") }
            user { text("critique two"); image(img("b")); image(img("c")) }
        }

        val result = summarisePriorCritiqueImages(prompt)

        assertEquals(0, imageCount(result), "all image attachments should be stripped")
        assertEquals(2, discardCount(result), "one discard notice per stripped image-bearing message")
        assertEquals(3, result.messages.size, "message count unchanged")
        assertTrue(
            result.messages.flatMap { it.parts }.filterIsInstance<MessagePart.Text>().any { it.text == "critique one" },
            "the original text of a stripped message must be retained"
        )
    }

    @Test fun `leaves prompts without images untouched`() {
        val prompt = Prompt.build(id = "test") {
            user { text("no images here") }
            user { text("still no images") }
        }
        val result = summarisePriorCritiqueImages(prompt)

        assertEquals(0, imageCount(result))
        assertEquals(0, discardCount(result), "no discard notices when there were no images to strip")
        assertFalse(result.messages.any { it.parts.any { p -> p is MessagePart.Text && p.text.contains("discarded") } })
    }
}
