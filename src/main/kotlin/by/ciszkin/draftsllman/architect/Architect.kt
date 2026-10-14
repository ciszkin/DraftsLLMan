package by.ciszkin.draftsllman.architect

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.serialization.typeToken
import by.ciszkin.draftsllman.agent.reasoningTolerantStrategy
import by.ciszkin.draftsllman.engine.DraftsEngine
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.solver.SafeHarbor
import by.ciszkin.draftsllman.solver.SolveResult
import kotlin.math.abs
import kotlinx.serialization.serializer

/**
 * The text-only Architect (Zero-Coordinate Slice 4): decomposes a drawing prompt into a CONNECTED
 * SKELETON of primitive components + the inter-component constraints that link them. A Koog [AIAgent]
 * with a single tool, `emit_skeleton`, called once with the full skeleton. Uses
 * [reasoningTolerantStrategy] (no vision — the Architect renders nothing).
 */
class Architect(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val canvas: CanvasSpec,
    private val sink: SkeletonSink,
    private val maxIterations: Int = 20,
    private val installFeatures: GraphAIAgent.FeatureContext.() -> Unit = {},
) {


    fun agent(): GraphAIAgent<String, String> {
        val registry = ToolRegistry { tools(listOf(emitSkeletonTool(canvas, sink))) }
        return AIAgent(
            promptExecutor = promptExecutor,
            llmModel = model,
            strategy = reasoningTolerantStrategy(),
            toolRegistry = registry,
            systemPrompt = architectSystemPrompt(canvas),
            temperature = 0.0,
            maxIterations = maxIterations,
            installFeatures = installFeatures,
        )
    }
}

/**
 * Canvas aspect ratio snapped to the nearest canonical ratio (e.g. 800x600 -> "4:3", 797x599 -> "4:3"),
 * so the prompt conveys the canvas SHAPE without leaking its pixel dimensions. Snapping to a fixed
 * canonical set avoids leaking the raw (often coprime) dimensions that gcd-reduction would pass through.
 * (Zero-Coordinate policy: the LLM sees no pixels.)
 */
internal fun aspectRatio(canvas: CanvasSpec): String {
    val actual = canvas.width.toInt().coerceAtLeast(1).toDouble() /
        canvas.height.toInt().coerceAtLeast(1).toDouble()
    val canonical = listOf(
        1 to 1, 4 to 3, 3 to 4, 16 to 9, 9 to 16, 3 to 2, 2 to 3,
    ).minBy { (cw, ch) -> abs(actual - cw.toDouble() / ch.toDouble()) }
    return "${canonical.first}:${canonical.second}"
}

/** Total emit attempts allowed before the tool stashes the last skeleton regardless (1 + 2 retries, spec §4.4). */
internal const val MAX_EMIT_ATTEMPTS = 3

/**
 * The Architect's single tool: structurally validate the emitted skeleton, SOLVE it immediately
 * (the feasibility gate, spec §4.4), and either stash it with `ok` or return a diagnostic the LLM
 * can act on — `unsatisfiable:` (over-constrained; names the conflicting constraint when known) or
 * `degenerate:` (the solve collapsed components; names them) — WITHOUT stashing, so the Architect
 * revises and re-emit_skeletons within its own loop. After [MAX_EMIT_ATTEMPTS] failing attempts the
 * LAST skeleton is stashed with a terminal note and the Coordinator's Safe-Harbor applies. The
 * attempt counter lives in this factory's closure: one tool instance per Architect run.
 *
 * Engine exceptions (e.g., a constraint on a `group` component that the solver holds no variables for)
 * are converted to diagnostics and count as failed attempts — they do not propagate out of the tool.
 */
