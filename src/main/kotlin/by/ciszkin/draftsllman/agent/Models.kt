package by.ciszkin.draftsllman.agent

import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.PromptExecutorBuilder
import ai.koog.prompt.executor.ollama.client.ContextWindowStrategy
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import java.io.File
import java.util.Properties

/**
 * Externalized Ollama configuration. The endpoint and model are NOT hardcoded in source — they come
 * from env vars (DRAFTS_OLLAMA_ENDPOINT / DRAFTS_OLLAMA_MODEL) overriding a gitignored `local.properties`
 * (`ollama.endpoint` / `ollama.model`). See `local.properties.example`. Required only for live runs;
 * deterministic tests use the scripted executor and never read this.
 */

/** Resolves a config value: the env override wins, else the properties value, else null. Pure. */
fun resolveConfig(envValue: String?, propertyValue: String?): String? =
    envValue?.takeIf { it.isNotBlank() } ?: propertyValue?.takeIf { it.isNotBlank() }

/** Reads [key] from `local.properties` in the working directory (null if absent/unreadable). */
private fun localProperty(key: String): String? = runCatching {
    File("local.properties").takeIf { it.isFile }?.inputStream()?.use {
        Properties().apply { load(it) }.getProperty(key)
    }
}.getOrNull()

/** Ollama base URL: DRAFTS_OLLAMA_ENDPOINT env, else `ollama.endpoint` in local.properties, else error. */
fun defaultOllamaEndpoint(): String =
    resolveConfig(System.getenv("DRAFTS_OLLAMA_ENDPOINT"), localProperty("ollama.endpoint"))
        ?: error("Ollama endpoint not configured: set ollama.endpoint in local.properties or the DRAFTS_OLLAMA_ENDPOINT env var")

/** Ollama model id: DRAFTS_OLLAMA_MODEL env, else `ollama.model` in local.properties, else error. */
fun defaultOllamaModel(): String =
    resolveConfig(System.getenv("DRAFTS_OLLAMA_MODEL"), localProperty("ollama.model"))
        ?: error("Ollama model not configured: set ollama.model in local.properties or the DRAFTS_OLLAMA_MODEL env var")

/**
 * A live Ollama [PromptExecutor] at [defaultOllamaEndpoint] — the canonical wiring [TextToSvgCli] uses by
 * default and capture/replay tests reuse, so the capture path can't drift from production.
 *
 * Ollama wiring note: `DraftsAgent.promptExecutor` is the abstract class [PromptExecutor], while
 * `OllamaClient` is an `LLMClient` (the HTTP layer), not a [PromptExecutor]. Koog bridges the two via
 * [PromptExecutorBuilder.ollama], which builds an `OllamaClient` at [defaultOllamaEndpoint] and wraps it
 * in a [PromptExecutor].
 */
/**
 * The Ollama context window (`num_ctx`) the executor pins per request: `DRAFTS_OLLAMA_NUM_CTX` env
 * else `ollama.numctx` in local.properties, else 8192. Ollama's DEFAULT (~4K) truncates generation
 * mid-reasoning once the ~2.7K-token Architect prompt is loaded (live finding 2026-08-23: local
 * gemma4:12b and qwen3-vl:8b both died reasoning-only at exactly totalTokensCount=4096). Fixed, not
 * FitPrompt: a varying num_ctx makes Ollama reload the model on every change, and FitPrompt's
 * chunk rounding would reproduce the 4096 wall for our prompt size.
 */
fun ollamaContextLength(numCtxProperty: String? = localProperty("ollama.numctx")): Long =
    resolveConfig(System.getenv("DRAFTS_OLLAMA_NUM_CTX"), numCtxProperty)?.toLongOrNull() ?: 8192L

fun liveOllamaPromptExecutor(): PromptExecutor =
    PromptExecutorBuilder().ollama(
        baseUrl = defaultOllamaEndpoint(),
        contextWindowStrategy = ContextWindowStrategy.Companion.Fixed(ollamaContextLength()),
    ).build()

// region OpenRouter (cloud, OpenAI-compatible) — the alternative to local Ollama.

/**
 * The selected LLM provider: "openrouter" (cloud, OpenAI-compatible) or "ollama" (local). Resolved from
 * the `DRAFTS_PROVIDER` env var overriding `drafts.provider` in local.properties; defaults to "ollama".
 */
fun selectedProvider(): String =
    resolveConfig(System.getenv("DRAFTS_PROVIDER"), localProperty("drafts.provider"))?.lowercase() ?: "ollama"

