package by.ciszkin.draftsllman.agent

import ai.koog.prompt.Prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.MessagePart
import by.ciszkin.draftsllman.engine.ToolExecutor
import by.ciszkin.draftsllman.scene.Anchor
import by.ciszkin.draftsllman.solver.SceneConstraint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end Koog agent loop, driven by [ScriptedPromptExecutor] (no network). Each test wires
 * [DraftsAgent] + [AgentRunner] and asserts both the final scene-graph state and the [RunOutcome.status].
 */
class DraftsAgentTest {

    private val model: LLModel = LLModel(provider = LLMProvider.Ollama, id = "test")

    /** Prime the canvas + any pre-existing nodes the agent will reference (setup bypasses the agent). */
    private fun ToolExecutor.setup(vararg nodes: String) {
        execute("""{"type":"set_view_box","width":200,"height":120}""")
        nodes.forEach { id ->
            execute("""{"type":"add_node","id":"$id","role":"seed","kind":"circle","width":10.0,"height":10.0,"preferredX":0.0,"preferredY":0.0}""")
        }
    }

    @Test
    fun `happy path - add node and constraint, then DONE`() {
        val executor = ToolExecutor.default()
        executor.setup("b")
        val promptExecutor = ScriptedPromptExecutor().apply {
            enqueueToolCall("add_node", """{"id":"a","role":"wheel","kind":"circle","width":20.0,"height":20.0,"preferredX":10.0,"preferredY":10.0}""")
            enqueueToolCall("add_constraint", """{"id":"c1","kind":"align","sourceId":"a","sourceAnchor":"LEFT","targetId":"b","targetAnchor":"LEFT"}""")
            enqueueDone()
        }

        val runner = AgentRunner(executor)
        val agent = DraftsAgent(executor, promptExecutor, model, installFeatures = runner.installNoOpFeature).agent()
        val outcome = runner.run(agent, "draw a wheel aligned to the base")

        assertEquals(RunStatus.DONE, outcome.status)
        assertTrue(executor.engine().current.nodes.containsKey("a"), "node 'a' should have been added")
        assertTrue(
            executor.engine().current.constraints.constraints.any { it.id == "c1" },
            "constraint 'c1' should have been added"
        )
    }

    @Test
    fun `rollback recovery - a rejected tool call is followed by a corrected one`() {
        val executor = ToolExecutor.default()
        executor.setup("x", "y")
        val promptExecutor = ScriptedPromptExecutor().apply {
            // BAD: align LEFT (X) with TOP (Y) — incompatible axes -> Rejected, graph rolled back.
            enqueueToolCall("add_constraint", """{"id":"c1","kind":"align","sourceId":"x","sourceAnchor":"LEFT","targetId":"y","targetAnchor":"TOP"}""")
            // CORRECTED: same id, both LEFT (X) -> committed.
            enqueueToolCall("add_constraint", """{"id":"c1","kind":"align","sourceId":"x","sourceAnchor":"LEFT","targetId":"y","targetAnchor":"LEFT"}""")
            enqueueDone()
        }

        val runner = AgentRunner(executor)
        val agent = DraftsAgent(executor, promptExecutor, model, installFeatures = runner.installNoOpFeature).agent()
        val outcome = runner.run(agent, "align x with y")

        assertEquals(RunStatus.DONE, outcome.status)
        val constraints = executor.engine().current.constraints.constraints
        val c1 = constraints.singleOrNull { it.id == "c1" }
        assertIs<SceneConstraint.Align>(c1, "corrected constraint c1 should have committed")
        // The rejected LEFT/TOP version must not have leaked through; only the LEFT/LEFT correction is present.
        assertEquals(Anchor.LEFT, c1.targetAnchor, "c1 should be the corrected LEFT/LEFT version, not the rejected one")
    }

    @Test
    fun `no-op terminates the loop when the scene graph stops changing`() {
        val executor = ToolExecutor.default()
        executor.setup("seed")
        // export_svg never mutates -> the hash repeats across consecutive tool calls until the detector trips.
        val promptExecutor = ScriptedPromptExecutor().apply {
            repeat(4) { enqueueToolCall("export_svg", """{}""") }
        }

        val runner = AgentRunner(executor)
        val agent = DraftsAgent(executor, promptExecutor, model, maxIterations = 20, installFeatures = runner.installNoOpFeature).agent()
        val outcome = runner.run(agent, "stall without progressing")

        assertEquals(RunStatus.NO_OP, outcome.status, "expected no-op termination; got ${outcome.status}")
    }

    @Test
    fun `max iterations terminates the loop`() {
        val executor = ToolExecutor.default()
        executor.setup()
        // Endless mutating tool calls with unique ids (each changes the hash, so no-op never fires first);
        // no DONE -> Koog throws AIAgentMaxNumberOfIterationsReachedException at maxIterations.
        val promptExecutor = ScriptedPromptExecutor().apply {
            repeat(8) { i ->
                enqueueToolCall("add_node", """{"id":"n$i","role":"x","kind":"circle","width":5.0,"height":5.0}""")
            }
        }

        val runner = AgentRunner(executor)
        val agent = DraftsAgent(executor, promptExecutor, model, maxIterations = 3, installFeatures = runner.installNoOpFeature).agent()
        val outcome = runner.run(agent, "loop forever")

        assertEquals(RunStatus.MAX_ITERATIONS, outcome.status, "expected max-iterations termination; got ${outcome.status}")
    }

    @Test
    fun `critique feedback flows back into the model context`() {
        val executor = ToolExecutor.default()
        executor.setup("a")
        val promptExecutor = ScriptedPromptExecutor().apply {
            enqueueToolCall("request_visual_critique", """{"nodeId":"a"}""")
            enqueueDone()
        }

        val runner = AgentRunner(executor)
        val agent = DraftsAgent(executor, promptExecutor, model, installFeatures = runner.installNoOpFeature).agent()
        val outcome = runner.run(agent, "draw then critique")

        assertEquals(RunStatus.DONE, outcome.status)
        // The critique summary (text-heavy feedback) must have been appended to the prompt the model sees next.
        val seenByModelAfterCritique = promptExecutor.prompts.last().flattenedText()
        assertTrue(
            seenByModelAfterCritique.contains("critique rendered"),
            "critique feedback did not reach the model context; prompt text was:\n$seenByModelAfterCritique"
        )
    }
}

/** All textual content in a prompt, including text nested inside tool-result parts (critique feedback lives there). */
private fun Prompt.flattenedText(): String = messages.flatMap { it.parts }.joinToString(" ") { part ->
    when (part) {
        is MessagePart.Text -> part.text
        is MessagePart.Tool.Result -> part.parts.filterIsInstance<MessagePart.Text>().joinToString(" ") { it.text }
        else -> ""
    }
}.trim()