internal fun emitSkeletonTool(canvas: CanvasSpec, sink: SkeletonSink): SimpleTool<EmitSkeleton> {
    var attempts = 0
    return object : SimpleTool<EmitSkeleton>(
        argsType = typeToken<EmitSkeleton>(),
        name = serializer<EmitSkeleton>().descriptor.serialName,
        description = "Emit the full connected skeleton (components + inter-component constraints) that " +
            "decomposes the drawing into primitives + their constraints. The engine solves it immediately; " +
            "if the result reports unsatisfiable or degenerate, revise the skeleton and re-emit.",
    ) {
        override suspend fun execute(args: EmitSkeleton): String {
            val errors = validateSkeleton(args, canvas)
            if (errors.isNotEmpty())
                return "error: " + errors.joinToString("; ") { "${it.path}: ${it.message}" }
            attempts += 1

            // A failed attempt = solver Unsatisfiable, a degenerate solve, OR an engine exception
            // (e.g. a constraint on a structural group component the solver holds no variables for —
            // the validator accepts groups). Every failure consumes budget; on the last allowed
            // attempt the skeleton is stashed regardless, so the Coordinator's Safe-Harbor always
            // has something to lay out (the cap-stash guarantee).
            val diagnostic: String? = try {
                val graph = SkeletonBuilder.build(args, canvas)
                val (solved, result) = DraftsEngine().solveInto(graph, pinSizes = true)
                when {
                    result is SolveResult.Unsatisfiable -> {
                        val id = result.constraintId
                        if (id != null)
                            "unsatisfiable: constraint '$id' could not be satisfied — it likely conflicts " +
                                "with another constraint on the same anchors/components. Remove or relax it, then re-emit."
                        else
                            "unsatisfiable: the constraint set could not be satisfied — constraints likely " +
                                "conflict (over-constrained). Remove or relax one, then re-emit. (solver: ${result.message})"
                    }
                    else -> SafeHarbor.degeneracy(solved)?.let { d ->
                        "degenerate: components [${d.componentIds.joinToString()}] ${d.reason} — " +
                            "add a distance_ratio between them to separate, then re-emit."
                    }
                }
            } catch (e: Exception) {
                "error: the engine could not solve the skeleton (${e::class.simpleName}: ${e.message}); " +
                    "simplify or fix the constraint set, then re-emit."
            }

            if (diagnostic == null) {
                sink.offer(args)
                return "ok: ${args.components.size} components, ${args.constraints.size} constraints (solved)"
            }
            if (attempts >= MAX_EMIT_ATTEMPTS) {
                sink.offer(args)
                return "max-retries reached: engine will lay this out via Safe-Harbor. " +
                    "Do not emit again — reply DONE."
            }
            return diagnostic
        }
    }
}

/**
 * The Architect system prompt (shared with the vision-first SketchRunner — the SAME constraint
 * vocabulary + satisfiability rules, proven to yield satisfiable skeletons).
 */
