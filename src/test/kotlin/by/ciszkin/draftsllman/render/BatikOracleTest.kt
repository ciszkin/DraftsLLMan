package by.ciszkin.draftsllman.render

import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.Color
import by.ciszkin.draftsllman.scene.RectNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.Style
import by.ciszkin.draftsllman.scene.svg.SvgExporter
import org.apache.batik.transcoder.SVGAbstractTranscoder.KEY_HEIGHT
import org.apache.batik.transcoder.SVGAbstractTranscoder.KEY_WIDTH
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.ImageTranscoder.KEY_BACKGROUND_COLOR
import org.apache.batik.transcoder.image.PNGTranscoder
import org.jetbrains.skiko.Library
import java.awt.Color as AwtColor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

class BatikOracleTest {
    @Test fun `skia direct-canvas and batik-from-svg agree on a filled rect with white bg both sides`() {
        Library.load()
        val graph = SceneGraph(
            nodes = mapOf("r" to RectNode("r", "box", ResolvedBox(20.0, 20.0, 60.0, 60.0), Style(fill = Color.rgb(0, 0, 0)))),
            rootIds = listOf("r"),
            canvas = CanvasSpec(100.0, 100.0),
        )

        val skiaPng = SkiaRenderer.render(graph)
        val batikPng = rasterizeWithBatik(SvgExporter.emit(graph), 100, 100)

        val skiaImg = ImageIO.read(skiaPng.inputStream())
        val batikImg = ImageIO.read(batikPng.inputStream())
        val diff = pixelDiffFraction(skiaImg, batikImg)
        assertTrue(diff < 0.05, "export vs raster drift too high: ${diff * 100}% pixels differ")
    }

    private fun rasterizeWithBatik(svg: String, w: Int, h: Int): ByteArray {
        val t = PNGTranscoder().apply {
            addTranscodingHint(KEY_WIDTH, w.toFloat())
            addTranscodingHint(KEY_HEIGHT, h.toFloat())
            addTranscodingHint(KEY_BACKGROUND_COLOR, AwtColor.WHITE)
        }
        val out = ByteArrayOutputStream()
        t.transcode(TranscoderInput(svg.byteInputStream()), TranscoderOutput(out))
        return out.toByteArray()
    }

    private fun pixelDiffFraction(a: BufferedImage, b: BufferedImage): Double {
        if (b.width != a.width || b.height != a.height) return 1.0
        var diff = 0
        for (y in 0 until a.height) for (x in 0 until a.width)
            if (a.getRGB(x, y) != b.getRGB(x, y)) diff++
        return diff.toDouble() / (a.width * a.height)
    }
}
