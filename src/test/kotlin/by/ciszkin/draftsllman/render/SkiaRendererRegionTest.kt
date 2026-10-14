package by.ciszkin.draftsllman.render

import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.Color
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.Style
import org.jetbrains.skia.Image
import org.jetbrains.skiko.Library
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SkiaRendererRegionTest {
    private fun graphWithCircle(): SceneGraph = SceneGraph(
        nodes = mapOf("a" to CircleNode("a", "w", ResolvedBox(10.0, 10.0, 40.0, 40.0), Style(fill = Color.BLACK))),
        rootIds = listOf("a"),
        canvas = CanvasSpec(100.0, 100.0),
    )

    @Test fun `renderRegion crops to a square target size`() {
        Library.load()
        val png = SkiaRenderer.renderRegion(graphWithCircle(), ResolvedBox(10.0, 10.0, 40.0, 40.0), 64)
        val img = Image.makeFromEncoded(png)
        assertEquals(64, img.width)
        assertEquals(64, img.height)
    }

    @Test fun `renderRegion includes the node rather than blank`() {
        Library.load()
        val box = ResolvedBox(10.0, 10.0, 40.0, 40.0)
        val withNode = SkiaRenderer.renderRegion(graphWithCircle(), box, 64)
        val blank = SkiaRenderer.renderRegion(
            SceneGraph(rootIds = emptyList(), canvas = CanvasSpec(100.0, 100.0)), box, 64,
        )
        assertFalse(withNode.contentEquals(blank), "crop of a node must differ from a blank crop")
    }
}
