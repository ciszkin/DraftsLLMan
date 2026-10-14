package by.ciszkin.draftsllman.agent

import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import by.ciszkin.draftsllman.engine.CritiqueResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CritiqueImageAttachmentsTest {
    @Test fun `global-only critique yields one png image attachment`() {
        val attachments = critiqueImageAttachments(CritiqueResult("Z3VsbGJhc2U=", null, null))
        assertEquals(1, attachments.size)
        val img = attachments.single()
        assertTrue(img is AttachmentSource.Image)
        assertEquals("png", img.format)
        assertEquals("image/png", img.mimeType)
        assertTrue(img.content is AttachmentContent.Binary.Base64)
    }

    @Test fun `global-plus-roi critique yields two png image attachments in order`() {
        val attachments = critiqueImageAttachments(CritiqueResult("Zw==", "cg==", null))
        assertEquals(2, attachments.size)
        assertTrue(attachments.all { it is AttachmentSource.Image && it.format == "png" })
    }
}
