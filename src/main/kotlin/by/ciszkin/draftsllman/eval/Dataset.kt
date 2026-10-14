package by.ciszkin.draftsllman.eval

import by.ciszkin.draftsllman.render.SkiaRenderer
import by.ciszkin.draftsllman.scene.CanvasSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** A sketchify backend: turns a clean [Wireframe] into a messy hand-drawn-looking PNG for [seed]. */
fun interface SketchifyBackend {
    fun sketch(wf: Wireframe, seed: Long): ByteArray
}

/** One materialized, integrity-gated dataset pair. File names are relative to the case's directory. */
@Serializable
data class DatasetEntry(
    val id: String,
    val seed: Int,
    val source: Source,
    val difficulty: Difficulty,
    val cleanFile: String,
    val sketchFile: String,
    val truthFile: String,
    val gatePassed: Boolean,
    val violations: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class DatasetManifest(
    val entries: List<DatasetEntry>,
) {
    /**
     * Re-loads the materialized pairs as [TestCase]s (sketch PNG bytes + parsed truth) — the input
     * the vision-first pipeline (S1) and the T0/T3 scorers consume.
     */
    fun loadCases(outDir: File, json: Json = Dataset.json): List<TestCase> = entries.map { entry ->
        val dir = File(outDir, entry.id)
        val truth = json.decodeFromString(
            GroundTruth.serializer(), File(dir, entry.truthFile).readText(),
        )
        TestCase(
            id = entry.id,
            sketchPng = File(dir, entry.sketchFile).readBytes(),
            source = entry.source,
            difficulty = entry.difficulty,
            groundTruth = truth,
        )
    }
}

/**
 * Materializes labeled (clean PNG, sketch PNG, truth JSON) pairs into [outDir] and writes
 * `manifest.json`. Uses [IntegrityGate] as guardrail #1 (M1 plan): when [enforceGate] is true, any
 * sketch that lost a truth component aborts the build with the offending case ids, so the dataset
 * can never contain a pair whose label no longer matches its image.
 */
object DatasetBuilder {

    fun build(
        cases: List<Pair<Int, Difficulty>>,
        outDir: File,
        backend: SketchifyBackend,
        enforceGate: Boolean = true,
        retries: Int = 2,
        json: Json = Dataset.json,
    ): DatasetManifest {
        outDir.mkdirs()
        val entries = cases.map { (seed, difficulty) ->
            val wf = WireframeDSL().build(seed)
            val id = "wf-$seed"
            val dir = File(outDir, id).apply { mkdirs() }
            val cleanFile = "clean.png"
            val sketchFile = "sketch.png"
            val truthFile = "truth.json"

            File(dir, cleanFile).writeBytes(SkiaRenderer.render(wf.graph))
            File(dir, truthFile).writeText(json.encodeToString(GroundTruth.serializer(), wf.truth))

            // Generation is cheap, so a rejected sketch (a component lost to the edit drift) is
            // simply regenerated with a different seed — keep the first gate-passing attempt; if
            // every attempt fails, the last attempt's file + gate=false entry document the reject.
            var sketch: ByteArray = ByteArray(0)
            var gate: GateResult? = null
            val attemptSeeds = (0..retries).map { seed.toLong() + it * 1_000L }
            for (attemptSeed in attemptSeeds) {
                sketch = backend.sketch(wf, attemptSeed)
                gate = IntegrityGate.check(sketch, wf.truth)
                if (gate.passed) break
            }
            writeSketch(dir, sketchFile, sketch)

            DatasetEntry(
                id = id, seed = seed, source = Source.SYNTHETIC, difficulty = difficulty,
                cleanFile = cleanFile, sketchFile = sketchFile, truthFile = truthFile,
                gatePassed = gate?.passed == true, violations = gate?.violations.orEmpty(),
                warnings = gate?.warnings.orEmpty(),
            )
        }
        val manifest = DatasetManifest(entries)
        File(outDir, "manifest.json").writeText(
            json.encodeToString(DatasetManifest.serializer(), manifest),
        )
        if (enforceGate) {
            val rejected = entries.filterNot { it.gatePassed }
            require(rejected.isEmpty()) {
                "integrity gate rejected ${rejected.map { it.id }} after retries; refusing to materialize a poisoned dataset"
            }
        }
        return manifest
    }

    private fun writeSketch(dir: File, sketchFile: String, sketch: ByteArray): Boolean {
        if (sketch.isEmpty()) return false
        File(dir, sketchFile).writeBytes(sketch)
        return true
    }
}

/** Shared JSON configuration for eval fixtures (pretty by default; used for the manifest and truth). */
object Dataset {
    val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }
}

/** Convenience: [CanvasSpec] for the standard eval wireframe canvas. */
val EVAL_CANVAS: CanvasSpec = CanvasSpec(900.0, 640.0)