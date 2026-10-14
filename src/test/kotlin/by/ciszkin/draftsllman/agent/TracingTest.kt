package by.ciszkin.draftsllman.agent

import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import by.ciszkin.draftsllman.engine.ToolExecutor
import kotlin.test.Test
import kotlin.test.assertTrue

class TracingTest {
    private val model = LLModel(provider = LLMProvider.Ollama, id = "test")

    @Test fun `tracing logs LLM turns and tool calls`() {
        val captured = StringBuilder()
        val tracer = Tracer(briefSink = { captured.appendLine(it) })
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall("add_node", """{"id":"a","role":"x","kind":"circle","width":10.0,"height":10.0}""")
            enqueueDone()
        }
        val executor = ToolExecutor.default()
        executor.execute("""{"type":"set_view_box","width":100,"height":100}""")
        val runner = AgentRunner(executor)
        val agent = DraftsAgent(executor, scripted, model,
            installFeatures = {
                val noOp = runner.installNoOpFeature
                noOp()
                installTracing("TestAgent", tracer)
            }
        ).agent()
        runner.run(agent, "draw a circle")

        val log = captured.toString()
        assertTrue(log.contains("[TestAgent] LLM turn 1"), "expected an LLM turn log: $log")
        assertTrue(log.contains("add_node"), "expected tool call in log: $log")
        assertTrue(log.contains("[TestAgent] LLM turn 2"), "expected a second LLM turn: $log")
    }
}
