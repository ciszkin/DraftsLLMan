package by.ciszkin.draftsllman.sketch

import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import by.ciszkin.draftsllman.agent.ScriptedPromptExecutor
import by.ciszkin.draftsllman.architect.EmitSkeleton
import by.ciszkin.draftsllman.architect.SkeletonComponent
import by.ciszkin.draftsllman.architect.SkeletonConstraint
import by.ciszkin.draftsllman.scene.CanvasSpec
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S1 entry point: a raster sketch -> a solved SceneGraph. Driven through the deterministic scripted
 * executor (no network/img), asserting: valid emit, rejection-then-correction, no-emit, reasoning
 * tolerance, the input image actually reaching the first recorded prompt, and Unsatisfiable skeletons
 * degrading to the Safe-Harbor spread instead of failing.
 */
class SketchRunnerTest {
    private val model = LLModel(provider = LLMProvider.Ollama, id = "test")
    private val canvas = CanvasSpec(900.0, 640.0)
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false; classDiscriminator = "type" }

    private val fakePng: ByteArray = ByteArray(12) { (it + 1).toByte() }

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
            SkeletonConstraint("span", "distance_ratio", "rear:CENTER_X", "front:CENTER_X",
                ratio = 0.3, relativeTo = "canvas_width"),
            SkeletonConstraint("containR", "contain", "rear", "canvas", margin = 0.05),
            SkeletonConstraint("containF", "contain", "front", "canvas", margin = 0.05),
        ),
    )

    private fun run(sketch: ScriptedPromptExecutor): SketchOutcome =
        SketchRunner(sketch, model).run(fakePng, canvas)

    @Test
    fun `valid emit yields a solved skeleton graph`() {
        val sketch = ScriptedPromptExecutor().apply {
            enqueueToolCall("emit_skeleton", skeletonJson(goodSkeleton))
            enqueueDone()
        }
        val outcome = run(sketch)
        assertEquals(SketchStatus.SKELETON, outcome.status)
        assertNotNull(outcome.skeleton)
        assertEquals(3, outcome.graph.nodes.size)
        // every skeleton component must exist as a solved node
        assertEquals(setOf("rear", "front", "frame"), outcome.graph.nodes.keys)
    }

    @Test
    fun `rejected emit is followed by a corrected one`() {
        val bad = goodSkeleton.copy(components = goodSkeleton.components.map { it.copy(width = 0.0) })
        val sketch = ScriptedPromptExecutor().apply {
            enqueueToolCall("emit_skeleton", skeletonJson(bad))
            enqueueToolCall("emit_skeleton", skeletonJson(goodSkeleton))
            enqueueDone()
        }
        val outcome = run(sketch)
        assertEquals(SketchStatus.SKELETON, outcome.status)
        assertEquals(3, outcome.graph.nodes.size)
    }

    @Test
    fun `no emit yields NO_SKELETON with an empty scene`() {
        val sketch = ScriptedPromptExecutor().apply { enqueueDone() }
        val outcome = run(sketch)
        assertEquals(SketchStatus.NO_SKELETON, outcome.status)
        assertNull(outcome.skeleton)
        assertTrue(outcome.graph.nodes.isEmpty())
    }

    @Test
    fun `a reasoning-only model is nudged back into an emit`() {
        val sketch = ScriptedPromptExecutor().apply {
            enqueueAssistant(ai.koog.prompt.message.Message.Assistant(
                ai.koog.prompt.message.MessagePart.Reasoning("pondering the sketch..."),
                metaInfo = ai.koog.prompt.message.ResponseMetaInfo.Empty,
            ))
            enqueueToolCall("emit_skeleton", skeletonJson(goodSkeleton))
            enqueueDone()
        }
        val outcome = run(sketch)
        assertEquals(SketchStatus.SKELETON, outcome.status)
        assertNotNull(outcome.skeleton)
    }

    @Test
    fun `a message carrying text plus a tool call still executes the tool`() {
        // qwen/gemma sometimes return "text + tool_call" in ONE response. koog's onTextMessage
        // finish-edge matches that message too, so without the guard in nodeCallLLM the loop
        // prematurely ends and the skeleton is lost (live finding 2026-09-02).
        val textAndCall = ai.koog.prompt.message.Message.Assistant(
            parts = listOf(
                ai.koog.prompt.message.MessagePart.Text("Here is my corrected skeleton."),
                ai.koog.prompt.message.MessagePart.Tool.Call(
                    id = "call-x", tool = "emit_skeleton",
                    args = kotlinx.serialization.json.Json.parseToJsonElement(skeletonJson(goodSkeleton))
                        .let { it as kotlinx.serialization.json.JsonObject },
                ),
            ),
            metaInfo = ai.koog.prompt.message.ResponseMetaInfo.Empty,
        )
        val sketch = ScriptedPromptExecutor().apply {
            enqueueAssistant(textAndCall)
            enqueueDone()
        }
        val outcome = run(sketch)
        assertTrue(outcome.status == SketchStatus.SKELETON, "text+tool message must still emit: ${outcome.status}")
        assertEquals(setOf("rear", "front", "frame"), outcome.graph.nodes.keys)
    }

    @Test
    fun `the input sketch image reaches the first recorded prompt`() {
        val sketch = ScriptedPromptExecutor().apply {
            enqueueToolCall("emit_skeleton", skeletonJson(goodSkeleton))
            enqueueDone()
        }
        run(sketch)
        val images = sketch.prompts.first().messages
            .flatMap { it.parts }
            .filterIsInstance<MessagePart.Attachment>()
            .map { it.source }
            .filterIsInstance<AttachmentSource.Image>()
        assertEquals(1, images.size, "exactly the input sketch image must be attached to the first call")
        val base64 = (images.single().content as AttachmentContent.Binary.Base64)
        assertTrue(
            fakePng.contentEquals(base64.asBytes()),
            "the attached image must be byte-identical to the input sketch PNG",
        )
    }

    @Test
    fun `an unsatisfiable skeleton degrades to the Safe-Harbor spread, never fails`() {
        // Two hubs hard-connected to each other + no spanning distance_ratio -> over-constrained:
        // the solver cannot place them; the runner must degrade to the Safe-Harbor layout.
        val impossible = EmitSkeleton(
            components = listOf(
                SkeletonComponent("a", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
                SkeletonComponent("b", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
            ),
            constraints = listOf(
                SkeletonConstraint("loop", "connect", "a:hub", "b:hub"),
                SkeletonConstraint("loop2", "connect", "b:hub", "a:hub"),
            ),
        )
        val sketch = ScriptedPromptExecutor().apply {
            // The tool reports the diagnostic and only STASHES after MAX_EMIT_ATTEMPTS (3) failures,
            // then tells the model to reply DONE; the runner's Safe-Harbor lays the stashed skeleton out.
            repeat(3) { enqueueToolCall("emit_skeleton", skeletonJson(impossible)) }
            enqueueDone()
        }
        val outcome = run(sketch)
        assertEquals(SketchStatus.SKELETON, outcome.status)
        assertEquals(2, outcome.graph.nodes.size, "Safe-Harbor must keep both components in the drawing")
    }
}