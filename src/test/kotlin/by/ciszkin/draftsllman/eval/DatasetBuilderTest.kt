package by.ciszkin.draftsllman.eval

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DatasetBuilder + manifest: materializes labeled (clean PNG, sketch PNG, truth JSON) pairs with an
 * integrity-gated sketch backend, and can re-load them as [TestCase]s. Tests use a deterministic
 * procedural (outline + Sketchify) backend — no network.
 */
class DatasetBuilderTest {

    private fun tempDir(): File = Files.createTempDirectory("eval-dataset-test-").toFile().apply { deleteOnExit() }

    /** Deterministic backend: outlined wireframe -> procedural hand-drawn-ish PNG (gate-passes). */
    private fun proceduralBackend(): SketchifyBackend = SketchifyBackend { wf, s ->
        Sketchify(jitterLevel = 1, seed = s).sketchPng(by.ciszkin.draftsllman.render.SkiaRenderer.render(wf.graph))
    }

    private fun blankBackend(): SketchifyBackend = SketchifyBackend { _, _ ->
        // an all-white "sketch" -> every component region empty -> gate fails
        by.ciszkin.draftsllman.render.SkiaRenderer.render(
            by.ciszkin.draftsllman.scene.SceneGraph(
                canvas = by.ciszkin.draftsllman.scene.CanvasSpec(900.0, 640.0),
            ),
        )
    }

    @Test
    fun `build writes manifest and per-case files for every seed`() {
        val out = tempDir()
        val manifest = DatasetBuilder.build(
            cases = listOf(1 to Difficulty(8, 1), 7 to Difficulty(8, 1)),
            outDir = out,
            backend = proceduralBackend(),
        )
        assertEquals(listOf("wf-1", "wf-7"), manifest.entries.map { it.id })
        for (entry in manifest.entries) {
            val dir = File(out, entry.id)
            assertTrue(File(dir, entry.cleanFile).isFile, "clean.png exists")
            assertTrue(File(dir, entry.sketchFile).isFile, "sketch.png exists")
            assertTrue(File(dir, entry.truthFile).isFile, "truth.json exists")
            assertTrue(entry.gatePassed, "procedural backend must pass the integrity gate: ${entry.warnings}")
        }
        assertTrue(File(out, "manifest.json").isFile, "manifest.json written")
    }

    @Test
    fun `rejected sketches are recorded when the gate is not enforced`() {
        val out = tempDir()
        val manifest = DatasetBuilder.build(
            cases = listOf(3 to Difficulty(8, 1)),
            outDir = out,
            backend = blankBackend(),
            enforceGate = false,
        )
        assertFalse(manifest.entries.single().gatePassed, "blank sketch must be recorded as rejected")
    }

    @Test
    fun `enforcing the gate throws naming rejected cases`() {
        val out = tempDir()
        val e = kotlin.runCatching {
            DatasetBuilder.build(
                cases = listOf(3 to Difficulty(8, 1)),
                outDir = out,
                backend = blankBackend(),
                enforceGate = true,
            )
        }.exceptionOrNull()
        assertTrue(e != null && e.message.orEmpty().contains("wf-3"), "error must name the rejected case: $e")
    }

    @Test
    fun `rejected sketches are retried with a different seed`() {
        val out = tempDir()
        // The backend "fails" (returns blank -> gate rejects) for seed < 100 and produces a
        // gate-passing outlined sketch from seed >= 100 — so the base attempt (seed = 1) must be
        // retried automatically (1 + 1000) and the manifest records a PASSING case.
        val flaky: SketchifyBackend = SketchifyBackend { wf, s ->
            if (s < 100) {
                by.ciszkin.draftsllman.render.SkiaRenderer.render(
                    by.ciszkin.draftsllman.scene.SceneGraph(
                        canvas = by.ciszkin.draftsllman.scene.CanvasSpec(900.0, 640.0),
                    ),
                )
            } else {
                Sketchify(jitterLevel = 1, seed = s).sketchPng(by.ciszkin.draftsllman.render.SkiaRenderer.render(wf.graph))
            }
        }
        val manifest = DatasetBuilder.build(
            cases = listOf(1 to Difficulty(8, 1)),
            outDir = out,
            backend = flaky,
            enforceGate = true,
        )
        val entry = manifest.entries.single()
        assertTrue(entry.gatePassed, "retry with a different seed must produce a passing sketch: ${entry.violations}")
    }

    @Test
    fun `loadCases reconstructs TestCases with matching truth`() {
        val out = tempDir()
        val manifest = DatasetBuilder.build(
            cases = listOf(7 to Difficulty(8, 1)),
            outDir = out,
            backend = proceduralBackend(),
        )
        val cases = manifest.loadCases(out)
        assertEquals(1, cases.size)
        val tc = cases.single()
        assertEquals("wf-7", tc.id)
        assertEquals(Source.SYNTHETIC, tc.source)
        assertTrue(tc.sketchPng.size > 0, "sketch PNG bytes loaded")

        // the loaded truth must have a real shape (components + annotations) matching the DSL seed 7
        val wf = WireframeDSL().build(7)
        assertEquals(wf.truth.components.map { it.id }, tc.groundTruth.components.map { it.id })
        assertEquals(wf.truth.annotationIds, tc.groundTruth.annotationIds)
    }
}