fun architectSystemPrompt(canvas: CanvasSpec): String = """        You decompose a drawing request into a CONNECTED SKELETON of primitive components on a canvas
        with aspect ratio ${aspectRatio(canvas)} (width:height). You are NOT told the canvas pixel
        dimensions, so never emit absolute positions or pixel gaps — the engine derives every position
        from your constraints. Emit the WHOLE skeleton in ONE call to emit_skeleton.
        - components: each has a unique id, a role, a type (circle|rect|line|group), a size (width, height),
          and `anchors` mapping an alias name to a geometric anchor name. Geometric anchors:
          POINT anchors (for connect): CENTER, TOP_CENTER, BOTTOM_CENTER, LEFT_CENTER, RIGHT_CENTER,
          TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT.
          X-axis anchors (for align/distance_ratio): LEFT, RIGHT, CENTER_X.
          Y-axis anchors (for align/distance_ratio): TOP, BOTTOM, CENTER_Y.
          Alias names must NOT reuse a reserved geometric anchor word (CENTER, TOP, BOTTOM, LEFT,
          RIGHT, TOP_LEFT, ...) — the collision check is case-insensitive, so "center" is rejected
          too. Name anchors like "hubPt", "rearAxle", or "topJunc" instead.
          Keep the skeleton SMALL — 5-8 components for the macro-layout (wheels, frame tubes,
          seat, handlebars); visual detail (spokes, rivets, texture) is added later per component,
          never as extra skeleton components.
        - constraints link components: connect/align/distance_ratio link a source "id:anchor" to a
          target "id:anchor"; contain links a component to the canvas; relative_size links two
          components' sizes; distribute spaces a list evenly. Six kinds:
          `connect` — make two POINT anchors coincide (e.g. pin the frame's rear-axle corner to a wheel hub).
          `align` — drop two same-axis anchors onto one line (both LEFT/RIGHT/CENTER_X or both
          TOP/BOTTOM/CENTER_Y).
          `distance_ratio` — set an axis-aligned gap as a fraction of a canvas axis: `ratio` in (0.0, 1.0],
          `relative_to` = canvas_width or canvas_height. Axis rule: canvas_width needs X-axis anchors
          (CENTER_X/LEFT/RIGHT); canvas_height needs Y-axis anchors (CENTER_Y/TOP/BOTTOM).
          `contain` — keep a component's whole box inside the canvas (leaf components only): `source`
          = plain component id (no anchor), `target` = "canvas", `margin` = fraction of the canvas
          kept clear on each side [0.0, 0.5] — keep it small, like 0.02-0.05. Add `contain` for
          floating parts (seats, handlebars, labels) positioned relative to other components — it
          prevents them landing off-canvas — and for one or two structural parts (e.g. the wheels) so
          the whole drawing stays on-canvas. It composes freely with align/distance_ratio as long as
          the whole drawing (component sizes plus distance_ratio spans) fits within ~0.8 of each
          canvas axis.
          `relative_size` — make one component's size a multiple of another's: `source` and
          `target` are plain component ids, `ratio` in (0.0, 5.0] scales BOTH axes uniformly (e.g.
          a penny-farthing's front wheel: relative_size(source="front_wheel", target="rear_wheel",
          ratio=1.6)). The target is the size reference — declared sizes yield to the ratio.
          `distribute` — space a list of components evenly along an axis: `components` = at least 3
          ids in left-to-right (along="width") or top-to-bottom (along="height") order (e.g. picket
          fence slats). Only spacing is constrained — pair it with align/distance_ratio to pin the set.
        - Anchor refs must use a DECLARED alias from the component's anchors (e.g. "wheel:hub")
          or the exact geometric anchor name (e.g. "wheel:CENTER_X") — ad-hoc spellings like
          "cx" or "centerX" are rejected as unknown anchors.
        - WARNING: `connect` makes two anchors coincide — do NOT `connect` two wheel hubs to each other,
          and do not rely on `connect` alone to separate components (they will collapse to the same point).
          To separate components (e.g. the wheelbase between two wheels), use `distance_ratio` with X-axis
          anchors (`CENTER_X`/`LEFT`/`RIGHT`) for horizontal gaps and Y-axis (`CENTER_Y`/`TOP`/`BOTTOM`) for vertical.
        - MINIMUM SHAPE (every skeleton must have all three):
          (1) at least ONE `distance_ratio` span between the outermost components (e.g. the
          wheelbase) — without a span the drawing collapses toward the canvas corner;
          (2) `contain` on EVERY floating part (seat, handlebars, labels), not just structural
          ones — an uncontained part can legally solve off-canvas;
          (3) the connect-tree shape (already required above) linking every component to the structure.
        - SATISFIABILITY RULES (why solves fail — follow these or the engine will reject your skeleton):
          circles and rects are RIGID — their declared sizes never change (the one exception:
          a relative_size SOURCE's declared size yields to the ratio), so never add a
          `distance_ratio`/`align` that fights a rigid component's size. A `line` with BOTH ends
          `connect`ed is a STRETCHABLE STRUT: declare a rough size and set the exact span with ONE
          `distance_ratio` between the end components — the preferred pattern for any span (e.g. the
          wheelbase between the two wheels). NEVER close a loop of `connect`s (the #1 cause of
          `unsatisfiable`); keep `connect`s a TREE (each component connected once to the structure).
          When revising, REMOVE the offending constraint — restructuring the topology keeps the same
          hidden contradiction.
        - Example (a bicycle): two circle wheels with a "hub"->CENTER anchor; a line frame with
          "rearAxle"->BOTTOM_LEFT and "frontAxle"->BOTTOM_RIGHT; connect frame:rearAxle to rear_wheel:hub and
          frame:frontAxle to front_wheel:hub; then distance_ratio(source="rear_wheel:CENTER_X",
          target="front_wheel:CENTER_X", ratio=0.6, relative_to="canvas_width") to set the wheelbase.
          Then contain(source="rear_wheel", target="canvas", margin=0.02) (and one for the front wheel)
          keeps the bicycle on-canvas.
        The engine positions every component from the constraints; you give sizes + topology, never absolute positions.
        The engine immediately SOLVES your skeleton when you emit it, and the tool result tells you how it went:
        - `error:` — a structural mistake (bad anchor name, unknown component, bad ratio) or an engine failure. Fix and re-emit.
        - `unsatisfiable:` — your constraints over-constrain a component: e.g. an `align` and a `distance_ratio`
          on the same anchors pull them together AND apart, or a `connect` conflicts with a `distance_ratio`.
          Remove or relax the constraint named in the message and re-emit. (The named constraint is a hint —
          reason about it and its neighbors.)
        - `degenerate:` — the solve succeeded but the named components collapsed onto one point. Add a
          `distance_ratio` between the named components to separate them and re-emit.
        You may revise up to 2 times; after that the engine lays the drawing out via Safe-Harbor.
        Then reply with the plain text DONE.""".trimIndent()
