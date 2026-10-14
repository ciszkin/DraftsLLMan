package by.ciszkin.draftsllman.coordination

import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import by.ciszkin.draftsllman.agent.ScriptedPromptExecutor
import by.ciszkin.draftsllman.architect.EmitSkeleton
import by.ciszkin.draftsllman.architect.SkeletonComponent
import by.ciszkin.draftsllman.architect.SkeletonConstraint
import by.ciszkin.draftsllman.scene.CanvasSpec
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Slice 5 Task 6 acceptance-bar test: detail nodes injected by the Draftsman via `add_nodes` MUST
 * land at the correct GLOBAL positions around the solved component after the per-component run + merge
 * into the final SVG. This is the user's stated motivation — "offset-merge can't connect components →
 * unrecognizable drawings" must be fixed: spokes appear around the actual solved wheel, never at (0,0).
 *
 * The flow is driven end-to-end through the [Coordinator] with a [ScriptedPromptExecutor]:
 *   1. Architect emits a connected bicycle skeleton (rear + front wheels + frame, two connect constraints).
 *   2. Coordinator Phase 2 solves it globally (the solver positions the wheels — we do NOT hard-code).
 *   3. The Rear Draftsman emits `add_nodes` (4 radial spokes); the other two Draftsmen finish empty.
 *   4. Coordinator Phase 3 merges detail into the solved graph and exports the final SVG.
 *
 * We then parse the SVG: read the rear wheel's solved center from its `<circle cx cy>`, read each spoke
 * line's start point from its `<line x1 y1>`, and assert the centroid of the evenly-spaced radial spokes
 * equals the wheel's solved center. This invariant holds regardless of where the solver placed the wheel,
 * so it proves the spokes track the SOLVED GLOBAL position — not the local origin.
 */
class CoordinatorPhase3Test {
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

    @Test fun `Phase 3 detail nodes land at correct global positions around the solved component`() {
        val scripted = ScriptedPromptExecutor().apply {
            // Architect: emit the connected skeleton, then finish.
            enqueueToolCall("emit_skeleton", json.encodeToString(EmitSkeleton.serializer(), bicycle))
            enqueueDone()
            // Rear Draftsman: inject 4 radial spokes around the hub, then finish.
            enqueueToolCall(
                "add_nodes",
                """{"target_id":"rear","node_name":"spoke","node_type":"line","preset":"radial_pattern","count":4,"center_anchor":"hub","boundary_anchor":"TOP_CENTER","displacement":0.5}""",
            )
            enqueueDone()
            // Front Draftsman + Frame Draftsman: finish empty (no detail).
            enqueueDone()
            enqueueDone()
        }
        val outcome = Coordinator(scripted, model, canvas).run("bicycle with spokes")

        // The rear wheel and its 4 spokes must all be present in the final SVG.
        assertTrue(outcome.svg.contains("""<circle id="rear"""), "rear wheel missing: ${outcome.svg}")
        val spokeIds = (0..3).map { "rear_spoke_$it" }
        for (id in spokeIds) {
            assertTrue(outcome.svg.contains("""<line id="$id"""), "spoke $id missing: ${outcome.svg}")
        }

        // Read the rear wheel's SOLVED GLOBAL center straight from the exported SVG.
        val rearCx = doubleAttr(outcome.svg, "rear", "cx")
        val rearCy = doubleAttr(outcome.svg, "rear", "cy")

        // Each spoke is a <line>; its start point (x1, y1) is the radial position. The centroid of
        // evenly-spaced radial points == the circle's center, so the spokes' centroid MUST equal the
        // wheel's solved center — proving detail tracks the solved global position, not (0,0).
        val starts = spokeIds.map { doubleAttr(outcome.svg, it, "x1") to doubleAttr(outcome.svg, it, "y1") }
        val centroidX = starts.map { it.first }.average()
        val centroidY = starts.map { it.second }.average()
        assertApprox(rearCx, centroidX, 0.5, "spoke centroid x ($centroidX) must match rear center ($rearCx)")
        assertApprox(rearCy, centroidY, 0.5, "spoke centroid y ($centroidY) must match rear center ($rearCy)")

        // Defence-in-depth: the spokes must NOT be clustered at the local origin (the offset-merge bug).
        // The bicycle's connect constraints position the rear wheel away from (0,0); the spokes follow.
        assertTrue(abs(centroidX) > 1.0 || abs(centroidY) > 1.0,
            "spokes must not sit at the local origin (0,0): centroid=($centroidX,$centroidY)")
    }

    @Test fun `Phase 3 preserves the skeleton components and connections in the final SVG`() {
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall("emit_skeleton", json.encodeToString(EmitSkeleton.serializer(), bicycle))
            enqueueDone()
            // All three Draftsmen finish empty — Phase 3 must still emit the solved skeleton intact.
            enqueueDone(); enqueueDone(); enqueueDone()
        }
        val outcome = Coordinator(scripted, model, canvas).run("bicycle")

        assertTrue(outcome.architectProducedSkeleton)
        assertEquals(3, outcome.componentCount)
        for (id in listOf("rear", "front", "frame")) {
            assertTrue(outcome.svg.contains("""id="$id"""), "$id missing from solved skeleton: ${outcome.svg}")
        }
    }

    private fun doubleAttr(svg: String, elementId: String, attr: String): Double {
        // id is quote-delimited, so matching id="exactId" is exact (no prefix collision). The trailing
        // \b after the closing quote would FAIL (both " and the following space are non-word), so it is
        // deliberately omitted.
        val elem = Regex("""<\w+\b[^>]*\bid="${Regex.escape(elementId)}"[^>]*/?>""")
            .find(svg) ?: fail("no element with id='$elementId' in svg")
        val pattern = Regex("\\b" + attr + "=\"(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\"")
        val m = pattern.find(elem.value)
            ?: fail("no '$attr' on element id='$elementId': ${elem.value}")
        return m.groupValues[1].toDouble()
    }

    private fun assertApprox(expected: Double, actual: Double, tol: Double, msg: String) {
        assertTrue(abs(expected - actual) <= tol, "$msg (Δ=${expected - actual}, tol=$tol)")
    }
}
