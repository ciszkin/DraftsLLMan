package by.ciszkin.draftsllman.eval

import by.ciszkin.draftsllman.render.SkiaRenderer
import by.ciszkin.draftsllman.scene.SceneGraph
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skiko.Library
import kotlin.random.Random

/**
 * M1 eval-framework Sketchify step ("destroy the perfection", §3.1).
 *
 * Takes a CLEAN [SceneGraph] (e.g. produced by WireframeDSL) and renders it as a hand-drawn-looking
 * PNG: the perfect vector rasterization is perturbed deterministically so a vision-first pipeline
 * sees something closer to a real sketch. [jitterLevel] 0..3 controls messiness (eval-framework §8);
 * [seed] (combined with [jitterLevel]) drives every random choice, so the same
 * (graph, seed, jitterLevel) always yields byte-identical output.
 *
 * The perturbation is deliberately pixel-level and geometry-free, in order of increasing cost:
 *  1. whole-canvas rotation by a small seeded angle (-4..+4 deg),
 *  2. a light blur approximating stroke-width variation / smear (strength scales with jitterLevel),
 *  3. per-pixel grayscale paper/scan noise, plus occasional white dropout specks at the top
 *     jitter levels. Perturbations stay subtle — never strokes that could be read as components.
 *
 * Determinism: Skia's rasterizer output for a given graph is deterministic, and every random draw
 * here comes from a single seeded [Random], so the full pipeline is reproducible.
 */
class Sketchify(
    private val jitterLevel: Int = 1,      // 0..3 messiness (eval-framework §8)
    private val seed: Long = 42,
) {
    init {
        require(jitterLevel in 0..3) { "jitterLevel must be 0..3, got $jitterLevel" }
    }

    /** Render the clean graph to a PNG, then perturb pixels to look hand-drawn. */
    fun sketch(graph: SceneGraph): ByteArray {
        Library.load()
        return sketchPng(SkiaRenderer.render(graph))
    }

    /**
     * Perturb an EXISTING raster (any size) exactly like [sketch] would — same seeded rotation,
     * blur, noise. Lets callers feed any raster (e.g. the SVG-faithful Skia render, which now draws
     * strokes). Size comes from the decoded image.
     */
    fun sketchPng(png: ByteArray): ByteArray {
        Library.load()
        val rand = Random(seed * 31 + jitterLevel * 7L + 13)
        val src = Image.makeFromEncoded(png)
            ?: error("sketchPng: cannot decode input PNG")
        try {
            val w = src.width
            val h = src.height
            if (w <= 0 || h <= 0) error("sketchPng: input PNG has zero size")
            val rotated = rotateAndBlur(src, w, h, rand)
            try {
                val bitmap = Bitmap().also { it.allocN32Pixels(w, h, false) }
                check(rotated.readPixels(bitmap, 0, 0)) { "Skia readPixels failed in Sketchify (rotated image)" }
                perturb(bitmap, rand, w, h)
                return encodePixels(bitmap, w, h)
            } finally {
                rotated.close()
            }
        } finally {
            src.close()
        }
    }

    // -- perturbation stages -------------------------------------------------

    /** Stages 1+2: draw the source rotated by a seeded angle, optionally blurred. */
    private fun rotateAndBlur(src: Image, w: Int, h: Int, rand: Random): Image {
        val angle = -4.0 + rand.nextDouble() * 8.0
        val blurSigma = when (jitterLevel) {
            0 -> 0f
            1 -> 0.3f
            2 -> 0.6f
            else -> 1.0f
        }
        val surface = Surface.makeRaster(ImageInfo.makeN32Premul(w, h))
        try {
            surface.canvas.clear(Color.WHITE)
            drawCenteredRotated(surface.canvas, src, w, h, angle, blurSigma)
            return surface.makeImageSnapshot()
        } finally {
            surface.close()
        }
    }

    /** Rotate the whole canvas about its center and draw [src] over it, LINEAR sampling. */
    private fun drawCenteredRotated(
        canvas: Canvas,
        src: Image,
        w: Int,
        h: Int,
        angleDeg: Double,
        blurSigma: Float,
    ) {
        canvas.save()
        try {
            canvas.rotate(angleDeg.toFloat(), w / 2f, h / 2f)
            Paint().use { paint ->
                if (blurSigma > 0f) {
                    paint.isAntiAlias = true
                    paint.maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, blurSigma, true)
                }
                // Overscan margin so the rotated corners still read as the paper background.
                val margin = 24f
                val dst = Rect.makeLTRB(-margin, -margin, w + margin, h + margin)
                canvas.drawImageRect(
                    src, Rect.makeWH(src.width.toFloat(), src.height.toFloat()),
                    dst, SamplingMode.LINEAR, paint, false,
                )
            }
        } finally {
            canvas.restore()
        }
    }

    /** Stage 3: per-pixel grayscale noise + white dropout specks at high jitter levels. */
    private fun perturb(bitmap: Bitmap, rand: Random, w: Int, h: Int) {
        val noiseAmp = when (jitterLevel) {
            0 -> 0
            1 -> 5
            2 -> 10
            else -> 16
        }
        val dropoutSpecks = jitterLevel >= 3

        // Same layout as the bitmap (N32 premul), so defaults (rowBytes = bitmap.rowBytes) apply.
        val bytes = bitmap.readPixels(bitmap.imageInfo) ?: error("Skia readPixels failed in Sketchify (perturb)")

        var speckBudget = (w * h) / 300
        var i = 0
        while (i + 3 < bytes.size) {
            val flick = if (noiseAmp > 0 && rand.nextFloat() < 0.3f) {
                rand.nextInt(-noiseAmp, noiseAmp + 1)
            } else 0
            if (flick != 0) {
                bytes[i] = (bytes[i].toInt() + flick).coerceIn(0, 255).toByte()
                bytes[i + 1] = (bytes[i + 1].toInt() + flick).coerceIn(0, 255).toByte()
                bytes[i + 2] = (bytes[i + 2].toInt() + flick).coerceIn(0, 255).toByte()
                // alpha left untouched
            }
            if (dropoutSpecks && speckBudget > 0 && rand.nextInt(3600) == 0) {
                speckBudget--
                val x = i / 4 % w
                val y = i / 4 / w
                if (x + 1 < w && y + 1 < h) {
                    val base = (y * w + x) * 4
                    for (dy in 0..1) {
                        for (dx in 0..1) {
                            val at = base + (dy * w + dx) * 4
                            if (at + 3 < bytes.size) {
                                bytes[at] = 0xFF.toByte()
                                bytes[at + 1] = 0xFF.toByte()
                                bytes[at + 2] = 0xFF.toByte()
                                bytes[at + 3] = 0xFF.toByte()
                            }
                        }
                    }
                }
            }
            i += 4
        }

        bitmap.installPixels(bytes)
    }

    /** Encode the perturbed bitmap back to a PNG. */
    private fun encodePixels(bitmap: Bitmap, w: Int, h: Int): ByteArray {
        val out = Surface.makeRaster(ImageInfo.makeN32Premul(w, h))
        try {
            out.canvas.clear(Color.WHITE)
            Image.makeFromBitmap(bitmap).use { img ->
                out.canvas.drawImage(img, 0f, 0f)
            }
            return out.makeImageSnapshot().use { img ->
                img.encodeToData(EncodedImageFormat.PNG, 100)?.use { it.bytes }
                    ?: error("Skia encodeToData returned null in Sketchify")
            }
        } finally {
            out.close()
        }
    }
}