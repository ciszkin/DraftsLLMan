package by.ciszkin.draftsllman.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.ArrayDeque

/**
 * Deterministic test [PromptExecutor] holding a FIFO queue of scripted [Message.Assistant]
 * responses. No network, no real model: every agent test drives the loop through this harness.
 *
 * Enqueue the assistant turns in call order, then let the agent (or a direct [execute] call)
 * drain them. Pops in FIFO order; throws if the queue is empty when [execute] is called.
 *
 * Every [execute] call is recorded in [prompts] so flow-back assertions can inspect what the
 * model was shown (e.g. critique feedback appended after a tool result).
 */
class ScriptedPromptExecutor : PromptExecutor() {
    private val queue: ArrayDeque<Message.Assistant> = ArrayDeque()

    /** Every [Prompt] handed to [execute], in call order, for flow-back assertions. */
    val prompts = mutableListOf<Prompt>()

    /**
     * Enqueue an assistant tool-call message for [toolName] with [argsJson] (parsed to a [JsonObject],
     * matching the form Koog uses to deserialize back into the tool's `TArgs`).
     */
    fun enqueueToolCall(toolName: String, argsJson: String = "{}") {
        val args = Json.parseToJsonElement(argsJson)
            .let { it as? JsonObject ?: error("argsJson must be a JSON object, got: $argsJson") }
        val id = "call-${queue.size}"
        val message = Message.Assistant(
            MessagePart.Tool.Call(id = id, tool = toolName, args = args),
            metaInfo = ResponseMetaInfo.Empty
        )
        queue.addLast(message)
    }

    /**
     * Enqueue a plain assistant text message. Terminates the basic agent loop
     * (`singleRunStrategy` ends on a text-only assistant message).
     */
    fun enqueueDone(text: String = "DONE") {
        queue.addLast(Message.Assistant(content = text, metaInfo = ResponseMetaInfo.Empty))
    }

    /**
     * Enqueue a pre-built assistant message — e.g. one carrying multiple [MessagePart.Tool.Call] parts,
     * to exercise batched tool turns.
     */
    fun enqueueAssistant(message: Message.Assistant) {
        queue.addLast(message)
    }

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant {
        prompts += prompt
        return queue.pollFirst() ?: error("ScriptedPromptExecutor queue is empty")
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> = flow { error("streaming is not supported in ScriptedPromptExecutor") }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        error("moderation is not supported in ScriptedPromptExecutor")

    override fun close() { /* no-op */ }
}
