package by.ciszkin.draftsllman.eval

import by.ciszkin.draftsllman.render.SkiaRenderer
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.Color
import by.ciszkin.draftsllman.scene.RectNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.Style
import by.ciszkin.draftsllman.scene.SceneGraph
import org.jetbrains.skiko.Library
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The dirty-sketch integrity gate (M1 plan guardrail #1): before a generated sketch joins the
 * dataset, EVERY ground-truth component must still be visible in it (ink present in its region).
 * This prevents qwen-image variants that dropped/merged elements from poisoning the labeled pairs.
 *
 * NOTE on the "good" proxies: the CLEAN fixture render is fill-only (SkiaRenderer has no stroke
 * support for rects), so it is NOT a valid stand-in for a real sketch — real hand-drawn wireframes
 * (and qwen-edit outputs) are OUTLINED. The tests therefore use an outlined render as the known-good
 * proxy: dark strokes around every component + annotation box.
 */
class IntegrityGateTest {

    private fun loadLibrary() = Library.load()

    /**
     * Render a wireframe with a subset of its nodes — the SVG-faithful Skia render now draws the
     * fill + stroke the DSL declared (outlines included), so it is a valid "known-good sketch"
     * proxy for the gate, and dropping nodes simulates a sketch that lost elements.
     */
    private fun renderVariant(wf: Wireframe, dropIds: Set<String>): ByteArray {
        var graph = wf.graph
        for (id in dropIds) graph = graph.withoutNode(id)
        return SkiaRenderer.render(graph)
    }

    @Test
    fun `outlined render of every component passes the gate`() {
        loadLibrary()
        val wf = WireframeDSL().build(7)
        val result = IntegrityGate.check(renderVariant(wf, emptySet()), wf.truth)
        assertTrue(result.passed, "outlined sketch must pass (violations: ${result.violations})")
        assertEquals(emptyList<String>(), result.violations)
    }

    @Test
    fun `blank white image fails naming every component`() {
        loadLibrary()
        val wf = WireframeDSL().build(7)
        val blank = SkiaRenderer.render(SceneGraph(canvas = wf.truth.canvas)) // all-white canvas
        val result = IntegrityGate.check(blank, wf.truth)
        assertFalse(result.passed, "blank image must fail the gate")
        assertEquals(wf.truth.components.size, result.violations.size,
            "every component must be reported missing: ${result.violations}")
    }

    @Test
    fun `solid black image fails as over-inked garbage`() {
        loadLibrary()
        val wf = WireframeDSL().build(7)
        val canvas = wf.truth.canvas
        val black = SkiaRenderer.render(
            SceneGraph(
                nodes = mapOf("blob" to RectNode(
                    "blob", "blob", ResolvedBox(0.0, 0.0, canvas.width, canvas.height),
                    Style(fill = Color.BLACK),
                )),
                rootIds = listOf("blob"),
                canvas = canvas,
            ),
        )
        val result = IntegrityGate.check(black, wf.truth)
        assertFalse(result.passed, "over-inked/black output must fail the gate")
    }

    @Test
    fun `sketch missing some components fails naming exactly those`() {
        loadLibrary()
        val wf = WireframeDSL().build(7)
        val removed = setOf("button_1", "button_2", "badge_0")
        val png = renderVariant(wf, dropIds = removed)
        val result = IntegrityGate.check(png, wf.truth)
        assertFalse(result.passed)
        for (id in removed) {
            assertTrue(result.violations.any { it.contains(id) }, "violations must name missing '$id': ${result.violations}")
        }
        val keptMissing = result.violations.filterNot { v -> removed.any { v.contains(it) } }
        assertEquals(emptyList<String>(), keptMissing, "only removed components may be missing: $keptMissing")
    }

    @Test
    fun `missing annotations are warnings not failures`() {
        loadLibrary()
        val wf = WireframeDSL().build(7)
        val png = renderVariant(wf, dropIds = wf.truth.annotationIds)
        val result = IntegrityGate.check(png, wf.truth)
        assertTrue(result.passed, "structural components still present: ${result.violations}")
        assertTrue(
            result.warnings.any { it.contains("annotation") },
            "dropped annotations must surface as warnings: ${result.warnings}",
        )
    }
    /** A minimal truth for threshold tests: one button component on a 200x200 canvas. */
    private fun singleButtonTruth(): GroundTruth = GroundTruth(
        components = listOf(
            EvComponent("r", "button", PrimitiveKind.RECT, ResolvedBox(30.0, 30.0, 80.0, 60.0)),
        ),
        edges = emptyList(),
        annotationIds = emptySet(),
        canvas = by.ciszkin.draftsllman.scene.CanvasSpec(200.0, 200.0),
    )

    @Test
    fun `mid-gray pen strokes still count as visible ink`() {
        loadLibrary()
        // Hand-drawn ink is often mid-gray; the gate must accept it (default threshold 192).
        val sketch = by.ciszkin.draftsllman.render.SkiaRenderer.render(
            by.ciszkin.draftsllman.scene.SceneGraph(
                nodes = mapOf("r" to by.ciszkin.draftsllman.scene.RectNode(
                    "r", "button", ResolvedBox(30.0, 30.0, 80.0, 60.0),
                    by.ciszkin.draftsllman.scene.Style(
                        fill = null, stroke = by.ciszkin.draftsllman.scene.Color.rgb(140, 140, 140), strokeWidth = 3.0,
                    ),
                )),
                rootIds = listOf("r"),
                canvas = by.ciszkin.draftsllman.scene.CanvasSpec(200.0, 200.0),
            ),
        )
        val result = IntegrityGate.check(sketch, singleButtonTruth())
        assertTrue(result.passed, "mid-gray strokes must be visible ink: ${result.violations}")
    }

    @Test
    fun `near-white strokes are not ink`() {
        loadLibrary()
        val sketch = by.ciszkin.draftsllman.render.SkiaRenderer.render(
            by.ciszkin.draftsllman.scene.SceneGraph(
                nodes = mapOf("r" to by.ciszkin.draftsllman.scene.RectNode(
                    "r", "button", ResolvedBox(30.0, 30.0, 80.0, 60.0),
                    by.ciszkin.draftsllman.scene.Style(
                        fill = null, stroke = by.ciszkin.draftsllman.scene.Color.rgb(245, 245, 245), strokeWidth = 3.0,
                    ),
                )),
                rootIds = listOf("r"),
                canvas = by.ciszkin.draftsllman.scene.CanvasSpec(200.0, 200.0),
            ),
        )
        val result = IntegrityGate.check(sketch, singleButtonTruth())
        assertTrue(!result.passed, "near-white strokes must NOT count as ink")
    }

}
