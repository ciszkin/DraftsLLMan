package by.ciszkin.draftsllman.draftsman

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import by.ciszkin.draftsllman.agent.AgentRunner
import by.ciszkin.draftsllman.agent.DraftsAgent
import by.ciszkin.draftsllman.agent.RunStatus
import by.ciszkin.draftsllman.agent.Tracer
import by.ciszkin.draftsllman.agent.installTracing
import by.ciszkin.draftsllman.architect.ComponentBrief
import by.ciszkin.draftsllman.engine.ToolExecutor
import by.ciszkin.draftsllman.scene.Anchor
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.SceneNode

/**
 * Runs a Draftsman — a [DraftsAgent] scoped to one [ComponentBrief] — in an ISOLATED, per-run scene and
 * returns the drawn [Component] (the local fragment). The canvas is pre-set to the brief's region size, so
 * the fragment's coordinates are local (Slice H offsets by [ComponentBrief.region]). Reuses all of
 * DraftsAgent's tools + vision critique + sliding window; only the system prompt is Brief-scoped.
 *
 * Each [run] / [runOnSkeleton] creates a fresh [ToolExecutor] (isolated scene) and a fresh [AgentRunner]
 * (fresh no-op state), so runs never leak state into each other.
 *
 * Two entry points:
 *  - [run]            — Slice F/G per-Component-Brief mode (fresh canvas sized to the brief's region).
 *  - [runOnSkeleton]  — Slice 5 Phase 3 detail-injection mode: seeds the isolated engine with ONE
 *                       already-solved skeleton component at its GLOBAL position, so the Draftsman's
 *                       `add_nodes` calls resolve against the real solved node and the generated detail
 *                       lands at correct global coordinates (no offset-merge step).
 */
