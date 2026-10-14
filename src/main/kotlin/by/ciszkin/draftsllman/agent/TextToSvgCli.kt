package by.ciszkin.draftsllman.agent

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import by.ciszkin.draftsllman.engine.ToolExecutor

/**
 * Live Text-to-SVG entry point: runs the DraftsLLMan agent against the default LLM on the remote Ollama
 * and returns the produced SVG for [prompt]. Endpoint/model are configurable (see Models.kt).
 *
 * Pass [promptExecutor] to inject a non-live executor (e.g. RecordingPromptExecutor for capture);
 * the default is [liveOllamaPromptExecutor].
 */
class TextToSvgCli(
    private val prompt: String,
    private val promptExecutor: PromptExecutor = liveOllamaPromptExecutor(),
    private val model: LLModel = DEFAULT_LLM,
    private val executor: ToolExecutor = ToolExecutor.default(),
) {
    /** Runs the agent and reports how the loop ended. Closes [promptExecutor] in a finally (svg() only needs executor). */
    fun run(): RunOutcome {
        try {
            val runner = AgentRunner(executor)
            val agent = DraftsAgent(executor, promptExecutor, model, installFeatures = runner.installNoOpFeature).agent()
            return runner.run(agent, prompt)
        } finally {
            promptExecutor.close()
        }
    }

    /** The SVG for the current scene graph (call after [run]). */
    fun svg(): String = executor.exportSvg()
}
