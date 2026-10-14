package by.ciszkin.draftsllman.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Decorates a live [PromptExecutor] (the OllamaClient), delegating [execute] and recording each
 * assistant response as a [Trace.Turn] (tool-call or text). Call [trace] after a run to serialize.
 */
class RecordingPromptExecutor(private val delegate: PromptExecutor) : PromptExecutor() {

    private val recorded = mutableListOf<Trace.Turn>()

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        val response = delegate.execute(prompt, model, tools)
        recorded += response.toTurns()
        return response
    }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        flow { error("streaming not supported in RecordingPromptExecutor") }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        error("moderation not supported in RecordingPromptExecutor")

    override fun close() { delegate.close() }

    /** The captured turns so far. */
    fun trace(): Trace = Trace(recorded.toList())

    private fun Message.Assistant.toTurns(): List<Trace.Turn> {
        val calls = parts.filterIsInstance<MessagePart.Tool.Call>()
        return if (calls.isNotEmpty()) calls.map { Trace.Turn.ToolCall(it.tool, it.args) }
               else listOf(Trace.Turn.Text(textContent().ifEmpty { "<empty>" }))
    }
}
