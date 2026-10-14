package by.ciszkin.draftsllman.agent

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart

/**
 * Visual sliding window (design §9.2): returns [prompt] with every prior `Message.User` that carries an
 * image attachment converted to a text summary — the image [MessagePart.Attachment]s are dropped (any
 * existing text is kept) and a discard notice is appended. All other messages are unchanged. Pure.
 *
 * Called from [draftsVisionStrategy]'s send-tool-results node via `rewritePrompt`, immediately before
 * appending the new critique image — so the prompt never carries more than one critique image at a time.
 */
fun summarisePriorCritiqueImages(prompt: Prompt): Prompt {
    val rewritten = prompt.messages.map { msg ->
        if (msg is Message.User && msg.parts.any { it is MessagePart.Attachment && it.source is AttachmentSource.Image }) {
            Message.User(
                parts = msg.parts.filterNot { it is MessagePart.Attachment && it.source is AttachmentSource.Image } +
                    MessagePart.Text("[System: visual feedback image discarded to save context]"),
                metaInfo = msg.metaInfo,
                id = msg.id,
            )
        } else {
            msg
        }
    }
    return prompt.copy(messages = rewritten)
}
