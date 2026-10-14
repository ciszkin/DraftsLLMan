package by.ciszkin.draftsllman.eval

import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.Color
import by.ciszkin.draftsllman.scene.CurveNode
import by.ciszkin.draftsllman.scene.LineNode
import by.ciszkin.draftsllman.scene.RectNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.SceneNode
import by.ciszkin.draftsllman.scene.ShapeNode
import by.ciszkin.draftsllman.scene.Style
import kotlin.random.Random

/**
 * M1 fixture: a tiny, seeded, deterministic DSL that builds a SceneGraph of CLEAN primitives
 * representing a UI wireframe/diagram, and co-derives the [GroundTruth] for that same scene so the
 * truth boxes always match the emitted nodes exactly.
 *
 * The generated scene is deliberately "clean" — no noise, precise layout — because messiness
 * (jitter) is applied later by Sketchify when rasterizing. This DSL only *threads* the
 * [Difficulty] through so downstream stages know how messy the sketch should be.
 *
 * The output **is** the ground truth: for every non-annotation node we emit we co-produce an
 * [EvComponent]; for every geometrically-contained child we emit a CONTAINS [EvEdge]; and the
 * annotation pointer/scribble marks are recorded in [GroundTruth.annotationIds] so a vision-first
 * pipeline must exclude them from the recovered component set (T1 discrimination).
 *
 * Geometry is fully driven by a [seed]-derived [Random]: positions, sizes and counts vary
 * deterministically per seed, while the CONTAINS invariants (children strictly inside their
 * container) are enforced by construction regardless of seed.
 */
class WireframeDSL(private val rng: Random = Random(42)) {

