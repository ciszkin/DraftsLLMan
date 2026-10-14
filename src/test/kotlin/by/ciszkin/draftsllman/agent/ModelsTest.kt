package by.ciszkin.draftsllman.agent

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsTest {
    @Test fun `resolveConfig prefers the env value when present`() {
        assertEquals("from-env", resolveConfig("from-env", "from-props"))
    }

    @Test fun `resolveConfig falls back to the properties value when env is null`() {
        assertEquals("from-props", resolveConfig(null, "from-props"))
    }

    @Test fun `resolveConfig returns null when neither is set`() {
        assertNull(resolveConfig(null, null))
    }

    @Test fun `resolveConfig treats blank values as absent`() {
        assertEquals("from-props", resolveConfig("  ", "from-props"))
        assertNull(resolveConfig("  ", "  "))
    }

    @Test fun `DEFAULT_LLM_CAPABILITIES declares tools and image vision`() {
        assertTrue(DEFAULT_LLM_CAPABILITIES.contains(LLMCapability.Tools))
        assertTrue(DEFAULT_LLM_CAPABILITIES.contains(LLMCapability.Vision.Image))
    }

    @Test fun `llmForProvider maps openrouter to the OpenAI provider with the given model id`() {
        val llm = llmForProvider("openrouter", "openai/gpt-4o")
        assertEquals(LLMProvider.OpenAI, llm.provider)
        assertEquals("openai/gpt-4o", llm.id)
        assertTrue(
            llm.capabilities?.contains(LLMCapability.OpenAIEndpoint.Responses) == true,
            "openrouter model must declare an OpenAI endpoint capability so Koog can route an arbitrary model id")
    }

    @Test fun `llmForProvider maps opencode to the OpenAI provider with chat-completions capability`() {
        val llm = llmForProvider("opencode", "kimi-k3")
        assertEquals(LLMProvider.OpenAI, llm.provider)
        assertEquals("kimi-k3", llm.id)
        assertTrue(
            llm.capabilities?.contains(LLMCapability.Tools) == true
                && llm.capabilities?.contains(LLMCapability.Vision.Image) == true
                && llm.capabilities?.contains(LLMCapability.Completion) == true
                && llm.capabilities?.contains(LLMCapability.OpenAIEndpoint.Completions) == true,
            "opencode (OpenAI-compatible gateway) must declare the chat-completions endpoint capability")
    }

    @Test fun `defaultVisionLLM on opencode resolves the configured vision model`() {
        // local.properties selects provider=opencode + opencode.visionmodel; S1 must get a
        // VISION-capable model, not the text-only qwen3.8-flash.
        val llm = defaultVisionLLM()
        assertEquals(LLMProvider.OpenAI, llm.provider, "opencode vision model must use the OpenAI client")
        assertTrue(llm.id.isNotBlank())
    }

    @Test fun `llmForProvider maps the local provider to Ollama`() {
        val llm = llmForProvider("ollama", "bonsai")
        assertEquals(LLMProvider.Ollama, llm.provider)
        assertEquals("bonsai", llm.id)
    }

    @Test fun `llmForProvider treats an unknown provider as Ollama`() {
        val llm = llmForProvider("whatever", "x")
        assertEquals(LLMProvider.Ollama, llm.provider)
    }

    /** The local-context fix (2026-08-23): local runs on gemma4:12b and qwen3-vl:8b died
     *  reasoning-only with totalTokensCount=4096 every turn — Ollama's DEFAULT context window
     *  truncates generation mid-reasoning once the (now ~2.7K-token) Architect prompt is loaded.
     *  The executor must pin a configurable num_ctx (default 8192) via a Fixed strategy. */
    @Test fun `ollama context length defaults to 8192 and reads the numctx property`() {
        assertEquals(8192L, ollamaContextLength(numCtxProperty = null))
        assertEquals(16384L, ollamaContextLength(numCtxProperty = "16384"))
    }
}
