package by.ciszkin.draftsllman.architect

import by.ciszkin.draftsllman.agent.DEFAULT_LLM
import by.ciszkin.draftsllman.agent.RecordingPromptExecutor
import by.ciszkin.draftsllman.agent.Trace
import by.ciszkin.draftsllman.agent.liveOllamaPromptExecutor
import by.ciszkin.draftsllman.scene.CanvasSpec
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import kotlin.test.Test

/**
 * Opt-in (DRAFTS_CAPTURE=1): runs the Architect live against the model with a RecordingPromptExecutor and
 * writes the captured trace to the replay fixture. Run once (remote reachable) to (re)generate the fixture;
 * NOT in normal CI. Reuses Slice C's RecordingPromptExecutor/Trace.
 */
@EnabledIfEnvironmentVariable(named = "DRAFTS_CAPTURE", matches = "1")
class ArchitectCaptureTest {
    @Test fun `capture a real architect run for the two-boxes prompt`() {
        val recorder = RecordingPromptExecutor(liveOllamaPromptExecutor())
        val outcome = ArchitectRunner(recorder, DEFAULT_LLM).run("two boxes side by side", CanvasSpec(100.0, 100.0))
        check(outcome.status == ArchitectStatus.SKELETON) { "capture did not emit skeleton: $outcome" }

        val fixture = Trace.encodeToString(recorder.trace())
        File("src/test/resources/architect/recorded-traces/two-boxes.json").writeText(fixture)
    }
}
