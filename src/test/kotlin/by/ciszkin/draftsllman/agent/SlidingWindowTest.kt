package by.ciszkin.draftsllman.agent

import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import by.ciszkin.draftsllman.engine.ToolExecutor
import org.jetbrains.skiko.Library
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the visual sliding window: across two `request_visual_critique` calls, only the LATEST critique
 * image survives in the prompt sent to the model; the earlier one is replaced by a discard notice.
 * Drives the real DraftsAgent graph via ScriptedPromptExecutor (no network).
 */
class SlidingWindowTest {
    private val model = LLModel(provider = LLMProvider.Ollama, id = "test")

    private fun newExecutor(): ToolExecutor = ToolExecutor.default()

    @Test fun `only the latest critique image survives across two critiques`() {
        Library.load()
        val executor = newExecutor()
        executor.execute("""{"type":"set_view_box","width":200,"height":120}""")
        executor.execute("""{"type":"add_node","id":"a","role":"w","kind":"circle","width":20.0,"height":20.0}""")
        val promptExecutor = ScriptedPromptExecutor().apply {
            enqueueToolCall("request_visual_critique", """{"globalSize":64}""")
            enqueueToolCall("request_visual_critique", """{"globalSize":64}""")
            enqueueDone()
        }

        val runner = AgentRunner(executor)
        val agent = DraftsAgent(executor, promptExecutor, model, installFeatures = runner.installNoOpFeature).agent()
        val outcome = runner.run(agent, "draw then critique twice")

        assertEquals(RunStatus.DONE, outcome.status)
        val parts = promptExecutor.prompts.last().messages.flatMap { it.parts }
        val images = parts.filterIsInstance<MessagePart.Attachment>()
            .map { it.source }
            .filterIsInstance<AttachmentSource.Image>()
        assertEquals(1, images.size, "expected exactly one critique image (the latest); got ${images.size}")
        assertTrue(
            parts.filterIsInstance<MessagePart.Text>().any { it.text.contains("discarded") },
            "expected a prior-critique discard notice in the prompt"
        )
    }
}
