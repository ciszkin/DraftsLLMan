package by.ciszkin.draftsllman.architect

import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import by.ciszkin.draftsllman.agent.ScriptedPromptExecutor
import by.ciszkin.draftsllman.engine.DraftsEngine
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.solver.SolveResult
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drives the Architect through a scripted executor: valid skeleton emit, rejection-then-correction,
 *  no-emit (NO_SKELETON), endless-invalid (MAX_ITERATIONS), reasoning tolerance. */
class ArchitectTest {
    private val model = LLModel(provider = LLMProvider.Ollama, id = "test")
    private val canvas = CanvasSpec(200.0, 200.0)
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false; classDiscriminator = "type" }

    private fun skeletonJson(s: EmitSkeleton): String = json.encodeToString(EmitSkeleton.serializer(), s)

    private val goodSkeleton = EmitSkeleton(
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

    @Test fun `valid emit yields the skeleton`() {
        val scripted = ScriptedPromptExecutor().apply { enqueueToolCall("emit_skeleton", skeletonJson(goodSkeleton)); enqueueDone() }
        val outcome = ArchitectRunner(scripted, model).run("bicycle", canvas)
        assertEquals(ArchitectStatus.SKELETON, outcome.status)
        assertEquals(3, outcome.skeleton?.components?.size)
    }

    @Test fun `a rejected emit is followed by a corrected one`() {
        val bad = goodSkeleton.copy(components = goodSkeleton.components.map { it.copy(width = 0.0) })
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall("emit_skeleton", skeletonJson(bad))
            enqueueToolCall("emit_skeleton", skeletonJson(goodSkeleton))
            enqueueDone()
        }
        val outcome = ArchitectRunner(scripted, model).run("bicycle", canvas)
        assertEquals(ArchitectStatus.SKELETON, outcome.status)
        assertEquals(3, outcome.skeleton?.components?.size)
    }

    @Test fun `no emit call yields NO_SKELETON`() {
        val scripted = ScriptedPromptExecutor().apply { enqueueDone() }
        val outcome = ArchitectRunner(scripted, model).run("nothing", canvas)
        assertEquals(ArchitectStatus.NO_SKELETON, outcome.status)
        assertNull(outcome.skeleton)
    }

    @Test fun `endless invalid emits terminate MAX_ITERATIONS`() {
        val bad = goodSkeleton.copy(components = goodSkeleton.components.map { it.copy(width = 0.0) })
        val scripted = ScriptedPromptExecutor().apply { repeat(8) { enqueueToolCall("emit_skeleton", skeletonJson(bad)) } }
        val outcome = ArchitectRunner(scripted, model, maxIterations = 3).run("loop", canvas)
        assertEquals(ArchitectStatus.MAX_ITERATIONS, outcome.status)
        assertNull(outcome.skeleton)
    }

    @Test fun `a perpetually-reasoning model terminates MAX_ITERATIONS`() {
        val scripted = ScriptedPromptExecutor().apply {
            repeat(8) { enqueueAssistant(Message.Assistant(MessagePart.Reasoning("still thinking..."), metaInfo = ResponseMetaInfo.Empty)) }
        }
        val outcome = ArchitectRunner(scripted, model).run("stuck", canvas)
        assertEquals(ArchitectStatus.MAX_ITERATIONS, outcome.status)
    }

    /** Anti-collapse acceptance (Slice 6): a skeleton that pairs the two `connect`s with a
     *  `distance_ratio` wheelbase solves to two wheels at DIFFERENT cx, ~ratio*canvas.width apart. */
    @Test fun `a distance_ratio skeleton solves with the two wheels separated, not collapsed`() {
        val wide = CanvasSpec(500.0, 500.0)
        val separated = EmitSkeleton(
            components = listOf(
                SkeletonComponent("rear_wheel", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
                SkeletonComponent("front_wheel", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
                SkeletonComponent("frame", "chassis", "line", 300.0, 100.0,
                    mapOf("rearAxle" to "BOTTOM_LEFT", "frontAxle" to "BOTTOM_RIGHT")),
            ),
            constraints = listOf(
                SkeletonConstraint("link_rear", "connect", "frame:rearAxle", "rear_wheel:hub"),
                SkeletonConstraint("link_front", "connect", "frame:frontAxle", "front_wheel:hub"),
                SkeletonConstraint("wheelbase", "distance_ratio",
                    "rear_wheel:CENTER_X", "front_wheel:CENTER_X", ratio = 0.6, relativeTo = "canvas_width"),
            ),
        )
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall("emit_skeleton", skeletonJson(separated)); enqueueDone()
        }
        val outcome = ArchitectRunner(scripted, model).run("bicycle", wide)
        assertEquals(ArchitectStatus.SKELETON, outcome.status)

        val graph = SkeletonBuilder.build(outcome.skeleton!!, wide)
        val (solved, result) = DraftsEngine().solveInto(graph, pinSizes = true)
        assertTrue(result is SolveResult.Success, "separation skeleton must solve: $result")

        val rearCx = solved.nodes["rear_wheel"]!!.box.cx
        val frontCx = solved.nodes["front_wheel"]!!.box.cx
        val wheelbase = frontCx - rearCx
        assertTrue(rearCx != frontCx, "wheels collapsed to the same cx (rear=$rearCx, front=$frontCx)")
        assertTrue(
            abs(wheelbase - 0.6 * wide.width) <= 1.0,
            "wheelbase $wheelbase should be ~0.6*canvas.width = ${0.6 * wide.width}"
        )
    }

    /** The feasibility gate (spec §4.4): a lone hub-to-hub connect SOLVES but collapses both wheels
     *  onto one point — the tool must return a `degenerate` diagnostic and refuse to stash, so the
     *  Architect revises. (The raw solver-level collapse is still proven in SafeHarborTest.) */
    @Test fun `a collapsed emit is rejected by the feasibility gate`() {
        val wide = CanvasSpec(500.0, 500.0)
        val collapsed = EmitSkeleton(
            components = listOf(
                SkeletonComponent("rear_wheel", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
                SkeletonComponent("front_wheel", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
            ),
            constraints = listOf(
                SkeletonConstraint("collapse", "connect", "rear_wheel:hub", "front_wheel:hub"),
            ),
        )
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall("emit_skeleton", skeletonJson(collapsed))
            enqueueDone()
        }
        val outcome = ArchitectRunner(scripted, model).run("two wheels", wide)
        assertEquals(ArchitectStatus.NO_SKELETON, outcome.status,
            "the gate must refuse a collapsed skeleton (nothing stashed)")
        assertNull(outcome.skeleton)
    }

    /** TDD for the prompt rewrite: the LLM-facing system prompt must show the gcd-reduced aspect
     *  ratio (NOT canvas pixels), teach `distance_ratio`, and carry the connect-collapse warning. */
    @Test fun `system prompt hides canvas pixels and teaches distance_ratio separation`() {
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall("emit_skeleton", skeletonJson(goodSkeleton)); enqueueDone()
        }
        ArchitectRunner(scripted, model).run("bicycle", CanvasSpec(800.0, 600.0))

        val promptText = scripted.prompts.first().messages.joinToString(" ") { msg ->
            msg.parts.filterIsInstance<MessagePart.Text>().joinToString(" ") { it.text }
        }
        // Aspect ratio (reduced to lowest terms), not canvas pixel dimensions.
        assertTrue(promptText.contains("aspect ratio 4:3"), "should show gcd-reduced aspect ratio:\n$promptText")
        assertFalse(promptText.contains("800"), "must not leak canvas pixel width:\n$promptText")
        assertFalse(promptText.contains("600"), "must not leak canvas pixel height:\n$promptText")
        assertFalse(promptText.contains("width.toInt()"), "must not carry the broken template literal:\n$promptText")
        // distance_ratio taught + the wheelbase example + the load-bearing collapse warning.
        assertTrue(promptText.contains("distance_ratio"), "should teach distance_ratio:\n$promptText")
        assertTrue(promptText.contains("wheelbase"), "should show the wheelbase example:\n$promptText")
        assertTrue(promptText.contains("collapse"), "must warn about connect-collapse:\n$promptText")
    }

    /** A coprime canvas (e.g. 797×599) must snap to the nearest canonical aspect ratio, not leak its
     *  raw dimensions into the prompt — closes the gcd-reduction pixel leak. */
    @Test fun `aspect ratio snaps a coprime canvas to the nearest canonical ratio`() {
        assertEquals("4:3", aspectRatio(CanvasSpec(800.0, 600.0)))
        assertEquals("1:1", aspectRatio(CanvasSpec(500.0, 500.0)))
        assertEquals("16:9", aspectRatio(CanvasSpec(1920.0, 1080.0)))
        assertEquals("3:4", aspectRatio(CanvasSpec(600.0, 800.0)))
        // A coprime canvas must not leak its raw (gcd-irreducible) dimensions.
        val coprime = aspectRatio(CanvasSpec(797.0, 599.0))
        assertEquals("4:3", coprime)
        assertFalse(coprime.contains("797") || coprime.contains("599"))
    }

    /** The revise loop (spec §4.4) must be TAUGHT: what unsatisfiable/degenerate mean, how to fix
     *  each, and the 2-revision budget. */
    @Test fun `system prompt teaches the revise loop`() {
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall("emit_skeleton", skeletonJson(goodSkeleton)); enqueueDone()
        }
        ArchitectRunner(scripted, model).run("bicycle", canvas)

        val promptText = scripted.prompts.first().messages.joinToString(" ") { msg ->
            msg.parts.filterIsInstance<MessagePart.Text>().joinToString(" ") { it.text }
        }
        assertTrue(promptText.contains("unsatisfiable"), "must explain unsatisfiable feedback:\n$promptText")
        assertTrue(promptText.contains("degenerate"), "must explain degenerate feedback:\n$promptText")
        assertTrue(promptText.contains("Safe-Harbor"), "must state the fallback after retries:\n$promptText")
        assertTrue(promptText.contains("`distance_ratio` between the named components"),
            "must teach the degenerate fix:\n$promptText")
    }

    /** Live-run defect (2026-08-14): the model re-emitted shuffled-but-still-unsatisfiable skeletons
     *  because nothing taught it WHY connect chains fail — a line's declared size is RIGID, so every
     *  connect chain implies exact distances and closing a loop with mismatched lengths contradicts.
     *  The prompt must teach the rigid-size rule and the safe shape (connect-tree + one gap per pair). */
    @Test fun `system prompt teaches rigid sizes and connect-tree topology`() {
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall("emit_skeleton", skeletonJson(goodSkeleton)); enqueueDone()
        }
        ArchitectRunner(scripted, model).run("bicycle", canvas)

        val promptText = scripted.prompts.first().messages.joinToString(" ") { msg ->
            msg.parts.filterIsInstance<MessagePart.Text>().joinToString(" ") { it.text }
        }
        assertTrue(promptText.contains("RIGID"), "must teach that component sizes are rigid:\n$promptText")
        assertTrue(promptText.contains("loop"), "must warn against closing connect loops:\n$promptText")
        assertTrue(promptText.contains("TREE"), "must teach the connect-tree shape:\n$promptText")
    }

    /** Struts (spec 2026-08-14): the prompt must teach the STRETCHABLE STRUT pattern positively —
     *  declare a rough size, set the exact span with ONE distance_ratio between the end components. */
    @Test fun `system prompt teaches stretchable struts`() {
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall("emit_skeleton", skeletonJson(goodSkeleton)); enqueueDone()
        }
        ArchitectRunner(scripted, model).run("bicycle", canvas)

        val promptText = scripted.prompts.first().messages.joinToString(" ") { msg ->
            msg.parts.filterIsInstance<MessagePart.Text>().joinToString(" ") { it.text }
        }
        assertTrue(promptText.contains("STRETCHABLE STRUT"), "must teach the strut pattern:\n$promptText")
        assertTrue(promptText.contains("set the exact span"), "must teach rough-size + exact-span split:\n$promptText")
    }

    /** contain (spec 2026-08-17): the prompt must teach the kind, the margin field, what it
     *  prevents, the structural-parts guidance, and the example line. */
    @Test fun `system prompt teaches contain`() {
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall("emit_skeleton", skeletonJson(goodSkeleton)); enqueueDone()
        }
        ArchitectRunner(scripted, model).run("bicycle", canvas)

        val promptText = scripted.prompts.first().messages.joinToString(" ") { msg ->
            msg.parts.filterIsInstance<MessagePart.Text>().joinToString(" ") { it.text }
        }
        assertTrue(promptText.contains("`contain`"), "must teach the contain kind:\n$promptText")
        assertTrue(promptText.contains("margin"), "must teach the margin field:\n$promptText")
        assertTrue(promptText.contains("off-canvas"), "must say what contain prevents:\n$promptText")
        assertTrue(promptText.contains("structural"), "must teach containing structural parts:\n$promptText")
        assertTrue(promptText.contains("keeps the bicycle on-canvas"), "example must gain a contain line:\n$promptText")
        assertTrue(promptText.contains("[0.0, 0.5]"), "must show inclusive margin range:\n$promptText")
        assertTrue(promptText.contains("0.02-0.05"), "must teach typical small margins:\n$promptText")
    }

    /** Live-run observation (2026-08-17 and 2026-08-18): EVERY run's first emit failed the
     *  alias-collision rule — the model named aliases "center"/"bottom" (lowercase collides too;
     *  the check is case-insensitive) and burned a full Architect retry (~45-70s of model time).
     *  The prompt must pre-empt the collision instead of letting the validator teach it. */
    @Test fun `system prompt warns against reserved anchor alias names`() {
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall("emit_skeleton", skeletonJson(goodSkeleton)); enqueueDone()
        }
        ArchitectRunner(scripted, model).run("bicycle", canvas)

        val promptText = scripted.prompts.first().messages.joinToString(" ") { msg ->
            msg.parts.filterIsInstance<MessagePart.Text>().joinToString(" ") { it.text }
        }
        assertTrue(promptText.contains("must NOT reuse"), "must forbid reserved alias names:\n$promptText")
        assertTrue(promptText.contains("case-insensitive"), "must say the collision is case-insensitive:\n$promptText")
        assertTrue(promptText.contains("hubPt"), "must show a safe alias example:\n$promptText")
    }

    /** Live-run observation (2026-08-18, run 3): emit attempts 3-4 burned on ad-hoc anchor
     *  spellings ("cx", "centerX") — neither a declared alias nor an exact geometric name. The
     *  prompt must teach the ref vocabulary instead of letting the validator reject it. */
    @Test fun `system prompt requires declared aliases or exact geometric names in refs`() {
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall("emit_skeleton", skeletonJson(goodSkeleton)); enqueueDone()
        }
        ArchitectRunner(scripted, model).run("bicycle", canvas)

        val promptText = scripted.prompts.first().messages.joinToString(" ") { msg ->
            msg.parts.filterIsInstance<MessagePart.Text>().joinToString(" ") { it.text }
        }
        assertTrue(promptText.contains("DECLARED alias"), "must require declared aliases or exact names:\n$promptText")
        assertTrue(promptText.contains("centerX"), "must name the ad-hoc spellings to avoid:\n$promptText")
        assertTrue(promptText.contains("unknown anchor"), "must say ad-hoc refs are rejected:\n$promptText")
    }

    /** Live-run observation (2026-08-18): skeletons grew 5 -> 11 -> 12 components across runs and
     *  the 12-component one exhausted the emit cap (unsatisfiable, then degenerate, then flailing)
     *  — richer skeletons have more failure modes and every failed emit costs a ~45s reasoning
     *  turn. Detail belongs to the per-component phase, not the skeleton. */
    @Test fun `system prompt caps skeleton decomposition size`() {
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall("emit_skeleton", skeletonJson(goodSkeleton)); enqueueDone()
        }
        ArchitectRunner(scripted, model).run("bicycle", canvas)

        val promptText = scripted.prompts.first().messages.joinToString(" ") { msg ->
            msg.parts.filterIsInstance<MessagePart.Text>().joinToString(" ") { it.text }
        }
        assertTrue(promptText.contains("5-8 components"), "must cap the skeleton size:\n$promptText")
        assertTrue(promptText.contains("macro-layout"), "must say what the skeleton is FOR:\n$promptText")
    }

    /** Schema-level alias teaching (2026-08-19): every live run burned 1-2 emits on reserved-word
     *  alias names despite the prose prohibition. The rule now lives in the tool SCHEMA itself via
     *  @LLMDescription on SkeletonComponent.anchors — models follow field descriptions far better
     *  than prose. This test pins the annotation -> Koog descriptor plumbing so a Koog upgrade
     *  that drops it fails loudly. */
    @Test fun `emit_skeleton schema teaches the alias naming rule on the anchors field`() {
        val tool = emitSkeletonTool(canvas, SkeletonSink())
        val components = (tool.descriptor.requiredParameters + tool.descriptor.optionalParameters)
            .first { it.name == "components" }
        val itemsType = components.type as? ToolParameterType.List
        val objectType = itemsType?.itemsType as? ToolParameterType.Object
        val anchors = objectType?.properties?.firstOrNull { it.name == "anchors" }
        assertNotNull(anchors, "anchors must exist in the components item schema")
        val d = anchors.description
        assertTrue(d.contains("reserved geometric anchor words"), "schema must forbid reserved alias names: $d")
        assertTrue(d.contains("case-insensitive"), "schema must say the check is case-insensitive: $d")
        assertTrue(d.contains("hubPt"), "schema must show a safe example: $d")
    }

    /** Live-run follow-up (2026-08-19, 2m02s run): the reserved-word collision is fixed, but emit 1
     *  failed on a REFERENCED-but-undeclared alias ('bottomPt' on 'handlebars'). The same
     *  schema-level teaching applies to the constraint ref fields. */
    @Test fun `emit_skeleton schema teaches declared-alias refs on the constraint source and target fields`() {
        val tool = emitSkeletonTool(canvas, SkeletonSink())
        val constraints = (tool.descriptor.requiredParameters + tool.descriptor.optionalParameters)
            .first { it.name == "constraints" }
        val objectType = (constraints.type as? ToolParameterType.List)?.itemsType as? ToolParameterType.Object
        for (fieldName in listOf("source", "target")) {
            val field = objectType?.properties?.firstOrNull { it.name == fieldName }
            assertNotNull(field, "$fieldName must exist in the constraints item schema")
            val d = field.description
            assertTrue(d.contains("DECLARED alias"), "$fieldName schema must require declared aliases: $d")
            assertTrue(d.contains("exact geometric anchor name"), "$fieldName schema must allow exact names: $d")
        }
    }

    /** relative_size + distribute (spec 2026-08-20): the prompt must teach both kinds with their
     *  shapes and canonical examples. */
    @Test fun `system prompt teaches relative_size and distribute`() {
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall("emit_skeleton", skeletonJson(goodSkeleton)); enqueueDone()
        }
        ArchitectRunner(scripted, model).run("bicycle", canvas)

        val promptText = scripted.prompts.first().messages.joinToString(" ") { msg ->
            msg.parts.filterIsInstance<MessagePart.Text>().joinToString(" ") { it.text }
        }
        assertTrue(promptText.contains("Six kinds"), "kind count must be updated:\n$promptText")
        assertTrue(promptText.contains("`relative_size`"), "must teach relative_size:\n$promptText")
        assertTrue(promptText.contains("penny-farthing"), "must show the penny-farthing example:\n$promptText")
        assertTrue(promptText.contains("yield to the ratio"), "must say declared sizes yield:\n$promptText")
        assertTrue(promptText.contains("`distribute`"), "must teach distribute:\n$promptText")
        assertTrue(promptText.contains("picket"), "must show the picket-fence example:\n$promptText")
    }

    /** Minimum-shape guard (live-run finding 2026-08-20): a thin-constraint skeleton (pure
     *  connect-tree + wheel-contains; NO distance_ratio, no satellite contains) solved with the
     *  seat off-canvas at y=-7 and the drawing crammed top-left — the engine faithfully solves
     *  whatever richness it gets. The prompt must demand the minimum shape. */
    @Test fun `system prompt demands a minimum shape for every skeleton`() {
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall("emit_skeleton", skeletonJson(goodSkeleton)); enqueueDone()
        }
        ArchitectRunner(scripted, model).run("bicycle", canvas)

        val promptText = scripted.prompts.first().messages.joinToString(" ") { msg ->
            msg.parts.filterIsInstance<MessagePart.Text>().joinToString(" ") { it.text }
        }
        assertTrue(promptText.contains("MINIMUM SHAPE"), "must state the minimum shape:\n$promptText")
        assertTrue(promptText.contains("collapses toward the canvas corner"), "must explain why a span is needed:\n$promptText")
        assertTrue(promptText.contains("EVERY floating part"), "must demand satellite contains:\n$promptText")
    }

    /** Sizes-are-pixels schema teaching (live-run finding 2026-08-21: a model declared wheels
     *  0.2x0.2 — sizes misread as canvas fractions; every other field in the contract is
     *  ratio-based). The width/height field descriptions must state the unit. */
    @Test fun `emit_skeleton schema teaches that sizes are in pixels`() {
        val tool = emitSkeletonTool(canvas, SkeletonSink())
        val components = (tool.descriptor.requiredParameters + tool.descriptor.optionalParameters)
            .first { it.name == "components" }
        val objectType = (components.type as? ToolParameterType.List)?.itemsType as? ToolParameterType.Object
        for (fieldName in listOf("width", "height")) {
            val field = objectType?.properties?.firstOrNull { it.name == fieldName }
            assertNotNull(field, "$fieldName must exist in the components item schema")
            val d = field.description
            assertTrue(d.contains("pixels"), "$fieldName schema must state the unit: $d")
            assertTrue(d.contains("NOT a fraction"), "$fieldName schema must warn against fractions: $d")
        }
    }
}