    /**
     * Build one deterministic case. [seed] seeds a fresh [Random] so the whole layout — positions,
     * sizes, and count variation — is fully reproducible.
     */
    fun build(seed: Int): Wireframe {
        val r = Random(seed)
        val canvas = CanvasSpec(900.0, 640.0)

        // ---- Containers (stable large background zones) ----
        val sidebar = RectNode(
            id = "container_0",
            semanticRole = "container",
            box = ResolvedBox(24.0, 24.0, 200.0, 560.0),
            style = Style(fill = Color.rgb(245, 246, 248), stroke = Color.BLACK, strokeWidth = 1.5),
        )
        val main = RectNode(
            id = "container_1",
            semanticRole = "container",
            box = ResolvedBox(252.0, 24.0, 620.0, 560.0),
            style = Style(fill = Color.WHITE, stroke = Color.BLACK, strokeWidth = 1.5),
        )

        // ---- Button inside sidebar (y jitters within the sidebar) ----
        val navButton = RectNode(
            id = "button_0",
            semanticRole = "button",
            box = ResolvedBox(44.0, 60.0 + r.nextDouble() * 20.0, 160.0, 36.0),
            style = Style(fill = Color.rgb(220, 225, 232), stroke = Color.BLACK, strokeWidth = 1.0, cornerRadius = 6.0),
        )
        // ---- Avatar inside sidebar (coarsened to "icon" in the truth) ----
        val avatar = CircleNode(
            id = "avatar_0",
            semanticRole = "avatar",
            box = ResolvedBox(44.0, 120.0 + r.nextDouble() * 20.0, 40.0, 40.0),
            style = Style(fill = Color.rgb(200, 205, 214), stroke = Color.BLACK, strokeWidth = 1.0),
        )

        // ---- Chart (focal element) inside main panel: position + size jitter ----
        val chartSize = 150.0 + r.nextDouble() * 50.0
        val chart = CircleNode(
            id = "chart_0",
            semanticRole = "chart",
            box = ResolvedBox(
                340.0 + r.nextDouble() * 40.0,
                140.0 + r.nextDouble() * 40.0,
                chartSize,
                chartSize,
            ),
            style = Style(fill = null, stroke = Color.BLACK, strokeWidth = 2.0),
        )

        // ---- Buttons inside main panel (count + position jitter) ----
        val panelButtons = List(r.nextInt(2, 4)) { i ->
            RectNode(
                id = "button_${i + 1}",
                semanticRole = "button",
                box = ResolvedBox(
                    280.0 + i * 170.0 + r.nextDouble() * 10.0,
                    380.0 + r.nextDouble() * 20.0,
                    120.0 + r.nextDouble() * 40.0,
                    40.0,
                ),
                style = Style(fill = Color.rgb(230, 233, 238), stroke = Color.BLACK, strokeWidth = 1.0, cornerRadius = 8.0),
            )
        }

        // ---- Icons inside sidebar (count + position jitter) ----
        val icons = List(r.nextInt(2, 4)) { i ->
            CircleNode(
                id = "icon_$i",
                semanticRole = "icon",
                box = ResolvedBox(
                    50.0 + i * 60.0 + r.nextDouble() * 10.0,
                    520.0 + r.nextDouble() * 20.0,
                    24.0 + r.nextDouble() * 6.0,
                    24.0 + r.nextDouble() * 6.0,
                ),
                style = Style(fill = null, stroke = Color.BLACK, strokeWidth = 0.8),
            )
        }

        // ---- Dividers (axis-aligned separators) ----
        val divider1 = LineNode(
            id = "divider_0",
            semanticRole = "divider",
            start = 44.0 to 170.0,
            end = 204.0 to 170.0,
            style = Style(stroke = Color.BLACK, strokeWidth = 1.5),
        )
        val divider2 = LineNode(
            id = "divider_1",
            semanticRole = "divider",
            start = 300.0 to 350.0,
            end = 800.0 to 350.0,
            style = Style(stroke = Color.BLACK, strokeWidth = 1.5),
        )

        // ---- Badge polygon (closed triangle) inside main panel ----
        val badge = ShapeNode(
            id = "badge_0",
            semanticRole = "badge",
            points = listOf(
                700.0 to 80.0,
                760.0 to 80.0,
                730.0 to 60.0,
            ),
            closed = true,
            style = Style(fill = Color.rgb(255, 210, 120), stroke = Color.BLACK, strokeWidth = 1.0),
        )

        // ---- Annotation primitives: hand-drawn CALLOUT POINTERS aimed at targets ----
        // (deliberately NOT part of truth.components — the marks to be EXCLUDED.) Each annotation is
        // a small label DOT + an open polyline POINTER with an arrowhead pointing AT the target — the
        // classic napkin-wireframe call-out, so a VLM reads "meta-annotation", not "UI element".
        val annChart = pointerAnnotation(
            "annotation_0",
            origin = (chart.box.cx - 190.0) to (chart.box.cy - 130.0),
            target = (chart.box.cx - chart.radius * 0.9) to (chart.box.cy - chart.radius * 0.9),
        )
        val annSidebar = pointerAnnotation(
            "annotation_1",
            origin = (sidebar.box.right + 62.0) to (sidebar.box.top + 44.0),
            target = (sidebar.box.right + 6.0) to (sidebar.box.top + 26.0),
        )

        val allNonAnnotation = listOf(
            sidebar, main, navButton, avatar, chart,
        ) + panelButtons + icons + listOf(divider1, divider2, badge)

        val annotations = annChart + annSidebar

        // ---- Assemble the SceneGraph ----
        val nodes: Map<String, by.ciszkin.draftsllman.scene.SceneNode> = buildMap {
            for (n in allNonAnnotation) put(n.id, n)
            for (n in annotations) put(n.id, n)
        }
        val graph = SceneGraph(
            nodes = nodes,
            // Annotations MUST be roots too: SkiaRenderer draws only root-reachable nodes, so a
            // wavy pointer mark that is merely present in `nodes` never appears in the sketch —
            // and the eval's annotation-discrimination test requires it to be visible to exclude.
            rootIds = (allNonAnnotation + annotations).map { it.id },
            canvas = canvas,
        )

        // ---- Co-derive the GroundTruth ----
        val components = allNonAnnotation.map { node ->
            EvComponent(
                id = node.id,
                role = coarsenRole(node.semanticRole),
                kind = kindOf(node),
                box = node.box,
            )
        }

        val edges = listOf(
            EvEdge(sidebar.id, navButton.id, EdgeRelation.CONTAINS),
            EvEdge(sidebar.id, avatar.id, EdgeRelation.CONTAINS),
            EvEdge(main.id, chart.id, EdgeRelation.CONTAINS),
        )

        val truth = GroundTruth(
            components = components,
            edges = edges,
            annotationIds = annotations.map { it.id }.toSet(),
            annotationBoxes = annotations.associate { it.id to it.box },
            canvas = canvas,
        )

        return Wireframe(graph = graph, truth = truth)
    }

