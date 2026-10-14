package by.ciszkin.draftsllman.eval

import by.ciszkin.draftsllman.render.SkiaRenderer
import by.ciszkin.draftsllman.scene.CanvasSpec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Materializes the deterministic MACRO dev slice (procedural sketch backend — no GPU needed) into
 * output/eval-dataset-macro, so the vision-first pipeline can be scored on the easy tier cheaply.
 * The macro truth (7 components) matches the model caps 1:1, which is the point of difficulty tiering.
 */
class MacroSliceTest {

    @Test
    fun `materialize the macro dev slice with a gate-passing procedural backend`() {
        val out = File("output/eval-dataset-macro")
        // Rebuild each case from WireframeDSL.buildMacro (procedural backend uses a plain scene render).
        val backend = SketchifyBackend { wf, seed ->
            Sketchify(jitterLevel = 1, seed = seed).sketchPng(SkiaRenderer.render(wf.graph))
        }
        val cases = (1..4).map { seed ->
            val wf = WireframeDSL().buildMacro(seed)
            seed to wf
        }
        // Use DatasetBuilder with a small adapter: it builds via WireframeDSL().build(seed) internally,
        // so instead materialize manually to use buildMacro (deterministic, gate-enforced).
        out.mkdirs()
        val entries = cases.map { (seed, wf) ->
            val id = "wf-macro-$seed"
            val dir = File(out, id).apply { mkdirs() }
            File(dir, "clean.png").writeBytes(SkiaRenderer.render(wf.graph))
            var sketch: ByteArray = ByteArray(0)
            var gate: GateResult? = null
            for (attempt in 0..2) {
                sketch = backend.sketch(wf, seed.toLong() + attempt * 1000L)
                gate = IntegrityGate.check(sketch, wf.truth)
                if (gate.passed) break
            }
            File(dir, "sketch.png").writeBytes(sketch)
            val json = Dataset.json.encodeToString(GroundTruth.serializer(), wf.truth)
            File(dir, "truth.json").writeText(json)
            DatasetEntry(
                id = id, seed = seed, source = Source.SYNTHETIC, difficulty = Difficulty(7, 1),
                cleanFile = "clean.png", sketchFile = "sketch.png", truthFile = "truth.json",
                gatePassed = gate?.passed == true, violations = gate?.violations.orEmpty(),
                warnings = gate?.warnings.orEmpty(),
            )
        }
        val manifest = DatasetManifest(entries)
        File(out, "manifest.json").writeText(Dataset.json.encodeToString(DatasetManifest.serializer(), manifest))
        assertTrue(entries.all { it.gatePassed }, "macro slice must pass the gate: ${entries.filterNot { it.gatePassed }.map { it.id }}")
        assertTrue(entries.all { it.id.contains("macro") })
        assertTrue(manifest.loadCases(out).all { it.groundTruth.components.size == 7 }, "macro truth = 7 components")
    }
}
