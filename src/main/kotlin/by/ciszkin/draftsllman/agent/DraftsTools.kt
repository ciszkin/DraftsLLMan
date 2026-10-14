package by.ciszkin.draftsllman.agent

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import by.ciszkin.draftsllman.contract.ToolArgs
import by.ciszkin.draftsllman.contract.toolArgsJson
import by.ciszkin.draftsllman.engine.ToolExecutor
import by.ciszkin.draftsllman.engine.ToolResult
import kotlinx.serialization.serializer

/** Renders a ToolResult as concise, coordinate-free, text-heavy feedback for the LLM (Zero-Coordinate
 *  Policy). The internal ToolResult still carries box fields (touchedBox / boxUsed), but they are no
 *  longer read in production — ROI rendering recomputes its own box — so they are vestigial after this
 *  slice and can be dropped in a later cleanup. They never appear in the string the LLM sees. */
internal fun format(result: ToolResult): String = when (result) {
    is ToolResult.Committed -> {
        // The executor sets Committed.message to "ok" for historic mutations and to a descriptive
        // string for add_nodes (e.g. "ok: added 4 nodes ..."). Forward the descriptive form verbatim;
        // otherwise rebuild the historic "ok: touched <id>" from the touched node id.
        val id = result.touchedNodeId
        when {
            result.message != "ok" -> result.message
            id != null -> "ok: touched $id"
            else -> "ok"
        }
    }
    is ToolResult.Rejected -> "error: ${result.errors}"
    is ToolResult.SolverFailure -> "error: ${result.message}"
    is ToolResult.Failure -> "error: ${result.message}"
    is ToolResult.Query -> result.payload.toString()
    is ToolResult.Critique -> "critique rendered: global map + ROI image attached"
}

/** Serialize a ToolArgs variant (with discriminator), execute it, format the result. Pure given an executor. */
internal fun dispatch(executor: ToolExecutor, sink: CritiqueImageSink, args: ToolArgs): String {
    val result = executor.execute(toolArgsJson.encodeToString(ToolArgs.serializer(), args))
    if (result is ToolResult.Critique) sink.offer(result.result)
    return format(result)
}

// region Koog SimpleTool wrappers — one factory per ToolArgs variant.
// Tool `name` is derived from the ToolArgs descriptor's serialName (the discriminator the LLM emits), so the
// name/@SerialName invariant is mechanically enforced — no hand-coupled duplicate to keep in sync.
// Each delegates to `dispatch`, which serializes, executes transactionally, and formats text-heavy feedback.

/**
 * Builds a [SimpleTool] for ToolArgs variant [T]: [name] comes from [T]'s `@SerialName`, [argsType] from its
 * reified [typeToken], and [dispatch] is the transactional execute path. Inline+reified so each call site
 * materializes a distinct tool object bound to its concrete [T] (Koog introspection reads the same name/KType
 * the former named classes exposed).
 */
internal inline fun <reified T : ToolArgs> draftsTool(
    executor: ToolExecutor,
    sink: CritiqueImageSink,
    description: String,
): SimpleTool<T> =
    object : SimpleTool<T>(
        argsType = typeToken<T>(),
        name = serializer<T>().descriptor.serialName,
        description = description,
    ) { override suspend fun execute(args: T): String = dispatch(executor, sink, args) }

/** All DraftsLLMan tools, wired to [executor] and [sink]. Register via `ToolRegistry { tools(draftsTools(executor, sink)) }`. */
fun draftsTools(executor: ToolExecutor, sink: CritiqueImageSink): List<SimpleTool<*>> = listOf(
    draftsTool<ToolArgs.AddNode>(executor, sink,
        "Add a primitive node (circle/rect/line/group) to the scene graph. " +
            "preferredX/preferredY are non-authoritative solver hints (default: canvas center); " +
            "the constraint solver computes the final position. Always follow with add_constraint to anchor it."),
    draftsTool<ToolArgs.AddConstraint>(executor, sink,
        "Add a relational constraint (align/distance/connect) between two existing nodes' anchors. " +
            "Prefer this over raw coordinates: the solver derives exact geometry. " +
            "For 'align' both anchors must share an axis; for 'distance' both must be axis-aligned (not POINT); " +
            "for 'connect' both must be POINT anchors."),
    draftsTool<ToolArgs.RemoveNode>(executor, sink,
        "Remove a node (and its referencing constraints) from the scene graph by id."),
    draftsTool<ToolArgs.RemoveConstraint>(executor, sink,
        "Remove a single constraint from the scene graph by id."),
    draftsTool<ToolArgs.SetViewBox>(executor, sink,
        "Set the canvas/view-box dimensions (width x height). Call once at the start of a session."),
    draftsTool<ToolArgs.ExportSvg>(executor, sink,
        "Export the current scene graph as an SVG string (query; does not mutate state)."),
    draftsTool<ToolArgs.RequestVisualCritique>(executor, sink,
        "Render the foveated critique (global map + ROI crop) and return a short text summary confirming it " +
            "was rendered. The rendered images are attached to your next message so you can SEE the canvas — " +
            "call at milestones only (image-light feedback budget). Query; does not mutate state."),
    draftsTool<ToolArgs.AddNodesArgs>(executor, sink,
        "Inject deterministic detail nodes around an EXISTING target node by semantic intent. " +
            "node_type: point|line|curve|shape (NOT circle/rect/dot — a small round detail is 'point'). " +
            "preset: single_node (one detail at an anchor); radial_pattern (spokes — needs count + " +
            "boundary_anchor); linear_pattern (rivets along a line — needs count + start_anchor + end_anchor); " +
            "grid_pattern (needs rows, cols, top_left_anchor, bottom_right_anchor); boundary_pattern " +
            "(perimeter — needs count). The engine computes exact positions from the target's solved box — " +
            "never emit raw coordinates. Generated ids are '<target>_<name>_<i>' (or '<target>_<name>_<row>_<col>')."),
)

// endregion