    private fun coarsenRole(role: String): String = when (role) {
        "avatar" -> "icon"
        else -> role
    }

    private fun kindOf(node: by.ciszkin.draftsllman.scene.SceneNode): PrimitiveKind = when (node) {
        is CircleNode -> PrimitiveKind.CIRCLE
        is RectNode -> PrimitiveKind.RECT
        is LineNode -> PrimitiveKind.LINE
        is CurveNode -> PrimitiveKind.PATH
        is ShapeNode -> if (node.closed) PrimitiveKind.POLYGON else PrimitiveKind.PATH
        else -> error("unexpected node type ${node::class.simpleName}")
    }

    /**
     * The MACRO difficulty tier (eval-framework \u00a78): the same wireframe at its recoverable size —
     * 7 components (2 containers, 1 button, 1 chart, 2 dividers, 1 badge) + two call-out annotations,
     * NO per-container icon/button detail rows. Small enough for a vision model to express as a
     * satisfiable skeleton, unlike the 13-component detail tier.
     */
    fun buildMacro(seed: Int): Wireframe {
        val canvas = CanvasSpec(900.0, 640.0)
        val sidebar = RectNode(
            id = "container_0", semanticRole = "container",
            box = ResolvedBox(24.0, 24.0, 200.0, 560.0),
            style = Style(fill = Color.rgb(245, 246, 248), stroke = Color.BLACK, strokeWidth = 1.5),
        )
        val main = RectNode(
            id = "container_1", semanticRole = "container",
            box = ResolvedBox(252.0, 24.0, 620.0, 560.0),
            style = Style(fill = Color.WHITE, stroke = Color.BLACK, strokeWidth = 1.5),
        )
        val button = RectNode(
            id = "button_0", semanticRole = "button",
            box = ResolvedBox(44.0, 80.0, 160.0, 36.0),
            style = Style(fill = Color.rgb(220, 225, 232), stroke = Color.BLACK, strokeWidth = 1.0, cornerRadius = 6.0),
        )
        val chart = CircleNode(
            id = "chart_0", semanticRole = "chart",
            box = ResolvedBox(340.0, 150.0, 180.0, 180.0),
            style = Style(fill = null, stroke = Color.BLACK, strokeWidth = 2.0),
        )
        val dividerSide = LineNode(
            id = "divider_0", semanticRole = "divider",
            start = 44.0 to 170.0, end = 204.0 to 170.0,
            style = Style(stroke = Color.BLACK, strokeWidth = 1.5),
        )
        val dividerMain = LineNode(
            id = "divider_1", semanticRole = "divider",
            start = 300.0 to 360.0, end = 800.0 to 360.0,
            style = Style(stroke = Color.BLACK, strokeWidth = 1.5),
        )
        val badge = ShapeNode(
            id = "badge_0", semanticRole = "badge",
            points = listOf(700.0 to 80.0, 760.0 to 80.0, 730.0 to 60.0),
            closed = true,
            style = Style(fill = Color.rgb(255, 210, 120), stroke = Color.BLACK, strokeWidth = 1.0),
        )
        val annChart = pointerAnnotation(
            "annotation_0",
            origin = (chart.box.cx - 190.0) to (chart.box.cy - 130.0),
            target = (chart.box.cx - chart.radius * 0.9) to (chart.box.cy - chart.radius * 0.9),
        )
        val annSidebar = pointerAnnotation(
            "annotation_1",
            origin = (sidebar.box.right + 62.0) to (sidebar.box.top + 44.0),
            target = (sidebar.box.right + 6.0) to (sidebar.box.top + 26.0),
        )
        val all = listOf(sidebar, main, button, chart, dividerSide, dividerMain, badge)
        val annotations = annChart + annSidebar
        val graph = SceneGraph(
            nodes = buildMap {
                for (n in all) put(n.id, n)
                for (n in annotations) put(n.id, n)
            },
            rootIds = (all + annotations).map { it.id },
            canvas = canvas,
        )
        val truth = GroundTruth(
            components = all.map { EvComponent(it.id, coarsenRole(it.semanticRole), kindOf(it), it.box) },
            edges = listOf(
                EvEdge(sidebar.id, button.id, EdgeRelation.CONTAINS),
                EvEdge(main.id, chart.id, EdgeRelation.CONTAINS),
            ),
            annotationIds = annotations.map { it.id }.toSet(),
            annotationBoxes = annotations.associate { it.id to it.box },
            canvas = canvas,
        )
        return Wireframe(graph = graph, truth = truth)
    }

