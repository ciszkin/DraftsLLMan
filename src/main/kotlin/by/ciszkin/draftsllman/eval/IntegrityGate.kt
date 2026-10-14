package by.ciszkin.draftsllman.eval

import by.ciszkin.draftsllman.scene.ResolvedBox
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skiko.Library

/** Outcome of [IntegrityGate.check]: hard [violations] (structural damage) vs soft [warnings]. */
data class GateResult(
    val passed: Boolean,
    val violations: List<String>,
    val warnings: List<String>,
) {
    companion object {
        val OK = GateResult(passed = true, violations = emptyList(), warnings = emptyList())
    }
}

/**
 * The dirty-sketch integrity gate (M1 guardrail #1): a generated sketch may only join the dataset if
 * EVERY ground-truth component is still VISIBLE in it. The qwen-image edit step reproduces the layout
 * with hand-drawn style but occasionally drifts/drops elements; such variants would poison the labeled
 * Truth, so they must be rejected here and regenerated.
 *
 * Mechanism: decode the sketch PNG, downsample to a coarse ink grid (<= ~96x96), and require each
 * ground-truth component's (shrunk) region to contain at least [minInkFractionInRegion] dark pixels.
 * Additional guards: a nearly-all-dark image (over-inked / solid black) is rejected; dropped
 * annotations are reported as [warnings] (they matter for the discrimination metric, but are not
 * structural damage).
 */
object IntegrityGate {

    fun check(
        sketchPng: ByteArray,
        truth: GroundTruth,
        // Hand-drawn ink is often mid-gray (pen pressure), not near-black — a strict threshold
        // falsely rejects valid sketches (live finding: qwen draws strokes at ~150-190 luma).
        // 192 = dark pen strokes + gray-blue ink; excludes light fills and paper texture.
        inkThreshold: Int = 192,
        minInkFractionInRegion: Double = 0.01,
        overInkFraction: Double = 0.95,
    ): GateResult {
        Library.load()
        val image = Image.makeFromEncoded(sketchPng)
            ?: return GateResult(false, listOf("sketch PNG could not be decoded"), emptyList())
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) return GateResult(false, listOf("sketch PNG has zero size"), emptyList())

        val rgba = readRgba(image, w, h)

        // Full-resolution ink decisions (a 1.5px outline is missed by any coarse grid sampling).
        val totalPx = w * h
        var darkPx = 0
        for (i in 0 until totalPx) if (luminance(rgba, i) < inkThreshold) darkPx++
        val overInked = darkPx.toDouble() / totalPx >= overInkFraction

        val canvas = truth.canvas
        val sx = w / canvas.width
        val sy = h / canvas.height

        val violations = mutableListOf<String>()
        if (overInked) violations += "sketch is >$overInkFraction ink (solid black / over-inked output)"

        for (comp in truth.components) {
            val region = fullRegion(comp.box, sx, sy, w, h)
            if (!regionHasInk(region, rgba, w, minInkFractionInRegion, inkThreshold)) {
                violations += "no ink in component region '${comp.id}' (${comp.role})"
            }
        }

        val warnings = mutableListOf<String>()
        for ((id, box) in truth.annotationBoxes) {
            val region = fullRegion(box, sx, sy, w, h)
            if (!regionHasInk(region, rgba, w, minInkFractionInRegion, inkThreshold)) {
                warnings += "annotation mark '$id' not visible in the sketch"
            }
        }

        return GateResult(violations.isEmpty(), violations, warnings)
    }

    /** Decompress [image] into a flat RGBA byte array (order-robust, 4 bytes/pixel). */
    private fun readRgba(image: Image, w: Int, h: Int): ByteArray {
        val bmp = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(w, h)) }
        try {
            check(image.readPixels(bmp, 0, 0)) { "readPixels failed for decoded sketch" }
            val rgba = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, null)
            return bmp.readPixels(rgba, w * 4, 0, 0)
                ?: error("readPixels returned null for decoded sketch")
        } finally {
            bmp.close()
        }
    }

    /**
     * Full region of a truth box in the sketch's pixel space. The FULL box is used — the ink of e.g.
     * a light-filled container lives on its OUTLINE strokes, so shrinking the region would strip the
     * only ink. Degenerate boxes (zero-height divider lines) expand to an 8px band around their axis.
     */
    private fun fullRegion(
        box: ResolvedBox,
        sx: Double,
        sy: Double,
        imgW: Int,
        imgH: Int,
    ): IntArray { // [x0, y0, x1, y1) in pixels
        fun band(coord: Double, size: Double, maxCoord: Double): Pair<Double, Double> {
            var a = coord
            var b = coord + size
            if (b - a < 8.0) { // degenerate -> expand to a band
                val c = (a + b) / 2.0
                a = c - 4.0
                b = c + 4.0
            }
            return a.coerceIn(0.0, maxCoord) to b.coerceIn(0.0, maxCoord)
        }
        val (x0, x1) = band(box.x * sx, box.width * sx, imgW.toDouble())
        val (y0, y1) = band(box.y * sy, box.height * sy, imgH.toDouble())
        return intArrayOf(x0.toInt(), y0.toInt(), x1.toInt(), y1.toInt())
    }

    private fun regionHasInk(
        region: IntArray,
        rgba: ByteArray,
        imgW: Int,
        minFraction: Double,
        inkThreshold: Int,
    ): Boolean {
        val x0 = region[0]
        val y0 = region[1]
        val x1 = region[2]
        val y1 = region[3]
        var total = 0
        var dark = 0
        for (py in y0 until y1) {
            var idx = py * imgW + x0
            for (px in x0 until x1) {
                total++
                if (luminance(rgba, idx) < inkThreshold) dark++
                idx++
            }
        }
        return total == 0 || dark.toDouble() / total >= minFraction
    }

    /** Rec.709 luminance from a flat RGBA byte array at pixel index [i] (4 bytes/pixel). */
    private fun luminance(rgba: ByteArray, i: Int): Double {
        val r = rgba[i * 4].toInt() and 0xFF
        val g = rgba[i * 4 + 1].toInt() and 0xFF
        val b = rgba[i * 4 + 2].toInt() and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }
}