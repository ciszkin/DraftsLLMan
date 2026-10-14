package by.ciszkin.draftsllman.coordination

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import by.ciszkin.draftsllman.agent.DEFAULT_LLM
import by.ciszkin.draftsllman.agent.Tracer
import by.ciszkin.draftsllman.architect.ArchitectRunner
import by.ciszkin.draftsllman.architect.SkeletonBuilder
import by.ciszkin.draftsllman.architect.toDetailBrief
import by.ciszkin.draftsllman.draftsman.DraftsmanRunner
import by.ciszkin.draftsllman.draftsman.connectionsFor
import by.ciszkin.draftsllman.engine.DraftsEngine
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.svg.SvgExporter
import by.ciszkin.draftsllman.solver.SafeHarbor
import by.ciszkin.draftsllman.solver.SafeHarborResult
import by.ciszkin.draftsllman.solver.SafeHarborTier
import by.ciszkin.draftsllman.solver.SolveResult

/**
 * The Slice 5 Coordinator: a three-phase Text-to-SVG flow.
 *
 *   Phase 1 — the [ArchitectRunner] decomposes the prompt into a connected skeleton (components +
 *             named anchors + inter-component constraints).
 *   Phase 2 — [SkeletonBuilder] turns the skeleton into one global SceneGraph and [DraftsEngine]
 *             solves it with `pinSizes=true` so inter-component `connect` constraints actually link
 *             components (the Slice 4 connection gain). On an `Unsatisfiable` OR degenerate
 *             (collapsed/zero-area) solve, [SafeHarbor] replaces the layout with a deterministic
 *             non-overlapping spread — so the exported drawing is never one blob. The Phase-3 detail
 *             pass then runs against this "effective" (solved or spread) graph.
 *   Phase 3 — a [DraftsmanRunner] injects per-component detail (`add_nodes`) on the solved skeleton:
 *             for each component it runs in an isolated engine SEEDED with that component at its SOLVED
 *             GLOBAL position, so detail nodes are computed in global coordinates and can be dropped
 *             directly into the solved graph. This is the fix for "offset-merge can't place detail" —
 *             no offset step, no namespacing, the skeleton's connect constraints stay intact, and detail
 *             ids are already unique via `${targetId}_${nodeName}_$i`.
 */