/**
 * OpenRouter base URL (OpenAI-compatible). Defaults to `https://openrouter.ai/api` so the default
 * `v1/chat/completions` path resolves to the OpenRouter Chat Completions endpoint. Override via
 * `DRAFTS_OPENROUTER_ENDPOINT` or `openrouter.endpoint`.
 */
fun defaultOpenRouterEndpoint(): String =
    resolveConfig(System.getenv("DRAFTS_OPENROUTER_ENDPOINT"), localProperty("openrouter.endpoint"))
        ?: "https://openrouter.ai/api"

/** OpenRouter API key: `DRAFTS_OPENROUTER_API_KEY` env, else `openrouter.apikey` in local.properties, else error. */
fun defaultOpenRouterApiKey(): String =
    resolveConfig(System.getenv("DRAFTS_OPENROUTER_API_KEY"), localProperty("openrouter.apikey"))
        ?: error("OpenRouter API key not configured: set openrouter.apikey in local.properties or the DRAFTS_OPENROUTER_API_KEY env var")

/** OpenRouter model id (e.g. "openai/gpt-4o", "anthropic/claude-3.5-sonnet"): env override else local.properties, else error. */
fun defaultOpenRouterModel(): String =
    resolveConfig(System.getenv("DRAFTS_OPENROUTER_MODEL"), localProperty("openrouter.model"))
        ?: error("OpenRouter model not configured: set openrouter.model in local.properties or the DRAFTS_OPENROUTER_MODEL env var")

/**
 * A live OpenRouter [PromptExecutor] — an OpenAI-compatible client pointed at [defaultOpenRouterEndpoint].
 * OpenRouter exposes the OpenAI Chat Completions API, so the standard OpenAI client works unchanged; the
 * requested model id (e.g. "openai/gpt-4o") selects the underlying provider. Used when [selectedProvider]
 * is "openrouter".
 */
fun openRouterPromptExecutor(): PromptExecutor =
    PromptExecutorBuilder().openAI(
        apiKey = defaultOpenRouterApiKey(),
        settings = OpenAIClientSettings(
            baseUrl = defaultOpenRouterEndpoint(),
            timeoutConfig = tightTimeouts(),
        ),
    ).build()

/**
 * The live [PromptExecutor] for the [selectedProvider] — OpenRouter when configured, else the local Ollama
 * executor. [by.ciszkin.draftsllman.Main] wires this into the Coordinator.
 */
fun livePromptExecutor(): PromptExecutor = when (selectedProvider()) {
    "openrouter" -> openRouterPromptExecutor()
    "opencode" -> openCodePromptExecutor()
    else -> liveOllamaPromptExecutor()
}

// endregion

// region OpenCode Go (cloud, OpenAI-compatible) — the stable subscription alternative to OpenRouter.

/** Shared tight timeouts for cloud OpenAI-compatible clients: a stalled upstream surfaces in ~4 min,
 *  not koog's 15-min default (live finding 2026-09-02). Connect/request/socket in millis. */
internal fun tightTimeouts() = ConnectionTimeoutConfig(
    requestTimeoutMillis = 240_000,
    connectTimeoutMillis = 30_000,
    socketTimeoutMillis = 180_000,
)

/** OpenCode Go base URL (Anthropic-messages-compatible): `DRAFTS_OPENCODE_ENDPOINT` env, else
 *  `opencode.endpoint`, else the zen/go gateway. The Anthropic client posts to `{base}/v1/messages`,
 *  so this base already includes the gateway's nonstandard prefix. */
fun defaultOpenCodeEndpoint(): String =
    resolveConfig(System.getenv("DRAFTS_OPENCODE_ENDPOINT"), localProperty("opencode.endpoint"))
        ?: "https://opencode.ai/zen/go"

/** OpenCode API key: `DRAFTS_OPENCODE_API_KEY` env, else `opencode.apikey` in local.properties, else error. */
fun defaultOpenCodeApiKey(): String =
    resolveConfig(System.getenv("DRAFTS_OPENCODE_API_KEY"), localProperty("opencode.apikey"))
        ?: error("OpenCode API key not configured: set opencode.apikey in local.properties or the DRAFTS_OPENCODE_API_KEY env var")

/** OpenCode model id (e.g. "qwen/qwen3.8-flash"): env override else local.properties, else error. */
fun defaultOpenCodeModel(): String =
    resolveConfig(System.getenv("DRAFTS_OPENCODE_MODEL"), localProperty("opencode.model"))
        ?: error("OpenCode model not configured: set opencode.model in local.properties or the DRAFTS_OPENCODE_MODEL env var")

