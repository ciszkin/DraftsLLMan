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
 * Proves the vision-injection node attaches the rendered critique images to the prompt the model sees
 * on the turn AFTER `request_visual_critique`. Drives the real [DraftsAgent] graph via
 * [ScriptedPromptExecutor] (no network); inspects the recorded prompt for image attachments.
 */
class VisionInjectionTest {
    private val model = LLModel(provider = LLMProvider.Ollama, id = "test")

    private fun newExecutor(): ToolExecutor = ToolExecutor.default()

    @Test fun `the prompt after a critique carries the rendered images`() {
        Library.load()
        val executor = newExecutor()
        executor.execute("""{"type":"set_view_box","width":200,"height":120}""")
        executor.execute("""{"type":"add_node","id":"a","role":"w","kind":"circle","width":20.0,"height":20.0}""")
        val promptExecutor = ScriptedPromptExecutor().apply {
            enqueueToolCall("request_visual_critique", """{"nodeId":"a","globalSize":64,"roiSize":64}""")
            enqueueDone()
        }

        val runner = AgentRunner(executor)
        val agent = DraftsAgent(executor, promptExecutor, model, installFeatures = runner.installNoOpFeature).agent()
        val outcome = runner.run(agent, "draw then critique")

        assertEquals(RunStatus.DONE, outcome.status)
        // prompts.last() is the turn AFTER the critique tool call — it must carry the image attachments.
        val images = promptExecutor.prompts.last().messages
            .flatMap { it.parts }
            .filterIsInstance<MessagePart.Attachment>()
            .map { it.source }
            .filterIsInstance<AttachmentSource.Image>()
        assertTrue(images.isNotEmpty(), "expected image attachments after critique, got none")
        assertEquals(2, images.size, "expected global + ROI image (nodeId set); got ${images.size}")
    }

    @Test fun `a critique without nodeId attaches only the global map`() {
        Library.load()
        val executor = newExecutor()
        executor.execute("""{"type":"set_view_box","width":100,"height":100}""")
        val promptExecutor = ScriptedPromptExecutor().apply {
            enqueueToolCall("request_visual_critique", """{"globalSize":64}""")
            enqueueDone()
        }

        val runner = AgentRunner(executor)
        val agent = DraftsAgent(executor, promptExecutor, model, installFeatures = runner.installNoOpFeature).agent()
        val outcome = runner.run(agent, "global critique only")

        assertEquals(RunStatus.DONE, outcome.status)
        val images = promptExecutor.prompts.last().messages
            .flatMap { it.parts }
            .filterIsInstance<MessagePart.Attachment>()
            .map { it.source }
            .filterIsInstance<AttachmentSource.Image>()
        assertEquals(1, images.size, "no nodeId -> ROI absent -> only the global map; got ${images.size}")
    }
}