class Coordinator(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel = DEFAULT_LLM,
    private val canvas: CanvasSpec = CanvasSpec(500.0, 500.0),
    private val maxIterations: Int = 40,
    private val tracer: Tracer? = null,
) {
    fun run(prompt: String): CoordinatorOutcome {
        // Phase 1: Architect emits a connected skeleton (LLM-driven; LLM-free core is SkeletonBuilder).
        val architect = ArchitectRunner(promptExecutor, model, maxIterations, tracer = tracer).run(prompt, canvas)
        val skeleton = architect.skeleton
        val produced = skeleton != null
        if (produced) tracer?.brief("Phase 1: architect produced a skeleton (${skeleton.components.size} components)")
        else tracer?.brief("Phase 1: architect produced no skeleton")

        // Phase 2: build + global solve with pinSizes=true (connect constraints link components).
        if (skeleton == null) {
            return CoordinatorOutcome(SvgExporter.emit(SceneGraph(canvas = canvas)), 0, produced)
        }
        // Crash guard (2026-08-22): the validator deliberately accepts constraints on `group`
        // components (they ride the engine-diagnostic path in the emit gate), and the solver
        // throws IllegalStateException for groups — which escapes `solve` (not an
        // AddConstraintError). An engine failure here degrades to the Safe-Harbor branch below
        // (Unsatisfiable result) instead of crashing the run.
        val (solved, result) = try {
            DraftsEngine().solveInto(SkeletonBuilder.build(skeleton, canvas), pinSizes = true)
        } catch (e: Exception) {
            val msg = "Phase 2: skeleton failed to build/solve (${e::class.simpleName}: ${e.message}); Safe-Harbor applies"
            tracer?.brief(msg)
            System.err.println(msg)
            SceneGraph(canvas = canvas) to SolveResult.Unsatisfiable(e.message ?: "build/solve failure")
        }
        if (result is SolveResult.Unsatisfiable) {
            val msg = "Architect skeleton unsatisfiable: ${result.message}"
            tracer?.brief(msg)
            System.err.println(msg)
        } else {
            tracer?.brief("Phase 2: solved skeleton (${solved.nodes.size} nodes)")
        }

        // Safe-Harbor: on an unsatisfiable OR degenerate (collapsed/zero-area) Phase-2 solve, fall back to
        // a non-overlapping layout so the drawing is never one blob. connectHonoringSpread is tried first
        // (keeps `connect` topology where possible — e.g. a frame still spans the wheels); it internally
        // falls back to the deterministic disconnected row-spread. The result tier is traced honestly so
        // a row-spread fallback is not misreported as a connect-honoring success. Trigger is unchanged.
        val effective = when {
            result is SolveResult.Unsatisfiable || SafeHarbor.isDegenerate(solved) -> {
                val trigger = if (result is SolveResult.Unsatisfiable) "unsatisfiable solve" else "degenerate layout"
                // Belt-and-suspenders: tiers 1-2 catch their own solve failures and tier-3's
                // position-bake cannot solve, but if the harbor itself ever throws, an empty
                // drawing beats a crashed run.
                val harbor = try {
                    SafeHarbor.connectHonoringSpread(skeleton, canvas)
                } catch (e: Exception) {
                    val msg = "Safe-Harbor itself failed (${e::class.simpleName}: ${e.message}); emitting an empty drawing"
                    tracer?.brief(msg)
                    System.err.println(msg)
                    SafeHarborResult(SceneGraph(canvas = canvas), SafeHarborTier.ROW_SPREAD)
                }
                tracer?.brief("Safe-Harbor: $trigger — ${harbor.tier.label}")
                harbor.graph
            }
            else -> solved
        }

        // Phase 3: per-component Draftsman detail injection on the effective (solved or spread) skeleton.
        val draftsman = DraftsmanRunner(promptExecutor, model, maxIterations, tracer = tracer)
        var detailed = effective
        var detailCount = 0
        for (component in skeleton.components) {
            val solvedNode = effective.nodes[component.id] ?: continue
            val brief = component.toDetailBrief(solvedNode.box)
            val anchors = effective.anchorAliases[component.id] ?: emptyMap()
            // A single component's Draftsman failure (LLM/network) MUST NOT abort the whole drawing —
            // the solved skeleton is still valid without that component's detail. Log + continue, mirroring
            // Phase 2's Unsatisfiable handling. (Not Throwable — VM-level errors should still propagate.)
            val outcome = try {
                draftsman.runOnSkeleton(
                    brief, solvedNode, anchors = anchors, canvas = canvas,
                    connections = connectionsFor(component.id, skeleton.constraints),
                )
            } catch (e: Exception) {
                val msg = "Phase 3: ${component.id} Draftsman failed (${e::class.simpleName}: ${e.message}); skipping detail"
                tracer?.brief(msg); System.err.println(msg)
                null
            }
            val detail = outcome?.component
            if (detail != null) {
                for ((id, node) in detail.nodes) {
                    detailed = detailed.withNode(node).withRoot(id)
                }
                detailCount += detail.nodes.size
            }
            tracer?.brief("Phase 3: ${component.id} → ${outcome?.status ?: "FAILED"}" +
                (if (detail != null) " (+${detail.nodes.size} detail)" else ""))
        }
        tracer?.brief("Phase 3: injected $detailCount detail nodes across ${skeleton.components.size} components")

        return CoordinatorOutcome(SvgExporter.emit(detailed), skeleton.components.size, produced)
    }
}
