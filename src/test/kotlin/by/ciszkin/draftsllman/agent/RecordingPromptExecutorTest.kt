package by.ciszkin.draftsllman.agent

import ai.koog.prompt.Prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordingPromptExecutorTest {
    private val model = LLModel(provider = LLMProvider.Ollama, id = "test")
    private val prompt = Prompt.Empty

    @Test fun `records each assistant turn as a trace`() {
        // A fake "live" executor returning a scripted sequence.
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall("add_node", """{"id":"a","kind":"circle"}""")
            enqueueDone("DONE")
        }
        val recorder = RecordingPromptExecutor(scripted)

        runBlocking { recorder.execute(prompt, model, emptyList()) }
        runBlocking { recorder.execute(prompt, model, emptyList()) }

        val trace = recorder.trace()
        assertEquals(2, trace.turns.size)
        val first = trace.turns[0] as Trace.Turn.ToolCall
        assertEquals("add_node", first.tool)
        assertEquals("DONE", (trace.turns[1] as Trace.Turn.Text).content)
    }

    @Test fun `a single message with multiple tool calls records one turn per call`() {
        // Koog executes every Tool.Call in a message, so a batched turn must not collapse to one turn.
        val multiCall = Message.Assistant(
            listOf(
                MessagePart.Tool.Call(id = "c0", tool = "add_node", args = JsonObject(mapOf("id" to JsonPrimitive("a")))),
                MessagePart.Tool.Call(id = "c1", tool = "add_node", args = JsonObject(mapOf("id" to JsonPrimitive("b"))))
            ),
            metaInfo = ResponseMetaInfo.Empty
        )
        val scripted = ScriptedPromptExecutor().apply { enqueueAssistant(multiCall) }
        val recorder = RecordingPromptExecutor(scripted)

        runBlocking { recorder.execute(prompt, model, emptyList()) }

        val turns = recorder.trace().turns.map { it as Trace.Turn.ToolCall }
        assertEquals(listOf("add_node", "add_node"), turns.map { it.tool })
        assertEquals(listOf("""{"id":"a"}""", """{"id":"b"}"""), turns.map { it.args })
    }
}
