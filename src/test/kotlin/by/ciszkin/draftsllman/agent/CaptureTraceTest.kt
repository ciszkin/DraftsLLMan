package by.ciszkin.draftsllman.agent

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import kotlin.test.Test

/**
 * Opt-in (DRAFTS_CAPTURE=1): runs the agent live against the default model with a RecordingPromptExecutor and
 * writes the captured trace to the replay fixture. Run once (remote reachable) to (re)generate the fixture;
 * NOT in normal CI. After this, RecordedReplayTest replays the real trace.
 */
@EnabledIfEnvironmentVariable(named = "DRAFTS_CAPTURE", matches = "1")
class CaptureTraceTest {
    @Test
    fun `capture a real run with the default model for the milestone prompt`() {
        val recorder = RecordingPromptExecutor(liveOllamaPromptExecutor())
        val cli = TextToSvgCli(
            prompt = "a circle centered above a rectangle",
            promptExecutor = recorder,
        )
        val outcome = cli.run()
        check(outcome.status == RunStatus.DONE) { "capture did not finish DONE: $outcome" }

        val fixture = Trace.encodeToString(recorder.trace())
        File("src/test/resources/agent/recorded-traces/circle-above-rectangle.json").writeText(fixture)
    }
}