    /**
     * A hand-drawn CALLOUT annotation: a small label [dot] at [origin] plus an open polyline
     * [pointer] (leader with a kink + arrowhead wings) aimed at [target]. Both primitives carry the
     * same semantic id prefix; both must be excluded by a vision pipeline (they ride
     * [GroundTruth.annotationIds] as separate ids, `${id}_dot` / `${id}_ptr`).
     */
    private fun pointerAnnotation(
        id: String,
        origin: Pair<Double, Double>,
        target: Pair<Double, Double>,
    ): List<SceneNode> {
        val (ox, oy) = origin
        val (tx, ty) = target
        // Leader bend: 55% along the origin->target segment, nudged off-axis for hand-drawn feel.
        val bend = (ox + (tx - ox) * 0.55 + 9.0) to (oy + (ty - oy) * 0.55 - 7.0)
        // Arrowhead wings at the tip, pointing back along the leader.
        val wingR = (tx + 8.0) to (ty + 6.0)
        val wingL = (tx + 11.0) to (ty - 4.0)
        val dot = CircleNode(
            id = "${id}_dot",
            semanticRole = "annotation",
            box = ResolvedBox(ox - 6.0, oy - 6.0, 12.0, 12.0),
            style = Style(fill = null, stroke = Color.rgb(70, 70, 70), strokeWidth = 1.6),
        )
        val pointer = ShapeNode(
            id = "${id}_ptr",
            semanticRole = "annotation",
            points = listOf(origin, bend, target, wingR, wingL),
            closed = false,
            style = Style(stroke = Color.rgb(70, 70, 70), strokeWidth = 1.8),
        )
        return listOf(dot, pointer)
    }
}

/** What a build produces: the clean scene + the truth a perfect pipeline must recover. */
data class Wireframe(val graph: SceneGraph, val truth: GroundTruth)

/**
 * Small top-level factory that forwards to a build, threading [difficulty] through. The component
 * count band / jitter level influences how the downstream Sketchify stage rasterizes the case; the
 * clean layout here is already deterministic via the seed. The difficulty is folded into the derived
 * seed so different difficulty buckets yield different layouts.
 */
fun synthWireframe(seed: Int, difficulty: Difficulty): Wireframe {
    val derivedSeed = seed * 31 + difficulty.componentCount * 7 + difficulty.jitterLevel
    return WireframeDSL().build(derivedSeed)
}