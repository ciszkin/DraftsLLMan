package by.ciszkin.draftsllman.scene

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShapeNodeTest {

    private val triangle = listOf(0.0 to 0.0, 10.0 to 0.0, 5.0 to 10.0)

    @Test
    fun `closed shape emits a polygon element with compact points`() {
        val node = ShapeNode(
            id = "s",
            semanticRole = "tri",
            points = triangle,
            style = Style(fill = Color.BLACK),
        )
        val svg = node.toSvgString()
        assertTrue(svg.contains("<polygon"), "expected a <polygon element: $svg")
        assertTrue(svg.contains("""id="s""""), "expected id=\"s\": $svg")
        assertTrue(svg.contains("""data-role="tri""""), "expected data-role=\"tri\": $svg")
        assertTrue(svg.contains("""points="0.00 0.00 10.00 0.00 5.00 10.00""""), "expected compact points list: $svg")
    }

    @Test
    fun `open shape emits a path polyline with M and L segments and no Z`() {
        val node = ShapeNode(
            id = "s",
            semanticRole = "poly",
            points = triangle,
            closed = false,
            style = Style(stroke = Color.BLACK),
        )
        val svg = node.toSvgString()
        assertTrue(svg.contains("<path"), "expected a <path element: $svg")
        assertTrue(
            svg.contains("""d="M 0.00 0.00 L 10.00 0.00 L 5.00 10.00""""),
            "expected open polyline path data: $svg",
        )
    }

    @Test
    fun `shape with fewer than three points is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ShapeNode(id = "s", semanticRole = "x", points = listOf(0.0 to 0.0, 1.0 to 1.0))
        }
    }

    @Test
    fun `withBox translates every point by the origin delta`() {
        val node = ShapeNode(id = "s", semanticRole = "tri", points = triangle)
        val moved = node.withBox(node.box.translate(3.0, 4.0)) as ShapeNode
        assertEquals(3.0, moved.box.x)
        assertEquals(4.0, moved.box.y)
        assertEquals(10.0, moved.box.width)
        assertEquals(10.0, moved.box.height)
        assertEquals(listOf(3.0 to 4.0, 13.0 to 4.0, 8.0 to 14.0), moved.points)
    }
}
