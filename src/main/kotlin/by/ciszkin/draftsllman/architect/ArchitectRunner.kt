package by.ciszkin.draftsllman.architect

import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import ai.koog.prompt.executor.model.PromptExecutor
import by.ciszkin.draftsllman.agent.ReasoningLoopReached
import by.ciszkin.draftsllman.agent.Tracer
import by.ciszkin.draftsllman.agent.installTracing
import ai.koog.prompt.llm.LLModel
import by.ciszkin.draftsllman.scene.CanvasSpec
import kotlinx.coroutines.runBlocking

/**
 * Runs the [Architect] on [prompt] and returns the emitted [EmitSkeleton] (or how it ended). Creates a
 * fresh [SkeletonSink] + [Architect] per run.
 */
class ArchitectRunner(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val maxIterations: Int = 20,
    private val tracer: Tracer? = null,
) {
    fun run(prompt: String, canvas: CanvasSpec = CanvasSpec(500.0, 500.0)): ArchitectOutcome {
        val sink = SkeletonSink()
        val architect = Architect(
            promptExecutor, model, canvas, sink, maxIterations,
            installFeatures = { tracer?.let { installTracing("Architect", it) } },
        )
        return runBlocking {
            try {
                architect.agent().run(prompt)
                val skeleton = sink.poll()
                if (skeleton != null) ArchitectOutcome(skeleton, ArchitectStatus.SKELETON)
                else ArchitectOutcome(null, ArchitectStatus.NO_SKELETON)
            } catch (e: AIAgentMaxNumberOfIterationsReachedException) {
                ArchitectOutcome(sink.poll(), ArchitectStatus.MAX_ITERATIONS)
            } catch (e: ReasoningLoopReached) {
                ArchitectOutcome(sink.poll(), ArchitectStatus.MAX_ITERATIONS)
            }
        }
    }
}

enum class ArchitectStatus { SKELETON, NO_SKELETON, MAX_ITERATIONS }

data class ArchitectOutcome(val skeleton: EmitSkeleton?, val status: ArchitectStatus)
