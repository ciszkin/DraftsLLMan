package by.ciszkin.draftsllman.agent

import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import ai.koog.agents.core.feature.handler.tool.ToolCallCompletedContext
import ai.koog.agents.features.eventHandler.feature.handleEvents
import by.ciszkin.draftsllman.engine.SceneGraphHasher
import by.ciszkin.draftsllman.engine.ToolExecutor
import kotlinx.coroutines.runBlocking

/**
 * Drives a [DraftsAgent] run and classifies how it terminated.
 *
 * Three termination sources (design §4 / circuit breakers):
 *  1. **done** — the model emitted a plain-text message (singleRunStrategy finishes on `onTextMessage`).
 *  2. **max-iterations** — Koog's native [AIAgentMaxNumberOfIterationsReachedException] when the loop
 *     exceeds `maxIterations`.
 *  3. **no-op** — the scene-graph hash stopped changing across tool calls. Observed via Koog's native
 *     [ToolCallCompletedContext] interceptor (see [installNoOpFeature]): after every tool executes we feed
 *     the current hash into [NoOpDetector]; when it fires we throw [NoOpReached], which propagates out of
 *     `agent.run` (the interceptor is called inline with no swallowing — same path max-iterations takes).
 *
 * Note: **no-op** and **max-iterations** are surfaced via Koog's agent-failure path — the throw propagates
 * out of `agent.run` and is caught at the session boundary in [run], which maps it to a [RunStatus]. These
 * are normal loop terminations (design §4 circuit breakers), not hard errors; observability must not alert.
 *
 * The no-op feature is exposed as [installNoOpFeature] because Koog features can only be installed at agent
 * construction; pass it to [DraftsAgent]'s `installFeatures`.
 */
class AgentRunner(
    private val executor: ToolExecutor,
    noOpThreshold: Int = 3,
) {
    private val noOp = NoOpDetector(noOpThreshold)

    /**
     * Koog feature installer: install on the agent being built (via [DraftsAgent]'s `installFeatures`).
     * After each tool executes, records the post-tool scene-graph hash and short-circuits the loop by
     * throwing [NoOpReached] once [NoOpDetector] trips. Uses Koog's `onToolCallCompleted` event, which
     * fires inline within the tool-execution node — the throw aborts the run exactly as max-iterations does.
     *
     * Note: the hash is recorded after EVERY tool call — including read-only `export_svg` /
     * `request_visual_critique` and rejected (rolled-back) calls, all of which leave the hash unchanged
     * and therefore count toward the no-op threshold.
     */
    val installNoOpFeature: GraphAIAgent.FeatureContext.() -> Unit = {
        handleEvents {
            onToolCallCompleted { _: ToolCallCompletedContext ->
                val hash = SceneGraphHasher.hash(executor.engine().current)
                if (noOp.record(hash)) throw NoOpReached(hash)
            }
        }
    }

    /**
     * Runs [agent] on [prompt] inside [runBlocking] and reports the final hash + how it ended.
     *
     * Resets the no-op detector first so a reused [AgentRunner] doesn't leak the previous run's hash history.
     */
    fun run(agent: GraphAIAgent<String, String>, prompt: String): RunOutcome = runBlocking {
        noOp.reset()
        try {
            agent.run(prompt)
            RunOutcome(SceneGraphHasher.hash(executor.engine().current), RunStatus.DONE)
        } catch (e: NoOpReached) {
            RunOutcome(e.hash, RunStatus.NO_OP)
        } catch (e: AIAgentMaxNumberOfIterationsReachedException) {
            RunOutcome(SceneGraphHasher.hash(executor.engine().current), RunStatus.MAX_ITERATIONS)
        } catch (e: ReasoningLoopReached) {
            RunOutcome(SceneGraphHasher.hash(executor.engine().current), RunStatus.MAX_ITERATIONS)
        }
    }
}

/**
 * Sentinel thrown mid-loop from the no-op interceptor to short-circuit `agent.run`. Not a cancellation.
 *
 * Note: [NoOpReached] (and Koog's [AIAgentMaxNumberOfIterationsReachedException]) transit Koog's normal
 * agent-failure path — the throw inside `onToolCallCompleted`/the planner propagates out of `agent.run`,
 * where [AgentRunner.run] catches it at the session boundary and maps it to a [RunStatus]. So a NO_OP or
 * MAX_ITERATIONS outcome is a **normal** termination of the loop, not a hard error. Future observability
 * or debugger features must treat these as expected control flow, not failures to alert on.
 */
private class NoOpReached(val hash: String) : RuntimeException("no-op: scene graph unchanged across tool calls")

/** How an agent run ended. */
enum class RunStatus { DONE, NO_OP, MAX_ITERATIONS }

/** Outcome of an agent run: the final scene-graph hash and how the loop ended. */
data class RunOutcome(val finalHash: String, val status: RunStatus)
