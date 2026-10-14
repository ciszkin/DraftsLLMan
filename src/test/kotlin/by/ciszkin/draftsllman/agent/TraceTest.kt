package by.ciszkin.draftsllman.agent

import ai.koog.prompt.Prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.MessagePart
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class TraceTest {
    private val model = LLModel(provider = LLMProvider.Ollama, id = "test")
    private val prompt = Prompt.Empty

    @Test fun `trace round-trips through JSON`() {
        val trace = Trace(listOf(
            Trace.Turn.ToolCall(tool = "add_node", args = """{"id":"a","kind":"circle"}"""),
            Trace.Turn.Text(content = "DONE"),
        ))
        val json = Trace.encodeToString(trace)
        val back = Trace.decodeFromString(json)
        assertEquals(trace, back)
    }

    @Test fun `enqueueTrace feeds a scripted executor in order`() {
        val trace = Trace(listOf(
            Trace.Turn.ToolCall(tool = "add_node", args = """{"id":"a","kind":"circle"}"""),
            Trace.Turn.Text(content = "DONE"),
        ))
        val ex = ScriptedPromptExecutor().apply { enqueueTrace(trace) }
        val first = runBlocking { ex.execute(prompt, model, emptyList()) }
        val second = runBlocking { ex.execute(prompt, model, emptyList()) }
        assertEquals("add_node", first.parts.filterIsInstance<MessagePart.Tool.Call>().single().tool)
        assertEquals("DONE", second.textContent())
    }
}
