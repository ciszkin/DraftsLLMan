package by.ciszkin.draftsllman.eval

import by.ciszkin.draftsllman.scene.svg.SvgExporter
import org.jetbrains.skiko.Library
import java.io.File
import kotlin.test.Test

/**
 * MANUAL-PREVIEW helper (not an assertion suite): renders a small matrix of synthetic fixtures to
 * `output/eval-preview/` so a human can eyeball Sketchify realism — the ONE judgment the automated
 * suite cannot make. Run with:
 *   ./gradlew test --tests "by.ciszkin.draftsllman.eval.SketchPreviewTest"
 *
 * What to look at per (seed, jitter) pair:
 *  - clean-<seed>.svg        the wireframe as generated (open in a browser; is it a plausible UI
 *                            wireframe with zones, a focal chart, buttons and wavy annotations?)
 *  - sketch-<seed>-j<j>.png  Sketchify's hand-drawn look at jitter level j (0..3)
 *                            (compare against the reference blue-ink sample aesthetic)
 *  - truth-<seed>.json       the ground truth summary (component roles/kinds + annotationIds)
 *
 * Nothing is asserted here — it exists to put files on disk for human review.
 */
class SketchPreviewTest {

    @Test
    fun `generate preview fixtures for manual review`() {
        Library.load()
        val outDir = File("output/eval-preview").apply { mkdirs() }

        val seeds = listOf(1, 7, 42)
        val jitters = listOf(0, 1, 2, 3)

        for (seed in seeds) {
            val wf = WireframeDSL().build(seed)
            val svg = SvgExporter.emit(wf.graph)
            File(outDir, "clean-$seed.svg").writeText(svg)

            for (j in jitters) {
                val bytes = Sketchify(jitterLevel = j, seed = seed.toLong()).sketch(wf.graph)
                File(outDir, "sketch-$seed-j$j.png").writeBytes(bytes)
            }

            val summary = buildString {
                appendLine("seed=$seed canvas=${wf.truth.canvas.width}x${wf.truth.canvas.height}")
                for (c in wf.truth.components) {
                    appendLine("  ${c.id}  ${c.role}  ${c.kind}  box=(${c.box.x},${c.box.y},${c.box.width},${c.box.height})")
                }
                appendLine("  EDGES: " + wf.truth.edges.joinToString("; ") { "${it.from}->${it.to}:${it.relation}" })
                appendLine("  ANNOTATIONS (must be excluded): " + wf.truth.annotationIds.joinToString(", "))
            }
            File(outDir, "truth-$seed.txt").writeText(summary)
        }
        println("Preview fixtures written to ${outDir.absolutePath}")
    }
}