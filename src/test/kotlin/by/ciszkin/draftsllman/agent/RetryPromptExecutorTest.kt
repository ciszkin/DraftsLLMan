package by.ciszkin.draftsllman.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import kotlinx.coroutines.runBlocking
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class RetryPromptExecutorTest {
    /**
     * Fake [PromptExecutor] that records calls and returns queued responses or throws queued
     * exceptions. Mirrors [ScriptedPromptExecutor]'s surface (execute + executeStreaming + moderate + close).
     */
    private class FakeExecutor : PromptExecutor() {
        val calls = mutableListOf<Pair<Prompt, List<ToolDescriptor>>>()
        private val responses: ArrayDeque<Any> = ArrayDeque() // Message.Assistant or Throwable

        fun enqueueResponse(message: Message.Assistant) {
            responses.addLast(message)
        }

        fun enqueueThrow(exception: Throwable) {
            responses.addLast(exception)
        }

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Message.Assistant {
            calls += (prompt to tools)
            val next = responses.pollFirst() ?: error("FakeExecutor queue empty")
            return when (next) {
                is Message.Assistant -> next
                is Throwable -> throw next
                else -> error("Unexpected type: ${next::class}")
            }
        }

        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ) = error("streaming not supported in FakeExecutor")

        override suspend fun moderate(prompt: Prompt, model: LLModel) =
            error("moderation not supported in FakeExecutor")

        override fun close() {}
    }

    @Test
    fun `recovers when the second attempt succeeds`() {
        runBlocking {
        val fake = FakeExecutor()
        fake.enqueueThrow(KoogHttpClientException("OpenAILLMClient", 429, null, "rate-limited upstream", null))
        fake.enqueueResponse(Message.Assistant("OK", metaInfo = ai.koog.prompt.message.ResponseMetaInfo.Empty))

        val retry = RetryPromptExecutor(fake, delaysMillis = longArrayOf(0, 0))
        val prompt: Prompt = ai.koog.prompt.Prompt.Empty
        val model: LLModel = LLModel(provider = LLMProvider.Ollama, id = "test")
        val tools: List<ToolDescriptor> = emptyList()

        val result = retry.execute(prompt, model, tools)

        assertEquals("OK", result.textContent())
        assertEquals(2, fake.calls.size, "should have called inner twice (initial + 1 retry)")
        }
    }

    @Test
    fun `gives up after max attempts and rethrows`() {
        runBlocking {
        val fake = FakeExecutor()
        val rateLimitEx = KoogHttpClientException("OpenAILLMClient", 429, null, "rate-limited upstream", null)
        repeat(3) { fake.enqueueThrow(rateLimitEx) }

        val retry = RetryPromptExecutor(fake, delaysMillis = longArrayOf(0, 0))
        val prompt: Prompt = ai.koog.prompt.Prompt.Empty
        val model: LLModel = LLModel(provider = LLMProvider.Ollama, id = "test")
        val tools: List<ToolDescriptor> = emptyList()

        try {
            retry.execute(prompt, model, tools)
            fail("should have thrown after max retries")
        } catch (e: KoogHttpClientException) {
            assertEquals(3, fake.calls.size, "should have called inner three times (initial + 2 retries)")
        }
        }
    }

    @Test
    fun `non-rate-limit errors propagate immediately without retry`() {
        runBlocking {
        val fake = FakeExecutor()
        val unauthorizedEx = IllegalStateException("Error from client: OpenAILLMClient 401 unauthorized")
        fake.enqueueThrow(unauthorizedEx)
        fake.enqueueResponse(Message.Assistant("OK", metaInfo = ai.koog.prompt.message.ResponseMetaInfo.Empty))

        val retry = RetryPromptExecutor(fake, delaysMillis = longArrayOf(0, 0))
        val prompt: Prompt = ai.koog.prompt.Prompt.Empty
        val model: LLModel = LLModel(provider = LLMProvider.Ollama, id = "test")
        val tools: List<ToolDescriptor> = emptyList()

        try {
            retry.execute(prompt, model, tools)
            fail("should have thrown non-rate-limit error immediately")
        } catch (e: IllegalStateException) {
            assertEquals(1, fake.calls.size, "should have called inner only once (no retry for non-rate-limit)")
        }
        }
    }

    @Test
    fun `is a rate-limit only when the message says so`() {
        assertTrue(RetryPromptExecutor.isRateLimit(KoogHttpClientException("c", 429, null, "rate-limited upstream", null)))
        assertTrue(RetryPromptExecutor.isRateLimit(KoogHttpClientException("c", null, null, "Too Many Requests", null)))
        assertTrue(RetryPromptExecutor.isRateLimit(KoogHttpClientException("c", null, null, "got 429 error", null)))

        assertTrue(!RetryPromptExecutor.isRateLimit(IllegalStateException("Error from client: OpenAILLMClient 401 unauthorized")))
        assertTrue(!RetryPromptExecutor.isRateLimit(IllegalStateException("500 server error")))
        assertTrue(!RetryPromptExecutor.isRateLimit(Exception("something else")))
    }
}
