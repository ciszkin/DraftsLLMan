package by.ciszkin.draftsllman.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.typeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Koog 1.1.1 DSL — CONFIRMED by this compiling + passing spike.
 * This block is the authority for the agent-loop tasks (4-5). All snippets are real and exercised below.
 *
 * 1) ToolRegistry — built via the `ToolRegistry { ... }` constructor that takes a
 *    `ToolRegistryBuilder.() -> Unit`. Register one tool with `tool(ToolBase)`, many
 *    with `tools(List<ToolBase>)`:
 *        val registry = ToolRegistry { tool(MyTool()) }
 *
 * 2) SimpleTool<TArgs> — abstract class; pass `argsType`, `name`, `description` to the
 *    super constructor and override `suspend fun execute(args: TArgs): String`. The args
 *    TypeToken comes from the reified `typeToken<T>()` (ai.koog.serialization):
 *        class MyTool : SimpleTool<MyArgs>(
 *            argsType = typeToken<MyArgs>(), name = "myTool", description = "..."
 *        ) { override suspend fun execute(args: MyArgs): String { ... } }
 *    TArgs MUST be @Serializable (drives JSON-schema generation + call-arg deserialization).
 *
 * 3) AIAgent — top-level factory in ai.koog.agents.core.agent (AIAgentFactoryKt). Plain
 *    named params, NO trailing config lambda required; the default strategy is
 *    `singleRunStrategy()`: LLM call --(onToolCalls)--> execute tool --> send result --> repeat
 *    --(onTextMessage)--> finish. Max-iterations is the built-in `maxIterations` param:
 *        val agent: GraphAIAgent<String, String> = AIAgent(
 *            promptExecutor = exec,
 *            llmModel = LLModel(LLMProvider.Ollama, "id"),
 *            toolRegistry = registry,
 *            systemPrompt = "...",
 *            maxIterations = 5
 *        )
 *    Run via the suspend `agent.run(input)` (wrap in runBlocking).
 *    SURPRISE: the `promptExecutor` parameter is typed as the ABSTRACT CLASS `PromptExecutor`,
 *    not the `PromptExecutorAPI` interface. `PromptExecutor : PromptExecutorAPI`, so subclass
 *    `PromptExecutor()` and override execute / executeStreaming / moderate (+ close()).
 *
 * 4) Message.Assistant — data class with convenience constructors (parts are typed):
 *     - plain text: Message.Assistant(content = "DONE", metaInfo = ResponseMetaInfo.Empty)
 *     - tool call : Message.Assistant(
 *                       MessagePart.Tool.Call(id = "c1", tool = "myTool",
 *                           args = JsonObject(mapOf("k" to JsonPrimitive("v")))),
 *                       metaInfo = ResponseMetaInfo.Empty
 *                   )
 *    `MessagePart.Tool.Call` has a constructor taking `args: JsonObject` (serialised to the
 *    JSON string the framework decodes back into the tool's TArgs). Use ResponseMetaInfo.Empty
 *    for scripted/offline messages.
 *
 * 5) KoogHttpClient + base URL — `KoogHttpClient` is an INTERFACE (AutoCloseable), built via
 *    `KoogHttpClient.Factory.create(clientName, baseUrl = "http://host:port", ...)`. The factory
 *    is resolved automatically (HttpClientFactoryResolver, ktor-backed on the JVM classpath).
 *    The idiomatic Ollama path hides this behind a JVM convenience constructor taking the base
 *    URL directly (it resolves the factory + embeds the URL into the client):
 *        val client = OllamaClient(baseUrl = defaultOllamaEndpoint())
 *    (OllamaClient.DEFAULT_BASE_URL == "http://localhost:11434".)
 *    Not instantiated here — no network needed for this spike.
 */
class KoogSpikeTest {

    /** Args for the spike tool. Default lets an empty `{}` call still deserialize. */
    @Serializable
    data class SpikeArgs(val note: String = "none")

    /** A SimpleTool that records a side effect and returns a fixed string. */
    private class SpikeTool(private val log: MutableList<String>) :
        SimpleTool<SpikeArgs>(
            argsType = typeToken<SpikeArgs>(),
            name = "spike",
            description = "Appends its 'note' arg to a side-effect log and returns \"ran\"."
        ) {
        override suspend fun execute(args: SpikeArgs): String {
            log.add(args.note)
            return "ran"
        }
    }

    @Test
    fun `simple tool is dispatched end-to-end through a Koog AIAgent`() {
        val log = mutableListOf<String>()
        var llmCalls = 0

        // Scripted executor: call #1 -> assistant tool-call; call #2 -> plain DONE text.
        // The other two PromptExecutorAPI methods are unused by singleRunStrategy.
        val executor = object : PromptExecutor() {
            override suspend fun execute(
                prompt: Prompt,
                model: LLModel,
                tools: List<ToolDescriptor>
            ): Message.Assistant {
                llmCalls += 1
                return if (llmCalls == 1) {
                    Message.Assistant(
                        MessagePart.Tool.Call(
                            id = "call-1",
                            tool = "spike",
                            args = JsonObject(mapOf("note" to JsonPrimitive("ping")))
                        ),
                        metaInfo = ResponseMetaInfo.Empty
                    )
                } else {
                    Message.Assistant(content = "DONE", metaInfo = ResponseMetaInfo.Empty)
                }
            }

            override fun executeStreaming(
                prompt: Prompt,
                model: LLModel,
                tools: List<ToolDescriptor>
            ): Flow<StreamFrame> = flow { error("executeStreaming not used") }

            override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
                error("moderate not used")

            override fun close() { /* no-op */ }
        }

        val agent: GraphAIAgent<String, String> = AIAgent(
            promptExecutor = executor,
            llmModel = LLModel(provider = LLMProvider.Ollama, id = "spike-model"),
            toolRegistry = ToolRegistry { tool(SpikeTool(log)) },
            systemPrompt = "You are a spike test harness.",
            maxIterations = 5
        )

        val output = runBlocking { agent.run("go") }

        // The loop dispatched the tool (proved by the side effect + the deserialized arg).
        assertTrue(log.isNotEmpty(), "tool never ran; log=$log output=$output llmCalls=$llmCalls")
        assertEquals(listOf("ping"), log, "tool arg was not deserialized end-to-end")
    }
}