/**
 * OpenCode Go VISION model (e.g. "kimi-k3" — the gateway's qwen3.8-flash is TEXT-ONLY; a vision
 *  request to it returns the gateway's HTML page, live finding 2026-09-03): `DRAFTS_OPENCODE_VISION_MODEL`
 *  env, else `opencode.visionmodel` in local.properties, else error.
 */
fun defaultOpenCodeVisionModel(): String =
    resolveConfig(System.getenv("DRAFTS_OPENCODE_VISION_MODEL"), localProperty("opencode.visionmodel"))
        ?: error("OpenCode vision model not configured: set opencode.visionmodel in local.properties or the DRAFTS_OPENCODE_VISION_MODEL env var")

/**
 * The vision-capable LLM for the S1 sketch entry: on OpenCode Go the input needs the vision model
 * (kimi-k3 — qwen3.8-flash is text-only there); on OpenRouter/Ollama the default model already
 * supports images, so [DEFAULT_LLM] is used.
 */
fun defaultVisionLLM(): LLModel =
    if (selectedProvider() == "opencode") llmForProvider("opencode", defaultOpenCodeVisionModel())
    else DEFAULT_LLM

/**
 * A live OpenCode Go [PromptExecutor] — koog's ANTHROPIC client pointed at the zen/go gateway * A live OpenCode Go [PromptExecutor] — koog's ANTHROPIC client pointed at the zen/go gateway
 * (live check 2026-09-03: the gateway speaks the Anthropic Messages API with `x-api-key` auth, model
 * `qwen3.8-flash`, `{base}/v1/messages`). The client resolves every model through its version map
 * (`settings.modelVersionsMap`, keyed by [LLModel] — structural equality, hence the [llmForProvider]
 * construction here) and throws "Unsupported model" otherwise; the opencode model maps to its plain
 * id, which the gateway accepts directly.
 */
fun openCodePromptExecutor(): PromptExecutor =
    PromptExecutorBuilder().openAI(
        apiKey = defaultOpenCodeApiKey(),
        settings = OpenAIClientSettings(
            baseUrl = defaultOpenCodeEndpoint(),
            timeoutConfig = tightTimeouts(),
        ),
    ).build()

// endregion

/** Capabilities declared for [DEFAULT_LLM]: tool-calling + image vision (the critique loop attaches images). */
val DEFAULT_LLM_CAPABILITIES: List<LLMCapability> = listOf(LLMCapability.Tools, LLMCapability.Vision.Image)

/**
 * Pure: the [LLModel] for a provider, given the resolved model id. OpenRouter models use the OpenAI
 * provider — the OpenAI-compatible client declares `provider = OpenAI`, and Koog's executor routes a
 * request to the client whose provider matches the model's — carrying the OpenRouter model id (e.g.
 * "openai/gpt-4o") to select the underlying cloud model. Ollama uses the Ollama provider. Unknown → Ollama.
 *
 * OpenRouter/OpenCode models also declare an OpenAI endpoint capability: the model id isn't in Koog's
 * known-model registry, so Koog can't decide Chat Completions vs Responses without it and throws.
 * OpenRouter speaks the Responses API; OpenCode Go is an OpenAI-compatible chat-completions gateway
 * (provider OpenAI with the Completions endpoint capability).
 */
fun llmForProvider(provider: String, model: String): LLModel =
    when (provider.lowercase()) {
        "openrouter" -> LLModel(
            provider = LLMProvider.OpenAI,
            id = model,
            capabilities = DEFAULT_LLM_CAPABILITIES + LLMCapability.OpenAIEndpoint.Responses,
        )
        "opencode" -> LLModel(
            provider = LLMProvider.OpenAI,
            id = model,
            // The OpenAI client requires Completion for chat-completions-endpoint models (the
            // Responses-endpoint models like OpenRouter get it via their endpoint path).
            capabilities = DEFAULT_LLM_CAPABILITIES + LLMCapability.Completion +
                LLMCapability.OpenAIEndpoint.Completions,
        )
        else -> LLModel(provider = LLMProvider.Ollama, id = model, capabilities = DEFAULT_LLM_CAPABILITIES)
    }

/**
 * The default LLM for DraftsLLMan agents — provider-aware (OpenRouter when [selectedProvider] is
 * "openrouter", else Ollama). Lazy so config is resolved only when a live run needs it; only the SELECTED
 * provider's model id is resolved (so an unset OpenRouter key doesn't break the default Ollama path).
 */
val DEFAULT_LLM: LLModel by lazy {
    val provider = selectedProvider()
    val model = when (provider) {
        "openrouter" -> defaultOpenRouterModel()
        "opencode" -> defaultOpenCodeModel()
        else -> defaultOllamaModel()
    }
    llmForProvider(provider, model)
}
