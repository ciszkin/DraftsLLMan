package by.ciszkin.draftsllman.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import by.ciszkin.draftsllman.engine.ToolExecutor

/** The default DraftsAgent system prompt (the generic drawing workflow). The default for
 *  [DraftsAgent.systemPrompt]; the Draftsman overrides it with a Brief-scoped prompt. */
internal val DEFAULT_DRAFTS_SYSTEM_PROMPT = """
    You build SVG through tools, never raw coordinates. Workflow:
    1. set_view_box once.
    2. add_node for every primitive.
    3. add_constraint to relate them (align/distance/connect) — the solver computes exact positions.
    4. request_visual_critique only at milestones.
    5. Reply with the plain text DONE when the drawing is complete.
    Tool results are text-heavy, coordinate-free feedback: "ok: touched <id>" on success, "error: ..." on failure.
    You never receive raw coordinates — reason from ids, constraints, and visual critique only.
    On error, fix and retry. Do not re-issue a tool call whose result you just received unchanged.
""".trimIndent()

/**
 * Builds the DraftsLLMan Koog agent: a [singleRunStrategy] loop over the seven Drafts tools, driven by
 * [promptExecutor] against [model]. Tools delegate to the transactional [ToolExecutor] seam, so every
 * LLM->engine call is JSON-schema-validated and rolls back on failure (design §3).
 *
 * [systemPrompt] defaults to the generic drawing workflow ([DEFAULT_DRAFTS_SYSTEM_PROMPT]); the Draftsman
 * passes a Brief-scoped prompt. [installFeatures] lets the caller wire Koog features at construction time —
 * notably the no-op observation hook installed by [AgentRunner] (features cannot be added to an already-built agent).
 *
 * Live wiring (e.g. against the remote GPU Ollama):
 * ```
 * val agent = DraftsAgent(executor,
 *     PromptExecutorBuilder().ollama(defaultOllamaEndpoint()).build(),
 *     DEFAULT_LLM).agent()
 * ```
 * (To also wire the no-op circuit breaker, construct via DraftsAgent(..., installFeatures = runner.installNoOpFeature).agent() — see AgentRunner.)
 */
class DraftsAgent(
    private val executor: ToolExecutor,
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val systemPrompt: String = DEFAULT_DRAFTS_SYSTEM_PROMPT,
    private val maxIterations: Int = 20,
    private val installFeatures: GraphAIAgent.FeatureContext.() -> Unit = {},
) {
    fun agent(): GraphAIAgent<String, String> {
        val imageSink = CritiqueImageSink()
        val registry = ToolRegistry { tools(draftsTools(executor, imageSink)) }
        return AIAgent(
            promptExecutor = promptExecutor,
            llmModel = model,
            strategy = draftsVisionStrategy(imageSink),
            toolRegistry = registry,
            systemPrompt = systemPrompt,
            temperature = 0.0,
            maxIterations = maxIterations,
            installFeatures = installFeatures,
        )
    }
}
