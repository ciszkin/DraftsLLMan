package by.ciszkin.draftsllman.render

import by.ciszkin.draftsllman.scene.BezierPath
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.Color
import by.ciszkin.draftsllman.scene.CurveNode
import by.ciszkin.draftsllman.scene.RectNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.ShapeNode
import by.ciszkin.draftsllman.scene.Style
import org.jetbrains.skiko.Library
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SkiaRendererTest {
    @Test fun `renders a circle to non-empty png bytes`() {
        Library.load()
        val graph = SceneGraph(
            nodes = mapOf("a" to CircleNode("a", "w", ResolvedBox(10.0, 10.0, 40.0, 40.0), Style(fill = Color.BLACK))),
            rootIds = listOf("a"),
            canvas = CanvasSpec(100.0, 100.0),
        )
        val png = SkiaRenderer.render(graph)
        assertTrue(png.size > 100)
        assertEquals(0x89, png[0].toInt() and 0xFF)
        assertEquals(0x50, png[1].toInt() and 0xFF)
    }

    @Test fun `renders a non-square shape without crashing`() {
        Library.load()
        val graph = SceneGraph(
            nodes = mapOf("a" to RectNode("a", "r", ResolvedBox(10.0, 10.0, 60.0, 40.0), Style(fill = Color.BLACK))),
            rootIds = listOf("a"),
            canvas = CanvasSpec(100.0, 100.0),
        )
        val png = SkiaRenderer.render(graph)
        assertTrue(png.size > 100)
    }

    @Test fun `renders a quadratic curve to non-blank png bytes`() {
        Library.load()
        val graph = SceneGraph(
            nodes = mapOf(
                "c" to CurveNode(
                    id = "c",
                    semanticRole = "cable",
                    path = BezierPath(startPoint = 10.0 to 10.0, endPoint = 90.0 to 10.0, controlPoint1 = 50.0 to 80.0),
                    style = Style(stroke = Color.BLACK),
                )
            ),
            rootIds = listOf("c"),
            canvas = CanvasSpec(100.0, 100.0),
        )
        val blank = SkiaRenderer.render(SceneGraph(canvas = CanvasSpec(100.0, 100.0)))
        val rendered = SkiaRenderer.render(graph)
        assertTrue(rendered.size > 100)
        assertNotEquals(blank.toList(), rendered.toList(), "curve must rasterize non-blank pixels")
    }

    @Test fun `renders a filled closed shape to non-blank png bytes`() {
        Library.load()
        val graph = SceneGraph(
            nodes = mapOf(
                "s" to ShapeNode(
                    id = "s",
                    semanticRole = "tri",
                    points = listOf(20.0 to 20.0, 80.0 to 20.0, 50.0 to 80.0),
                    style = Style(fill = Color.BLACK),
                )
            ),
            rootIds = listOf("s"),
            canvas = CanvasSpec(100.0, 100.0),
        )
        val blank = SkiaRenderer.render(SceneGraph(canvas = CanvasSpec(100.0, 100.0)))
        val rendered = SkiaRenderer.render(graph)
        assertTrue(rendered.size > 100)
        assertNotEquals(blank.toList(), rendered.toList(), "shape must rasterize non-blank pixels")
    }
}