class DraftsmanRunner(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val maxIterations: Int = 20,
    private val tracer: Tracer? = null,
) {
    fun run(brief: ComponentBrief): ComponentOutcome {
        val executor = ToolExecutor.default()
        val region = brief.region
        executor.execute("""{"type":"set_view_box","width":${region.width},"height":${region.height}}""")

        val runner = AgentRunner(executor)
        val agent = DraftsAgent(
            executor,
            promptExecutor,
            model,
            systemPrompt = briefPrompt(brief),
            maxIterations = maxIterations,
            installFeatures = {
                val noOp = runner.installNoOpFeature
                noOp()
                tracer?.let { installTracing("Draftsman:${brief.id}", it) }
            },
        ).agent()
        val runOutcome = runner.run(agent, brief.description)

        val graph = executor.engine().current
        val component = if (graph.nodes.isNotEmpty())
            Component(brief.id, region, graph.nodes, graph.constraints) else null
        val status = when (runOutcome.status) {
            RunStatus.DONE -> if (component != null) DraftsmanStatus.DRAWN else DraftsmanStatus.EMPTY
            RunStatus.NO_OP -> DraftsmanStatus.NO_OP
            RunStatus.MAX_ITERATIONS -> DraftsmanStatus.MAX_ITERATIONS
        }
        return ComponentOutcome(component, status)
    }

    /**
     * Slice 5 Phase 3 detail-injection mode. Seeds the Draftsman's ISOLATED engine with the solved
     * skeleton component AT ITS GLOBAL POSITION (plus its named-anchor aliases and the global canvas),
     * runs the agent with `add_nodes` available, and returns the freshly-drawn DETAIL nodes as a
     * [Component] whose [Component.region] is the solved box and whose [Component.nodes] are the detail
     * nodes only (the seed component node is excluded).
     *
     * Why seed global? `add_nodes` (GeometryTools) computes each detail position from the target node's
     * SOLVED box, in the same coordinate space. Seeding at the global position makes the detail land at
     * global coordinates directly, so the Coordinator can drop the detail nodes into the solved graph
     * with NO offset step — the fix for the "offset-merge can't place detail" defect. The re-solve inside
     * ToolExecutor adds only MEDIUM-stay position constraints with no competing REQUIRED constraints
     * (the skeleton's connect constraints stay in the Coordinator's global graph, NOT here), so every
     * node holds its computed position.
     *
     * [brief] supplies the id/role/description (its region is unused here — the returned Component's
     * box is [solvedNode]'s box, and the prompt carries the coordinate-free handoff instead);
     * [solvedNode] is the seed node (its box == the solved box); [anchors] are the component's
     * named-anchor aliases so `add_nodes` anchor names like "hub" resolve; [canvas] is the global
     * canvas; [connections] are the component's neighbor relations for the handoff.
     */
    fun runOnSkeleton(
        brief: ComponentBrief,
        solvedNode: SceneNode,
        anchors: Map<String, Anchor>,
        canvas: CanvasSpec,
        connections: List<ComponentConnection> = emptyList(),
    ): ComponentOutcome {
        val executor = ToolExecutor.default()
        val seeded = SceneGraph(
            nodes = mapOf(brief.id to solvedNode),
            rootIds = listOf(brief.id),
            canvas = canvas,
            anchorAliases = if (anchors.isEmpty()) emptyMap() else mapOf(brief.id to anchors),
        )
        executor.engine().commit(seeded)

        val runner = AgentRunner(executor)
        val agent = DraftsAgent(
            executor,
            promptExecutor,
            model,
            systemPrompt = skeletonPrompt(
                brief,
                // Indent the handoff to the template's level so trimIndent keeps a common indent —
                // a flush-left multiline interpolation would defeat it and ship a ragged prompt.
                componentHandoff(brief.id, brief.semanticRole, solvedNode, solvedNode.box, canvas, anchors, connections)
                    .replaceIndent("        "),
            ),
            maxIterations = maxIterations,
            installFeatures = {
                val noOp = runner.installNoOpFeature
                noOp()
                tracer?.let { installTracing("Draftsman:${brief.id}", it) }
            },
        ).agent()
        val runOutcome = runner.run(agent, brief.description)

        val graph = executor.engine().current
        // Detail nodes = everything drawn this run EXCEPT the seed component node (it stays in the
        // Coordinator's solved graph; only the new detail is handed back).
        val detailNodes = graph.nodes.filterKeys { it != brief.id }
        val component = if (detailNodes.isNotEmpty())
            Component(brief.id, solvedNode.box, detailNodes, graph.constraints) else null
        val status = when (runOutcome.status) {
            RunStatus.DONE -> if (component != null) DraftsmanStatus.DRAWN else DraftsmanStatus.EMPTY
            RunStatus.NO_OP -> DraftsmanStatus.NO_OP
            RunStatus.MAX_ITERATIONS -> DraftsmanStatus.MAX_ITERATIONS
        }
        return ComponentOutcome(component, status)
    }

    private fun briefPrompt(brief: ComponentBrief): String = """
        You are drawing ONE component of a larger drawing, in isolation.
        Component: '${brief.semanticRole}' (id: '${brief.id}').
        Draw: ${brief.description}
        Your canvas is ${brief.region.width.toInt()}×${brief.region.height.toInt()} (already set — do not call set_view_box). Draw within it.
        1. add_node for every primitive base shape, then add_constraint to relate them (align/distance/connect) — the solver computes exact positions. Never emit raw coordinates.
        2. add_nodes for DETAIL (spokes, rivets, ribs, texture) on an existing target — presets: single_node, radial_pattern (spokes), linear_pattern (rivets), grid_pattern, boundary_pattern. The engine computes exact positions from the target's solved box.
        3. request_visual_critique only at milestones.
        4. Reply with the plain text DONE when the component is complete.
        Tool results are "ok: touched <id> ..." or "ok: added N nodes ..." on success, "error: ..." on failure. On error, fix and retry.
        Do not re-issue a tool call whose result you just received unchanged.
    """.trimIndent()

    /** Phase 3 detail-injection prompt: the component is already drawn + placed; only add detail via
     *  add_nodes. The handoff block is COORDINATE-FREE (Zero-Coordinate policy #2): qualitative
     *  size, declared anchors, semantic connections — never absolute geometry. */
    private fun skeletonPrompt(brief: ComponentBrief, handoff: String): String = """
        You are adding DETAIL to ONE already-drawn, already-placed component of a larger drawing.
        $handoff
        Task: ${brief.description}
        The canvas is the FULL global canvas (already set — do not call set_view_box, add_node, or add_constraint — these are not available in the detail phase). Use ONLY add_nodes to inject detail on the existing target '${brief.id}':
        - radial_pattern for spokes (count + boundary_anchor), linear_pattern for rivets (count + start_anchor + end_anchor),
          grid_pattern (rows, cols, top_left_anchor, bottom_right_anchor), boundary_pattern (count), single_node (center_anchor).
        The engine computes exact positions from the target's solved box — never emit raw coordinates.
        Reply with the plain text DONE when the detail is complete.
        Tool results are "ok: added N nodes ..." on success, "error: ..." on failure. On error, fix and retry.
        Do not re-issue a tool call whose result you just received unchanged.
    """.trimIndent()
}
