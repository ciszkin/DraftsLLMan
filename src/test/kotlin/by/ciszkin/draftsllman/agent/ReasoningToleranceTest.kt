package by.ciszkin.draftsllman.agent

import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import by.ciszkin.draftsllman.engine.ToolExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the agent loop tolerates a Reasoning-only model response: the first turn is reasoning-only
 * (no tool call, no text) -> the strategy nudges -> the next turn is a real tool call -> the agent
 * recovers and finishes DONE.
 */
class ReasoningToleranceTest {
    private val model = LLModel(provider = LLMProvider.Ollama, id = "test")

    @Test fun `a reasoning-only first turn is nudged, then the agent recovers`() {
        val scripted = ScriptedPromptExecutor().apply {
            // turn 1: reasoning-only (no Tool.Call, no Text) -> triggers a nudge
            enqueueAssistant(Message.Assistant(MessagePart.Reasoning("let me think about this..."), metaInfo = ResponseMetaInfo.Empty))
            // turn 2 (after the nudge): a real tool call
            enqueueToolCall("add_node", """{"id":"a","role":"x","kind":"circle","width":10.0,"height":10.0}""")
            enqueueDone()
        }
        val executor = ToolExecutor.default()
        executor.execute("""{"type":"set_view_box","width":100,"height":100}""")

        val runner = AgentRunner(executor)
        val agent = DraftsAgent(executor, scripted, model, installFeatures = runner.installNoOpFeature).agent()
        val outcome = runner.run(agent, "draw a circle")

        assertEquals(RunStatus.DONE, outcome.status, "the agent should recover after the nudge")
        assertTrue(executor.engine().current.nodes.containsKey("a"), "the nudged tool call should have run")
    }

    @Test fun `a model that only reasons terminates MAX_ITERATIONS, not stuck`() {
        val scripted = ScriptedPromptExecutor().apply {
            repeat(8) {
                enqueueAssistant(Message.Assistant(MessagePart.Reasoning("still thinking..."), metaInfo = ResponseMetaInfo.Empty))
            }
        }
        val executor = ToolExecutor.default()
        executor.execute("""{"type":"set_view_box","width":100,"height":100}""")

        val runner = AgentRunner(executor)
        val agent = DraftsAgent(executor, scripted, model, installFeatures = runner.installNoOpFeature).agent()
        val outcome = runner.run(agent, "draw a circle")

        assertEquals(RunStatus.MAX_ITERATIONS, outcome.status, "a perpetually-reasoning model should end MAX_ITERATIONS, not throw")
    }
}
