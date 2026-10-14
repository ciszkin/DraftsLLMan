package by.ciszkin.draftsllman.sketch

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import by.ciszkin.draftsllman.agent.ReasoningLoopReached
import by.ciszkin.draftsllman.agent.SketchSink
import by.ciszkin.draftsllman.agent.Tracer
import by.ciszkin.draftsllman.agent.installTracing
import by.ciszkin.draftsllman.agent.sketchVisionStrategy
import by.ciszkin.draftsllman.architect.SkeletonBuilder
import by.ciszkin.draftsllman.architect.emitSkeletonTool
import by.ciszkin.draftsllman.engine.DraftsEngine
import by.ciszkin.draftsllman.engine.ToolExecutor
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.solver.SafeHarbor
import by.ciszkin.draftsllman.solver.SolveResult
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

/**
 * S1 — the vision-first sketch entry point. Runs a vision-enabled agent whose ONLY stimulus is a
 * raster sketch (hand-drawn wireframe): [sketchVisionStrategy] attaches the PNG to the first LLM
 * message, the agent can call the reused `emit_skeleton` tool (feasibility-gated + solved, exactly
 * like the Architect), iterates on `unsatisfiable:`/`degenerate:` diagnostics, and terminates with a
 * plain-text DONE once a skeleton is stashed. The returned [SketchOutcome.graph] is the SOLVED scene
 * (Phase-1 + Phase-2 of the text pipeline, but image-driven): `SkeletonBuilder.build` +
 * `DraftsEngine.solveInto(pinSizes=true)`, degrading to the Safe-Harbor spread when the solve is
 * unsatisfiable/degenerate — never a failed run, never a collapsed blob.
 */
class SketchRunner(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val maxIterations: Int = 20,
    private val tracer: Tracer? = null,
) {
    /**
     * Recover the clean structure of [sketchPng] on [canvas]. [seed] is accepted for future
     * determinism hooks; the agent loop is inherently stochastic.
     */
    fun run(sketchPng: ByteArray, canvas: CanvasSpec = CanvasSpec.DEFAULT, seed: Long = 0): SketchOutcome {
        val executor = ToolExecutor.default()
        executor.execute("""{"type":"set_view_box","width":${canvas.width},"height":${canvas.height}}""")

        val sketchSink = SketchSink().apply { offer(sketchPng) }
        val skeletonSink = by.ciszkin.draftsllman.architect.SkeletonSink()
        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel = model,
            strategy = sketchVisionStrategy(sketchSink),
            toolRegistry = ToolRegistry { tools(listOf(emitSkeletonTool(canvas, skeletonSink))) },
            systemPrompt = SKETCH_RECOVERY_PROMPT(canvas),
            temperature = 0.0,
            maxIterations = maxIterations,
            installFeatures = { tracer?.let { installTracing("Sketch", it) } },
        )

        val (skeleton, status) = runBlocking {
            data class Run(val skeleton: by.ciszkin.draftsllman.architect.EmitSkeleton?, val status: SketchStatus)
            fun capture(): Run {
                val s = skeletonSink.poll()
                return Run(s, if (s != null) SketchStatus.SKELETON else SketchStatus.NO_SKELETON)
            }
            try {
                agent.run(RECOVERY_TASK)
                capture()
            } catch (e: AIAgentMaxNumberOfIterationsReachedException) {
                capture().let { Run(it.skeleton, SketchStatus.MAX_ITERATIONS) }
            } catch (e: ReasoningLoopReached) {
                capture().let { Run(it.skeleton, SketchStatus.MAX_ITERATIONS) }
            }
        }

        return when (status) {
            SketchStatus.NO_SKELETON, SketchStatus.MAX_ITERATIONS ->
                SketchOutcome(SceneGraph(canvas = canvas), null, status)
            SketchStatus.SKELETON -> {
                val (graph, direct) = solve(skeleton!!, canvas)
                SketchOutcome(graph, skeleton, status, solvedDirectly = direct)
            }
        }
    }

    /** Phase-2 solve with the crash-proof Safe-Harbor degradation (mirrors [by.ciszkin.draftsllman.coordination.Coordinator]). */
    private fun solve(skeleton: by.ciszkin.draftsllman.architect.EmitSkeleton, canvas: CanvasSpec): Pair<SceneGraph, Boolean> {
        val (solved, result) = try {
            DraftsEngine().solveInto(SkeletonBuilder.build(skeleton, canvas), pinSizes = true)
        } catch (e: Exception) {
            SceneGraph(canvas = canvas) to SolveResult.Unsatisfiable(e.message ?: "build/solve failure")
        }
        if (result is SolveResult.Unsatisfiable || SafeHarbor.isDegenerate(solved)) {
            return runCatching { SafeHarbor.connectHonoringSpread(skeleton, canvas).graph }
                .getOrElse { SceneGraph(canvas = canvas) } to false
        }
        return solved to true
    }

    companion object {
        const val RECOVERY_TASK = "Recover the clean UI wireframe structure of the attached sketch."
    }
}
