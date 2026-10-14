package by.ciszkin.draftsllman.eval

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract for the ComfyUI qwen-image-edit EXECUTION workflow built by [ComfyUISketchify]: the exact
 * node graph validated live against a working ComfyUI (`UnetLoaderGGUF` edit model + Edit-Lightning
 * LoRA + `TextEncodeQwenImageEdit` conditioning, VAE-encoded input at denoise 1.0). Pure structural
 * assertions — no network.
 */
class ComfyFlowBuilderTest {

    private fun workflow(): JsonObject =
        ComfyUISketchify.buildEditWorkflow(
            inputImage = "eval_input.png",
            prompt = "make it hand-drawn",
            seed = 2026L,
            steps = 4,
        )

    private fun node(wf: JsonObject, id: String): JsonObject = wf[id]!!.jsonObject

    private fun inputs(wf: JsonObject, id: String): JsonObject = node(wf, id)["inputs"]!!.jsonObject

    /** `["37", 0]`-style node reference -> list of JSON primitive contents ("37", "0"). */
    private fun ref(value: Any?): List<String> = (value as JsonArray).map { it.jsonPrimitive.contentOrNull ?: "" }

    @Test
    fun `builds exactly the validated eleven-node edit graph`() {
        assertEquals(
            setOf("54", "25", "37", "29", "23", "49", "26", "36", "32", "27", "55"),
            workflow().keys,
            "the workflow must be exactly the validated node set",
        )
    }

    @Test
    fun `model chain runs GGUF edit unet through the Lightning LoRA into the sampler`() {
        val wf = workflow()
        assertEquals("UnetLoaderGGUF", node(wf, "37")["class_type"]!!.jsonPrimitive.content)
        assertEquals("Qwen_Image_Edit-Q4_0.gguf", inputs(wf, "37")["unet_name"]!!.jsonPrimitive.content)

        assertEquals("LoraLoaderModelOnly", node(wf, "29")["class_type"]!!.jsonPrimitive.content)
        assertEquals("Qwen-Image-Edit-Lightning-4steps-V1.0-bf16.safetensors",
            inputs(wf, "29")["lora_name"]!!.jsonPrimitive.content)
        assertEquals(listOf("37", "0"), ref(inputs(wf, "29")["model"]))

        assertEquals(listOf("29", "0"), ref(inputs(wf, "32")["model"]), "sampler model = LoRA output")
    }

    @Test
    fun `edit conditioning ties clip vae image and feeds both positive and negative`() {
        val wf = workflow()
        assertEquals("TextEncodeQwenImageEdit", node(wf, "36")["class_type"]!!.jsonPrimitive.content)
        val te = inputs(wf, "36")
        assertEquals(listOf("49", "0"), ref(te["clip"]))
        assertEquals(listOf("23", "0"), ref(te["vae"]))
        assertEquals(listOf("25", "0"), ref(te["image"]))
        assertEquals("make it hand-drawn", te["prompt"]!!.jsonPrimitive.content)

        val sampler = inputs(wf, "32")
        assertEquals(listOf("36", "0"), ref(sampler["positive"]), "positive from edit node")
        assertEquals(listOf("36", "0"), ref(sampler["negative"]), "negative from edit node")
    }

    @Test
    fun `sampler and image-loading parameters match the validated run`() {
        val wf = workflow()
        val sampler = inputs(wf, "32")
        assertEquals(2026, sampler["seed"]!!.jsonPrimitive.content.toInt())
        assertEquals(4, sampler["steps"]!!.jsonPrimitive.content.toInt())
        assertEquals(1.0, sampler["cfg"]!!.jsonPrimitive.content.toDouble())
        assertEquals("euler", sampler["sampler_name"]!!.jsonPrimitive.content)
        assertEquals("simple", sampler["scheduler"]!!.jsonPrimitive.content)
        assertEquals(1.0, sampler["denoise"]!!.jsonPrimitive.content.toDouble())

        assertEquals(listOf("25", "0"), ref(inputs(wf, "26")["pixels"]), "VAEEncode takes the scaled image")
        assertEquals("eval_input.png", inputs(wf, "54")["image"]!!.jsonPrimitive.content)
        assertEquals(1.0, inputs(wf, "25")["megapixels"]!!.jsonPrimitive.content.toDouble())
        assertEquals(listOf("54", "0"), ref(inputs(wf, "25")["image"]))
    }

    @Test
    fun `decode uses the loaded vae and the save prefix is stable`() {
        val wf = workflow()
        assertEquals(listOf("23", "0"), ref(inputs(wf, "27")["vae"]))
        assertEquals("eval_sketch", inputs(wf, "55")["filename_prefix"]!!.jsonPrimitive.content)
        assertTrue(workflow().size == 11)
    }
}