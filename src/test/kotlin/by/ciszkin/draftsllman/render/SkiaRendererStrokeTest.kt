package by.ciszkin.draftsllman.render

import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.Color
import by.ciszkin.draftsllman.scene.RectNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.Style
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skiko.Library
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SVG-fidelity tests for the rasterizer: `toSvgString()` already emits `fill` AND `stroke`
 * attributes, so the raster path MUST render them too — otherwise the critique images the VLM sees
 * drift from the exported SVG (an explicit design principle of the engine). Also covers the legacy
 * default: a node with NO style colors still renders as black ink.
 */
class SkiaRendererStrokeTest {

    private fun render(graph: SceneGraph): ByteArray {
        Library.load()
        return SkiaRenderer.render(graph)
    }

    /** Flat RGBA bytes for pixel assertions. */
    private fun rgba(png: ByteArray): ByteArray {
        Library.load()
        val img = Image.makeFromEncoded(png) ?: error("cannot decode")
        val w = img.width
        val h = img.height
        val bmp = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(w, h)) }
        try {
            check(img.readPixels(bmp, 0, 0)) { "readPixels failed" }
            val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, null)
            return bmp.readPixels(info, w * 4, 0, 0) ?: error("no pixels")
        } finally {
            bmp.close()
        }
    }

    private fun lum(rgba: ByteArray, w: Int, x: Int, y: Int): Double {
        val i = (y * w + x) * 4
        val r = rgba[i].toInt() and 0xFF
        val g = rgba[i + 1].toInt() and 0xFF
        val b = rgba[i + 2].toInt() and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    /** Count of dark pixels along a 1px-tall (or 1px-wide) band — robust to AA fringe placement. */
    private fun darkCount(rgba: ByteArray, w: Int, xFrom: Int, xTo: Int, y: Int): Int {
        var n = 0
        for (x in xFrom..xTo) if (lum(rgba, w, x, y) < 90) n++
        return n
    }

    @Test
    fun `stroke-only circle renders as a hollow outline not a black disc`() {
        val graph = SceneGraph(
            nodes = mapOf("c" to CircleNode(
                "c", "chart", ResolvedBox(50.0, 50.0, 100.0, 100.0),
                Style(fill = null, stroke = Color.BLACK, strokeWidth = 3.0),
            )),
            rootIds = listOf("c"),
            canvas = CanvasSpec(200.0, 200.0),
        )
        val px = rgba(render(graph))
        assertTrue(lum(px, 200, 100, 100) > 240, "center must be hollow (no fill)")
        // top boundary band: the 3px stroke around the circle's top (y≈50)
        assertTrue(darkCount(px, 200, 96, 104, 49) >= 3, "top boundary band must be stroked dark")
        assertTrue(darkCount(px, 200, 96, 104, 51) >= 3, "inner band of the top stroke must be dark")
        // right boundary: vertical band along the circle's right edge (x≈150)
        val rightDark = (96..104).count { y -> lum(px, 200, 149, y) < 128 }
        assertTrue(rightDark >= 4, "right boundary band must be stroked dark (dark=$rightDark)")
    }

    @Test
    fun `rect with fill and stroke shows fill inside and dark outline`() {
        val graph = SceneGraph(
            nodes = mapOf("r" to RectNode(
                "r", "button", ResolvedBox(30.0, 30.0, 80.0, 60.0),
                Style(fill = Color.rgb(220, 220, 220), stroke = Color.BLACK, strokeWidth = 4.0),
            )),
            rootIds = listOf("r"),
            canvas = CanvasSpec(200.0, 200.0),
        )
        val px = rgba(render(graph))
        assertTrue(lum(px, 200, 70, 60) > 190, "center must be the light fill")
        assertTrue(lum(px, 200, 31, 60) < 90, "left edge must be the dark stroke")
    }

    @Test
    fun `legacy default-style node still renders as black ink`() {
        val graph = SceneGraph(
            nodes = mapOf("c" to CircleNode(
                "c", "rivet", ResolvedBox(80.0, 80.0, 40.0, 40.0), Style(),
            )),
            rootIds = listOf("c"),
            canvas = CanvasSpec(200.0, 200.0),
        )
        val px = rgba(render(graph))
        assertTrue(lum(px, 200, 100, 100) < 90, "default-style node keeps the black-ink fallback")
    }

    @Test
    fun `rounded corner rect rasterizes differently from the sharp corner`() {
        fun graph(radius: Double) = SceneGraph(
            nodes = mapOf("r" to RectNode(
                "r", "btn", ResolvedBox(30.0, 30.0, 80.0, 60.0),
                Style(fill = Color.rgb(200, 200, 200), stroke = Color.BLACK, strokeWidth = 2.0, cornerRadius = radius),
            )),
            rootIds = listOf("r"),
            canvas = CanvasSpec(200.0, 200.0),
        )
        val sharp = rgba(render(graph(0.0)))
        val rounded = rgba(render(graph(12.0)))
        assertEquals(sharp.size, rounded.size)
        assertFalse(sharp.contentEquals(rounded), "cornerRadius must change the rasterized output")
    }
}