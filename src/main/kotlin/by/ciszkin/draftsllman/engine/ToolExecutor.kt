package by.ciszkin.draftsllman.engine

import by.ciszkin.draftsllman.contract.KotlinxSchemaGenerator
import by.ciszkin.draftsllman.contract.OptimumSchemaValidator
import by.ciszkin.draftsllman.contract.ToolArgs
import by.ciszkin.draftsllman.contract.ValidationGate
import by.ciszkin.draftsllman.contract.toolArgsJson
import by.ciszkin.draftsllman.draftsman.GeometryTools
import by.ciszkin.draftsllman.render.SkiaRenderer
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.GroupNode
import by.ciszkin.draftsllman.scene.LineNode
import by.ciszkin.draftsllman.scene.RectNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.SceneNode
import by.ciszkin.draftsllman.scene.Style
import by.ciszkin.draftsllman.scene.svg.SvgExporter
import by.ciszkin.draftsllman.solver.ConstraintSet
import by.ciszkin.draftsllman.solver.SolveResult
import java.util.Base64

class ToolExecutor(
    private val engine: DraftsEngine = DraftsEngine(),
    private val gate: ValidationGate,
) {
    private val json = toolArgsJson

    fun engine(): DraftsEngine = engine

    /** The current scene graph as an SVG document — the host-side query behind the `export_svg` tool. */
    fun exportSvg(): String = SvgExporter.emit(engine.current)

    fun execute(rawJson: String): ToolResult {
        val element = try { json.parseToJsonElement(rawJson) }
        catch (e: Exception) { return ToolResult.Failure("malformed JSON: ${e.message}") }

        val gateResult = gate.validate(ToolArgs.serializer().descriptor, element)
        if (gateResult.errors.isNotEmpty())
            return ToolResult.Rejected(gateResult.errors.map { "${it.path}: ${it.message}" })

        val args = try { json.decodeFromJsonElement(ToolArgs.serializer(), gateResult.element) }
        catch (e: Exception) { return ToolResult.Rejected(listOf("decode: ${e.message}")) }

        val semanticErrors = args.semanticValidate(engine.current, engine.current.constraints)
        if (semanticErrors.isNotEmpty())
            return ToolResult.Rejected(semanticErrors.map { "${it.path}: ${it.message}" })

        return when (args) {
            is ToolArgs.ExportSvg -> ToolResult.Query(exportSvg())
            is ToolArgs.RequestVisualCritique -> renderCritique(engine.current, args)
            else -> runMutation(args)
        }
    }

    private fun runMutation(args: ToolArgs): ToolResult {
        val memento = engine.snapshot()
        val pre = engine.current
        return try {
            val (candidateGraph, candidateConstraints) = applyToCandidate(args, engine.current)
            val (solved, solveResult) = engine.solveInto(candidateGraph.copy(constraints = candidateConstraints))
            if (solveResult is SolveResult.Unsatisfiable) {
                engine.restore(memento); return ToolResult.SolverFailure(solveResult.message)
            }
            val verdict = ProgrammaticVerifier.verify(solved, candidateConstraints)
            if (!verdict.ok) { engine.restore(memento); return ToolResult.Failure(verdict.errors.joinToString("; ")) }
            engine.commit(solved)
            val (touchedNodeId, touchedBox) = touchedFor(args, pre, solved)
            ToolResult.Committed(commitMessageFor(args, pre, solved), touchedNodeId, touchedBox)
        } catch (e: Exception) {
            engine.restore(memento); ToolResult.Failure("${e::class.simpleName}: ${e.message}")
        }
    }

    private fun renderCritique(graph: SceneGraph, args: ToolArgs.RequestVisualCritique): ToolResult = try {
        val roiBox = args.nodeId?.let { graph.groupBox(it) }
        val (global, roi) = SkiaRenderer.renderFoveated(graph, args.globalSize, roiBox, args.roiSize)
        ToolResult.Critique(CritiqueResult(global.toBase64Png(), roi?.toBase64Png(), roiBox))
    } catch (e: Exception) {
        ToolResult.Failure("${e::class.simpleName}: ${e.message}")
    }

    private fun ByteArray.toBase64Png(): String = Base64.getEncoder().encodeToString(this)

    private fun touchedFor(args: ToolArgs, pre: SceneGraph, solved: SceneGraph): Pair<String?, ResolvedBox?> = when (args) {
        is ToolArgs.AddNode -> args.id to solved.nodes[args.id]?.box
        is ToolArgs.AddConstraint -> {
            val moved = solved.nodes.entries.mapNotNull { (id, n) ->
                val preBox = pre.nodes[id]?.box
                if (preBox != null && preBox != n.box) id to n.box else null
            }
            val nodeId = if (moved.size == 1) moved.first().first else args.sourceId
            nodeId to ResolvedBox.unionOf(moved.map { it.second })
        }
        is ToolArgs.RemoveNode -> args.id to pre.nodes[args.id]?.box
        is ToolArgs.RemoveConstraint -> null to null
        is ToolArgs.SetViewBox -> null to null
        is ToolArgs.ExportSvg, is ToolArgs.RequestVisualCritique -> null to null
        // Detail nodes sit on/around the target; report the target id and the union of the newly-added
        // nodes' solved boxes so ROI (SkiaRenderer.renderFoveated) crops to the detail region.
        is ToolArgs.AddNodesArgs -> {
            val addedBoxes = solved.nodes.entries.mapNotNull { (id, n) ->
                if (id in pre.nodes) null else n.box
            }
            args.targetId to ResolvedBox.unionOf(addedBoxes)
        }
    }

    /**
     * The LLM-facing success message. [ToolArgs.AddNodesArgs] gets a descriptive summary (so the
     * Draftsman knows what was injected); every other mutation keeps the historic `"ok"` and the
     * agent-layer formatter renders `"ok: touched <id>"` from [touchedFor].
     */
    private fun commitMessageFor(args: ToolArgs, pre: SceneGraph, solved: SceneGraph): String = when (args) {
        is ToolArgs.AddNodesArgs -> {
            val added = solved.nodes.keys.count { it !in pre.nodes }
            val noun = if (added == 1) "node" else "nodes"
            "ok: added $added $noun of type '${args.nodeType}' via ${args.preset} to '${args.targetId}'"
        }
        else -> "ok"
    }

    private fun applyToCandidate(args: ToolArgs, graph: SceneGraph): Pair<SceneGraph, ConstraintSet> {
        val constraints = graph.constraints
        return when (args) {
            is ToolArgs.AddNode -> {
                val px = args.preferredX ?: graph.canvas.width / 2.0
                val py = args.preferredY ?: graph.canvas.height / 2.0
                val box = ResolvedBox(px, py, args.width, args.height)
                val node: SceneNode = when (args.kind) {
                    "circle" -> CircleNode(args.id, args.role, box, Style())
                    "rect" -> RectNode(args.id, args.role, box, Style())
                    "line" -> LineNode(args.id, args.role, start = Pair(px, py), end = Pair(px + args.width, py + args.height), style = Style())
                    "group" -> GroupNode(args.id, args.role, args.childIds)
                    else -> error("unsupported kind")
                }
                val withChildren = if (node is GroupNode && args.childIds.isNotEmpty())
                    graph.copy(children = graph.children + (args.id to args.childIds))
                else graph
                withChildren.withNode(node).withRoot(args.id).withAnchorAliases(args.id, args.anchors) to constraints
            }
            is ToolArgs.AddConstraint -> graph to constraints.add(args.toConstraint(graph))
            is ToolArgs.RemoveNode -> graph.withoutNode(args.id) to
                ConstraintSet(constraints.constraints.filterNot { args.id in it.references() })
            is ToolArgs.RemoveConstraint -> graph to constraints.remove(args.id)
            is ToolArgs.SetViewBox -> graph.copy(canvas = CanvasSpec(args.width, args.height)) to constraints
            is ToolArgs.ExportSvg, is ToolArgs.RequestVisualCritique -> graph to constraints
            // Semantic validation already ran in execute(); GeometryTools computes deterministic
            // positions from the target's SOLVED box. Add each generated node + surface it at root
            // (mirrors AddNode's withNode/withRoot). No new constraints — detail nodes hold their
            // computed positions via the solver's MEDIUM position stays (see ConstraintSolver).
            is ToolArgs.AddNodesArgs -> {
                val detailNodes = GeometryTools.execute(args, graph)
                var withDetails = graph
                for (node in detailNodes) withDetails = withDetails.withNode(node).withRoot(node.id)
                withDetails to constraints
            }
        }
    }

    companion object {
        /** A [ToolExecutor] wired with a fresh [DraftsEngine] and the standard schema-validated [ValidationGate]. */
        fun default(): ToolExecutor = ToolExecutor(DraftsEngine(), ValidationGate(KotlinxSchemaGenerator(), OptimumSchemaValidator()))
    }
}
