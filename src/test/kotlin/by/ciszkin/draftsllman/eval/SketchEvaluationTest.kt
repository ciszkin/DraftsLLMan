package by.ciszkin.draftsllman.eval

import by.ciszkin.draftsllman.sketch.SketchRunner
import by.ciszkin.draftsllman.sketch.SketchStatus
import by.ciszkin.draftsllman.sketch.SketchOutcome

import ai.koog.prompt.executor.model.PromptExecutor
import by.ciszkin.draftsllman.agent.defaultVisionLLM
import by.ciszkin.draftsllman.agent.RetryPromptExecutor
import by.ciszkin.draftsllman.agent.Tracer
import by.ciszkin.draftsllman.agent.livePromptExecutor
import by.ciszkin.draftsllman.eval.Dataset
import by.ciszkin.draftsllman.eval.PredictionMapper
import by.ciszkin.draftsllman.eval.T0Validity
import by.ciszkin.draftsllman.eval.T3Geometric
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * LIVE decision-gate evaluation of S1 on the materialized dev slice — the numbers the M1 framework
 * exists to produce ("can the vision-first pipeline recover the clean structure from the sketch?").
 * Skipped unless `DRAFTS_SKETCH_EVAL=1`. Uses the live provider (OpenRouter per local.properties).
 *
 * Per TestCase: SketchRunner on the sketch PNG -> Prediction -> T0 invariants + T3 component
 * F1 (Hungarian + role + IoU) + primitive-type accuracy + annotation handling. Prints the table and
 * a PASS/WARN verdict against the tentative decision-gate threshold (T3 F1 ~ 0.80).
 */
class SketchEvaluationTest {

    @Test
    fun `score the dev slice through S1`() {
        if (System.getenv("DRAFTS_SKETCH_EVAL") != "1") return
        val outDir = File(System.getenv("DRAFTS_DATASET_DIR") ?: "output/eval-dataset")
        val manifestFile = File(outDir, "manifest.json")
        assertTrue(manifestFile.isFile, "materialize the dev slice first (DRAFTS_COMFY_GENERATE=1)")

        val manifest = Dataset.json.decodeFromString(
            by.ciszkin.draftsllman.eval.DatasetManifest.serializer(), manifestFile.readText(),
        )
        val cases = manifest.loadCases(outDir).let { all ->
            val filter = System.getenv("DRAFTS_SKETCH_CASES")
            if (filter.isNullOrBlank()) all
            else all.filter { tc -> filter.split(",").any { tc.id.contains(it.trim()) } }
        }
        val executor: PromptExecutor = RetryPromptExecutor(livePromptExecutor())

        println("=== S1 decision-gate evaluation (${cases.size} cases) ===")
        var sumF1 = 0.0
        var sumRel = 0.0
        var sumPrim = 0.0
        var annOk = 0
        var structuralCount = 0
        for (case in cases) {
            val traceFile = File("output/s1-trace-${case.id}.log")
            var outcome: by.ciszkin.draftsllman.sketch.SketchOutcome? = null
            var attempts = 0
            while (outcome == null || (outcome.status != SketchStatus.SKELETON && attempts < 1)) {
                attempts++
                try {
                    outcome = SketchRunner(
                        executor, defaultVisionLLM(), maxIterations = 30,
                        tracer = if (System.getenv("DRAFTS_SKETCH_DEBUG") == "1")
                            Tracer(file = java.io.PrintWriter(traceFile)) else null,
                    ).run(case.sketchPng, case.groundTruth.canvas)
                    if (outcome.status != SketchStatus.SKELETON) {
                        println("  ${case.id}: attempt=$attempts no skeleton, retrying")
                    }
                } catch (e: Exception) {
                println("  ${case.id}: RUN EXCEPTION ${e::class.simpleName}: ${e.message}")
                println("  ${case.id}: TRACE\n${e.stackTraceToString().take(1200)}")
                outcome = null
            }
            }
            if (outcome == null || outcome.status != SketchStatus.SKELETON) continue
            val pred = PredictionMapper.from(outcome!!.graph, fileName = case.id)
            val t0 = T0Validity.predictionInvariants(pred, case.groundTruth.canvas)
            // Semantic axis (the gate): role+kind recovery + coarse relative layout.
            val sem = SemanticScorer.match(pred.components, case.groundTruth.components)
            val f1Sem = SemanticScorer.f1(sem)
            val relCons = SemanticScorer.relationConsistency(pred.components, case.groundTruth.components, sem)
            // Geometric axis (diagnostic): exact IoU placement.
            val geo = T3Geometric.match(pred.components, case.groundTruth.components)
            val f1Geo = T3Geometric.f1(geo)
            val confusion = T3Geometric.confusion(pred.components, case.groundTruth.components)
            val primAcc = confusion.primitiveAccuracy()
            val annPrecision = confusion.annotationPrecision()
            sumF1 += f1Sem; sumRel += relCons; sumPrim += primAcc; structuralCount++

            println(
                "  ${case.id}: status=${outcome.status} direct=${outcome.solvedDirectly} | " +
                    "pred=${pred.components.size} truth=${case.groundTruth.components.size} | " +
                    "F1sem=${"%.3f".format(f1Sem)} relCons=${"%.3f".format(relCons)} | " +
                    "F1geo=${"%.3f".format(f1Geo)} primAcc=${"%.3f".format(primAcc)} | " +
                    "annPrec=${"%.3f".format(annPrecision)} | t0=${if (t0.isEmpty()) "ok" else t0.size}",
            )
        }
        val avgF1 = sumF1 / structuralCount
        val avgRel = sumRel / structuralCount
        val avgPrim = sumPrim / structuralCount
        val gatePassed = avgF1 >= 0.80 && avgRel >= 0.70
        println(
            "=== SUMMARY: avg F1sem=${"%.3f".format(avgF1)} avg relCons=${"%.3f".format(avgRel)} " +
                "avg primitiveAcc=${"%.3f".format(avgPrim)} " +
                "| DECISION-GATE (F1sem >= 0.80 AND relCons >= 0.70, no layout violations): " +
                "${if (gatePassed) "PASS — commit to vision-first" else "WARN — below threshold, consider hybrid"} ===",
        )
    }
}