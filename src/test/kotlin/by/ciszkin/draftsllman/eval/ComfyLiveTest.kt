package by.ciszkin.draftsllman.eval

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * LIVE tests against the LAN ComfyUI — skipped unless explicitly enabled, mirroring the repo's
 * opt-in live-test convention (see DRAFTS_LIVE_SMOKE).
 *
 *   DRAFTS_COMFY_SMOKE=1     -> one end-to-end qwen-image-edit run (seed 7) + integrity gate
 *   DRAFTS_COMFY_GENERATE=1  -> materialize a dev slice (seeds 1..6) into output/eval-dataset
 *
 * These hit the real qwen-image-edit workflow and are NOT part of the deterministic CI suite.
 */
class ComfyLiveTest {

    @Test
    fun `qwen edit produces a gate-passing hand-drawn sketch`() {
        if (System.getenv("DRAFTS_COMFY_SMOKE") != "1") return
        val wf = WireframeDSL().build(7)
        val sketch = ComfyUISketchify().sketch(wf, seed = 7L)
        assertTrue(sketch.size > 100, "sketch PNG must be non-trivial")
        val gate = IntegrityGate.check(sketch, wf.truth)
        assertTrue(
            gate.passed,
            "qwen sketch must keep every component visible (violations=${gate.violations}, warnings=${gate.warnings})",
        )
    }

    @Test
    fun `materialize the first dev slice into output eval-dataset`() {
        if (System.getenv("DRAFTS_COMFY_GENERATE") != "1") return
        val out = File("output/eval-dataset")
        val manifest = DatasetBuilder.build(
            cases = (1..6).map { it to Difficulty(componentCount = 8, jitterLevel = 1) },
            outDir = out,
            backend = SketchifyBackend { wf, seed -> ComfyUISketchify().sketch(wf, seed = seed) },
            enforceGate = true,
        )
        assertTrue(manifest.entries.all { it.gatePassed }, "all dev-slice sketches must pass the gate")
    }
}