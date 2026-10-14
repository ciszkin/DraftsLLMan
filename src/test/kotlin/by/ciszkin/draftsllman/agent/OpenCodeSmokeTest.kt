package by.ciszkin.draftsllman.agent

import by.ciszkin.draftsllman.architect.ArchitectRunner
import by.ciszkin.draftsllman.scene.CanvasSpec
import java.io.PrintWriter
import kotlin.test.Test

/** Gated koog-level smoke: can the Anthropic client talk to OpenCode Go at all (text-only prompt)? */
class OpenCodeSmokeTest {
    @Test fun `architect text roundtrip through OpenCode Go`() {
        if (System.getenv("DRAFTS_OPENCODE_SMOKE") != "1") return
        val executor = livePromptExecutor()
        val model = DEFAULT_LLM
        println("provider=${model.provider} model=${model.id}")
        val tracer = Tracer(file = PrintWriter("output/oc-smoke-trace.log"))
        val outcome = ArchitectRunner(executor, model, tracer = tracer)
            .run("Reply with the word ok and nothing else. Do not call any tool.", CanvasSpec(200.0, 120.0))
        println("ARCHITECT-OUTCOME: ${outcome.status}")
    }
}
