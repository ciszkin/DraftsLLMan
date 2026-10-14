package by.ciszkin.draftsllman.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.MessagePart
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScriptedPromptExecutorTest {

    private val model = LLModel(provider = LLMProvider.Ollama, id = "test")
    private val prompt = Prompt.Empty
    private val tools = emptyList<ToolDescriptor>()

    @Test
    fun `enqueued done messages are returned in FIFO order`() {
        val executor = ScriptedPromptExecutor().apply {
            enqueueDone("first")
            enqueueDone("second")
        }

        val first = runBlocking { executor.execute(prompt, model, tools) }
        val second = runBlocking { executor.execute(prompt, model, tools) }

        assertEquals("first", first.textContent())
        assertEquals("second", second.textContent())
    }

    @Test
    fun `enqueueToolCall produces a tool-call assistant message`() {
        val executor = ScriptedPromptExecutor().apply {
            enqueueToolCall("addNode", """{"type":"circle"}""")
        }

        val message = runBlocking { executor.execute(prompt, model, tools) }

        val calls = message.parts.filterIsInstance<MessagePart.Tool.Call>()
        assertEquals(1, calls.size, "expected exactly one tool-call part; parts=${message.parts}")
        assertEquals("addNode", calls.single().tool)
    }

    @Test
    fun `execute throws when the queue is empty`() {
        val executor = ScriptedPromptExecutor()

        assertFailsWith<IllegalStateException> {
            runBlocking { executor.execute(prompt, model, tools) }
        }
    }

    @Test
    fun `enqueueToolCall defaults to an empty args object`() {
        val executor = ScriptedPromptExecutor().apply { enqueueToolCall("ping") }

        val message = runBlocking { executor.execute(prompt, model, tools) }
        val call = message.parts.filterIsInstance<MessagePart.Tool.Call>().single()

        assertTrue(call.argsJson.isEmpty(), "expected empty default args; got ${call.argsJson}")
    }

    @Test
    fun `mixed enqueue order is preserved`() {
        val executor = ScriptedPromptExecutor().apply {
            enqueueToolCall("start", """{"i":0}""")
            enqueueDone("all done")
        }

        val first = runBlocking { executor.execute(prompt, model, tools) }
        val second = runBlocking { executor.execute(prompt, model, tools) }

        assertTrue(first.parts.any { it is MessagePart.Tool.Call }, "first should be a tool call")
        assertEquals("all done", second.textContent())
    }
}
