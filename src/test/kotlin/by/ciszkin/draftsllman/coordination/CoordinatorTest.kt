package by.ciszkin.draftsllman.coordination

import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import by.ciszkin.draftsllman.agent.ScriptedPromptExecutor
import by.ciszkin.draftsllman.agent.Tracer
import by.ciszkin.draftsllman.architect.EmitSkeleton
import by.ciszkin.draftsllman.architect.SkeletonComponent
import by.ciszkin.draftsllman.architect.SkeletonConstraint
import by.ciszkin.draftsllman.scene.CanvasSpec
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoordinatorTest {
    private val model = LLModel(provider = LLMProvider.Ollama, id = "test")
    private val canvas = CanvasSpec(500.0, 500.0)
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false; classDiscriminator = "type" }

    private val bicycle = EmitSkeleton(
        components = listOf(
            SkeletonComponent("rear", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
            SkeletonComponent("front", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
            SkeletonComponent("frame", "chassis", "line", 200.0, 100.0,
                mapOf("rearAxle" to "BOTTOM_LEFT", "frontAxle" to "BOTTOM_RIGHT")),
        ),
        constraints = listOf(
            SkeletonConstraint("lr", "connect", "frame:rearAxle", "rear:hub"),
            SkeletonConstraint("lf", "connect", "frame:frontAxle", "front:hub"),
        ),
    )

    @Test fun `an Architect skeleton solves to a connected SVG with all components placed`() {
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall("emit_skeleton", json.encodeToString(EmitSkeleton.serializer(), bicycle))
            enqueueDone()
            // Phase 3 runs one Draftsman per component (rear, front, frame); feed each an EMPTY finish
            // so the solved skeleton is emitted intact (this is a Phase 1+2 test — no detail asserted).
            enqueueDone(); enqueueDone(); enqueueDone()
        }
        val outcome = Coordinator(scripted, model, canvas).run("bicycle")

        assertTrue(outcome.architectProducedSkeleton, "the Architect should have produced a skeleton")
        assertEquals(3, outcome.componentCount, "all three components placed")
        assertTrue(outcome.svg.contains("""<circle id="rear"""), "rear wheel present: ${outcome.svg}")
        assertTrue(outcome.svg.contains("""<circle id="front"""), "front wheel present: ${outcome.svg}")
        assertTrue(outcome.svg.contains("""<line id="frame"""), "frame present: ${outcome.svg}")
    }

    @Test fun `no skeleton yields an empty SVG and no components`() {
        val scripted = ScriptedPromptExecutor().apply { enqueueDone() }
        val outcome = Coordinator(scripted, model, canvas).run("nothing")
        assertFalse(outcome.architectProducedSkeleton)
        assertEquals(0, outcome.componentCount)
        assertTrue(outcome.svg.contains("<svg"))
        assertFalse(outcome.svg.contains("<circle"))
    }

    /** Spec acceptance (§6): an over-constrained first emit is revised after the gate's
     *  `unsatisfiable` diagnostic, and the REVISED skeleton solves on the PRIMARY path — the trace
     *  must show NO Safe-Harbor trigger. This is the whole point of the revise loop. */
    @Test fun `a revised skeleton solves on the primary path without Safe-Harbor`() {
        val overConstrained = bicycle.copy(
            constraints = bicycle.constraints + SkeletonConstraint(
                "clash", "align", "rear:CENTER_X", "front:CENTER_X",
            ),
        )
        val lines = mutableListOf<String>()
        val tracer = Tracer(briefSink = { lines += it })
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall("emit_skeleton", json.encodeToString(EmitSkeleton.serializer(), overConstrained))
            enqueueToolCall("emit_skeleton", json.encodeToString(EmitSkeleton.serializer(), bicycle))
            enqueueDone() // architect finishes
            enqueueDone(); enqueueDone(); enqueueDone() // three empty Draftsmen
        }
        val outcome = Coordinator(scripted, model, canvas, tracer = tracer).run("bicycle")

        assertTrue(outcome.architectProducedSkeleton, "the revised skeleton should be stashed")
        assertEquals(3, outcome.componentCount)
        assertTrue(outcome.svg.contains("""<circle id="rear"""), "rear wheel missing: ${outcome.svg}")
        assertTrue(outcome.svg.contains("""<circle id="front"""), "front wheel missing: ${outcome.svg}")
        assertTrue(
            lines.none { it.contains("Safe-Harbor") },
            "Safe-Harbor must NOT trigger when the revise loop fixed the skeleton — trace: $lines"
        )
    }

    /** Crash-path guard (Task-3 review finding + 2026-08-22): the validator ACCEPTS constraints on
     *  `group` components (they ride the engine-diagnostic path), and the solver throws
     *  IllegalStateException for groups (no vars) — which escapes `solve` (not an
     *  AddConstraintError) and used to crash the whole run at Phase 2. The Coordinator must
     *  degrade to Safe-Harbor instead. */
    @Test fun `a group-constraint skeleton degrades to Safe-Harbor instead of crashing`() {
        val groupConstrained = EmitSkeleton(
            components = listOf(
                SkeletonComponent("cluster", "assembly", "group", 100.0, 100.0),
                SkeletonComponent("wheel", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
            ),
            constraints = listOf(SkeletonConstraint("bad", "connect", "cluster:CENTER", "wheel:hub")),
        )
        val scripted = ScriptedPromptExecutor().apply {
            // Three failing emits consume the budget; the cap-stash guarantee then carries the
            // group-constrained skeleton into Phase 2 — where the engine throws (no solver vars
            // for groups). That exception must not escape the Coordinator.
            repeat(3) { enqueueToolCall("emit_skeleton", json.encodeToString(EmitSkeleton.serializer(), groupConstrained)) }
            enqueueDone()
            enqueueDone(); enqueueDone() // two empty Draftsmen (cap-stash path proceeds to Phase 3)
        }
        val outcome = Coordinator(scripted, model, canvas).run("cluster")
        // Completes without throwing; the drawing exists (Safe-Harbor or otherwise).
        assertTrue(outcome.svg.startsWith("<svg"), "a drawing must still be emitted: ${outcome.svg}")
    }
}
