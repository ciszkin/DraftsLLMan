package by.ciszkin.draftsllman.agent

import by.ciszkin.draftsllman.engine.ToolExecutor
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.RectNode
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/** Replays the recorded trace and asserts the milestone scene graph (circle above a rectangle). */
class RecordedReplayTest {

    private val model = LLModel(provider = LLMProvider.Ollama, id = "test") // unused by scripted replay

    @Test fun `replaying the fixture draws a circle above a rectangle`() {
        val trace = Trace.fromResource("/agent/recorded-traces/circle-above-rectangle.json")
            ?: error("fixture missing — run CaptureTraceTest (Task 7) to generate it")

        val executor = ToolExecutor.default()
        val promptExecutor = ScriptedPromptExecutor().apply { enqueueTrace(trace) }
        val runner = AgentRunner(executor)
        val agent = DraftsAgent(executor, promptExecutor, model, installFeatures = runner.installNoOpFeature).agent()

        val outcome = runner.run(agent, "a circle centered above a rectangle")

        assertEquals(RunStatus.DONE, outcome.status)
        val nodes = executor.engine().current.nodes
        assertTrue(nodes.values.any { it is CircleNode }, "expected at least one circle; nodes=$nodes")
        assertTrue(nodes.values.any { it is RectNode }, "expected at least one rectangle; nodes=$nodes")

        val svg = executor.exportSvg()
        assertTrue(svg.contains("<circle"), "SVG missing <circle>: $svg")
        assertTrue(svg.contains("<rect"), "SVG missing <rect>: $svg")
    }
}
