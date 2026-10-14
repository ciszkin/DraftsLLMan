package by.ciszkin.draftsllman.scene.svg

import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.Color
import by.ciszkin.draftsllman.scene.GroupNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.Style
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SvgExporterTest {
    @Test fun `sanitizer neutralizes script in id`() {
        val bad = SvgSanitizer.sanitizeId("<script>alert(1)</script>")
        assertFalse(bad.contains("<"))
        assertFalse(bad.contains(">"))
    }

    @Test fun `sanitizer escapes text`() {
        assertEquals("a&lt;b&gt;c", SvgSanitizer.escapeText("a<b>c"))
    }

    @Test fun `exporter wraps graph in svg with viewBox`() {
        val graph = SceneGraph(
            nodes = mapOf("hub" to CircleNode("hub", "wheel", ResolvedBox(0.0, 0.0, 20.0, 20.0), Style(fill = Color.BLACK))),
            rootIds = listOf("hub"),
            canvas = CanvasSpec(100.0, 100.0),
        )
        val svg = SvgExporter.emit(graph)
        assertTrue(svg.startsWith("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100.00 100.00">"""))
        assertTrue(svg.contains("""<circle id="hub" data-role="wheel""""))
        assertTrue(svg.endsWith("</svg>"))
    }

    @Test fun `exporter emits group with children`() {
        val graph = SceneGraph(
            nodes = mapOf(
                "g" to GroupNode("g", "bike", listOf("a", "b")),
                "a" to CircleNode("a", "wheel", ResolvedBox(0.0, 0.0, 10.0, 10.0), Style(fill = Color.BLACK)),
                "b" to CircleNode("b", "wheel", ResolvedBox(20.0, 0.0, 10.0, 10.0), Style(fill = Color.BLACK)),
            ),
            rootIds = listOf("g"),
            children = mapOf("g" to listOf("a", "b")),
            canvas = CanvasSpec(100.0, 100.0),
        )
        val svg = SvgExporter.emit(graph)
        assertTrue(svg.contains("""<g id="g" data-role="bike">"""))
        assertTrue(svg.contains("</g>"))
    }
}
