package by.ciszkin.draftsllman.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay

/**
 * Wraps a [PromptExecutor] and retries a rate-limited request (OpenRouter 429s: "rate-limited
 * upstream" / "429" / "Too Many Requests") with backoff — the 2026-08-14 live run lost 2 of 6
 * Draftsman detail runs to exactly this. Retries only the CURRENT call (same prompt); any other
 * error propagates immediately. [delaysMillis] is injectable for instant tests; production default
 * 5s then 15s (3 attempts total — matching the pipeline's fail-soft philosophy: a component that
 * still fails after that is skipped by the caller, not fatal).
 */
class RetryPromptExecutor(
    private val inner: PromptExecutor,
    private val delaysMillis: LongArray = longArrayOf(5_000, 15_000),
) : PromptExecutor() {

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant {
        var attempt = 0
        while (true) {
            try {
                return inner.execute(prompt, model, tools)
            } catch (e: Exception) {
                if (attempt >= delaysMillis.size || !isRateLimit(e)) throw e
                delay(delaysMillis[attempt])
                attempt += 1
            }
        }
    }

    // Forward streaming without retry (streaming retries need re-subscription semantics, out of scope).
    // The pipeline only uses the suspend execute path; verified by grepping src/main for streaming usage.
    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> = inner.executeStreaming(prompt, model, tools)

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        inner.moderate(prompt, model)

    override fun close() = inner.close()

    companion object {
        /**
         * Message-based: the live 429s appear as generic HTTP-client exceptions carrying
         * OpenRouter's "rate-limited upstream"/"429"/"Too Many Requests" text.
         */
        fun isRateLimit(e: Throwable): Boolean {
            val m = (e.message ?: "") + " " + (e.cause?.message ?: "")
            return m.contains("rate-limit", ignoreCase = true) ||
                m.contains("rate limit", ignoreCase = true) ||
                m.contains("429") ||
                m.contains("Too Many Requests", ignoreCase = true)
        }
    }
}
