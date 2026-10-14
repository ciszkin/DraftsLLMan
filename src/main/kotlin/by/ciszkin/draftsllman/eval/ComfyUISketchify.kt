package by.ciszkin.draftsllman.eval

import by.ciszkin.draftsllman.render.SkiaRenderer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.skiko.Library
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Properties
import kotlin.random.Random

/**
 * A ComfyUI-backed sketchify backend: renders a [Wireframe] as an OUTLINED wireframe PNG, uploads it
 * to a LAN ComfyUI, runs the *validated* qwen-image-edit workflow (`UnetLoaderGGUF` edit model +
 * Edit-Lightning-4steps LoRA + `TextEncodeQwenImageEdit` conditioning, VAE-encoded input, denoise
 * 1.0 — the node graph that produced the user-approved hand-drawn result), and returns the generated
 * sketch PNG. Deterministic per [seed] (KSampler seed is fixed), so the same wireframe+seed always
 * yields the same sketch.
 *
 * Config (local.properties, env-friendly): `comfyui.endpoint` (default http://192.168.100.12:8188),
 * `comfyui.unet`, `comfyui.lora`, `comfyui.vae`, `comfyui.clip`.
 */
class ComfyUISketchify(
    private val endpoint: String = comfyEndpoint(),
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
) {

    fun sketch(wf: Wireframe, seed: Long = 0L, steps: Int = 4): ByteArray {
        Library.load()
        val inputPng = SkiaRenderer.render(wf.graph)
        val inputName = "eval_input_${seed}.png"
        upload(inputPng, inputName)
        val workflow = buildEditWorkflow(
            inputImage = inputName,
            prompt = SKETCH_PROMPT,
            seed = seed,
            steps = steps,
            unet = comfyUnet(),
            lora = comfyLora(),
            vae = comfyVae(),
            clip = comfyClip(),
        )
        val promptId = queue(workflow)
        return awaitOutput(promptId)
    }

    // -- ComfyUI HTTP ---------------------------

    private fun upload(png: ByteArray, name: String) {
        val boundary = "----draftsllman-eval-${Random.nextInt(100000, 999999)}"
        val body = java.io.ByteArrayOutputStream().apply {
            writeUtf8("--$boundary\r\n")
            writeUtf8("Content-Disposition: form-data; name=\"type\"\r\n\r\ninput\r\n")
            writeUtf8("--$boundary\r\n")
            writeUtf8("Content-Disposition: form-data; name=\"overwrite\"\r\n\r\ntrue\r\n")
            writeUtf8("--$boundary\r\n")
            writeUtf8("Content-Disposition: form-data; name=\"image\"; filename=\"$name\"\r\n")
            writeUtf8("Content-Type: image/png\r\n\r\n")
            writeAll(png)
            writeUtf8("\r\n--$boundary--\r\n")
        }.toByteArray()
        val req = HttpRequest.newBuilder(URI("$endpoint/upload/image"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val resp = send(req)
        if (resp.statusCode() !in 200..299) {
            throw IllegalStateException("ComfyUI upload failed (${resp.statusCode()}): ${resp.body()}")
        }
    }

    private fun queue(workflow: JsonObject): String {
        val body = buildJsonObject { put("prompt", workflow) }.toString()
        val req = HttpRequest.newBuilder(URI("$endpoint/prompt"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = send(req)
        val json = parse(resp)
        return json["prompt_id"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("ComfyUI /prompt returned no prompt_id: $json")
    }

    private fun awaitOutput(promptId: String, timeoutSec: Long = 240): ByteArray {
        val deadline = System.nanoTime() + Duration.ofSeconds(timeoutSec).toNanos()
        while (System.nanoTime() < deadline) {
            val req = HttpRequest.newBuilder(URI("$endpoint/history/$promptId"))
                .timeout(Duration.ofSeconds(30)).GET().build()
            val resp = send(req)
            val history = parse(resp).jsonObject[promptId]?.jsonObject ?: run {
                Thread.sleep(2000); continue
            }
            val status = history["status"]?.jsonObject
            val completed = status?.get("completed")?.jsonPrimitive?.content == "true" ||
                status?.get("status_str")?.jsonPrimitive?.content == "success"
            val error = status?.get("status_str")?.jsonPrimitive?.content == "error"
            if (completed) {
                val images = history["outputs"]?.jsonObject?.values
                    ?.flatMap { out -> (out.jsonObject["images"] as? JsonArray)?.toList().orEmpty() }
                    .orEmpty()
                val first = images.firstOrNull()
                    ?: throw IllegalStateException("ComfyUI completed with no output images")
                val img = first.jsonObject
                val filename = img["filename"]!!.jsonPrimitive.content
                val subfolder = img["subfolder"]?.jsonPrimitive?.content.orEmpty()
                val type = img["type"]?.jsonPrimitive?.content ?: "output"
                return fetchImage(filename, subfolder, type)
            }
            if (error) {
                val details = status?.get("messages")?.toString().orEmpty()
                throw IllegalStateException("ComfyUI execution error: $details")
            }
            Thread.sleep(2000)
        }
        throw IllegalStateException("ComfyUI timed out after ${timeoutSec}s waiting for prompt $promptId")
    }

    private fun fetchImage(filename: String, subfolder: String, type: String): ByteArray {
        val url = URI(
            "$endpoint/view?filename=$filename&subfolder=$subfolder&type=$type",
        )
        val req = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(60)).GET().build()
        val resp = send(req)
        if (resp.statusCode() != 200) throw IllegalStateException("view ${resp.statusCode()}")
        return resp.body()
    }

    private fun send(req: HttpRequest): HttpResponse<ByteArray> =
        http.send(req, HttpResponse.BodyHandlers.ofByteArray())

    private fun parse(resp: HttpResponse<ByteArray>): JsonObject =
        runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(String(resp.body(), Charsets.UTF_8))
                .jsonObject
        }.getOrElse { JsonObject(emptyMap()) }

    // -- pure workflow builder (unit-tested) ----------

    companion object {
        /** The tuned hand-drawn instruction (validated by the user on compare3). */
        const val SKETCH_PROMPT: String =
            "Redraw this UI wireframe as a hand-drawn sketch on white paper. Keep the exact same " +
                "layout and every element; do not add, remove, move, merge, resize, or re-color any " +
                "element; do not add text. Draw everything with a hand: lines slightly imperfect, " +
                "circles not perfectly round, loose corners with tiny overdraw, subtle line-pressure " +
                "variation; keep the light gray fills; faint paper texture. " +
                "The LONG horizontal divider lines: draw them as hand-drawn straight lines — long, " +
                "mostly straight, with a gentle human tremor (clearly NOT drawn with a ruler, but NOT " +
                "wavy either). " +
                "The small label dots with thin pointing lines (call-out annotations near the chart " +
                "and near the sidebar): keep them EXACTLY as drawn — the dot, the thin line and its " +
                "arrow tip; do not remove them, do not thicken or merge them into UI elements."


        /** Builds the EXECUTION-format workflow for `/prompt` (pure, unit-tested). */
        internal fun buildEditWorkflow(
            inputImage: String,
            prompt: String,
            seed: Long,
            steps: Int = 4,
            denoise: Double = 1.0,
            unet: String = "Qwen_Image_Edit-Q4_0.gguf",
            lora: String = "Qwen-Image-Edit-Lightning-4steps-V1.0-bf16.safetensors",
            vae: String = "qwen_image_vae.safetensors",
            clip: String = "qwen_2.5_vl_7b_fp8_scaled.safetensors",
        ): JsonObject = buildJsonObject {
            putNode("54", "LoadImage") { put("image", inputImage) }
            putNode("25", "ImageScaleToTotalPixels") {
                put("image", ref("54")); put("upscale_method", "nearest-exact")
                put("megapixels", 1.0); put("resolution_steps", 16)
            }
            putNode("37", "UnetLoaderGGUF") { put("unet_name", unet) }
            putNode("29", "LoraLoaderModelOnly") {
                put("model", ref("37")); put("lora_name", lora); put("strength_model", 1.0)
            }
            putNode("23", "VAELoader") { put("vae_name", vae) }
            putNode("49", "CLIPLoader") {
                put("clip_name", clip); put("type", "qwen_image"); put("device", "cpu")
            }
            putNode("26", "VAEEncode") { put("pixels", ref("25")); put("vae", ref("23")) }
            putNode("36", "TextEncodeQwenImageEdit") {
                put("clip", ref("49")); put("vae", ref("23")); put("image", ref("25")); put("prompt", prompt)
            }
            putNode("32", "KSampler") {
                put("model", ref("29")); put("positive", ref("36")); put("negative", ref("36"))
                put("latent_image", ref("26")); put("seed", seed); put("steps", steps)
                put("cfg", 1.0); put("sampler_name", "euler"); put("scheduler", "simple"); put("denoise", denoise)
            }
            putNode("27", "VAEDecode") { put("samples", ref("32")); put("vae", ref("23")) }
            putNode("55", "SaveImage") { put("images", ref("27")); put("filename_prefix", "eval_sketch") }
        }

        private fun JsonObjectBuilder.putNode(id: String, classType: String, inputs: JsonObjectBuilder.() -> Unit) {
            put(id, buildJsonObject {
                put("class_type", classType)
                put("inputs", buildJsonObject(inputs))
            })
        }

        private fun ref(nodeId: String): JsonArray = buildJsonArray {
            add(nodeId)
            add(0)
        }
    }
}

/** `comfyui.endpoint` from local.properties or the DRAFTS_COMFY_ENDPOINT env, else the LAN default. */
fun comfyEndpoint(): String =
    System.getenv("DRAFTS_COMFY_ENDPOINT")?.takeIf { it.isNotBlank() }
        ?: comfyProperty("comfyui.endpoint")
        ?: "http://192.168.100.12:8188"

private fun comfyUnet(): String = comfyProperty("comfyui.unet") ?: "Qwen_Image_Edit-Q4_0.gguf"
private fun comfyLora(): String = comfyProperty("comfyui.lora") ?: "Qwen-Image-Edit-Lightning-4steps-V1.0-bf16.safetensors"
private fun comfyVae(): String = comfyProperty("comfyui.vae") ?: "qwen_image_vae.safetensors"
private fun comfyClip(): String = comfyProperty("comfyui.clip") ?: "qwen_2.5_vl_7b_fp8_scaled.safetensors"

private fun comfyProperty(key: String): String? = runCatching {
    File("local.properties").takeIf { it.isFile }?.inputStream()?.use {
        Properties().apply { load(it) }.getProperty(key)
    }
}.getOrNull()

/** Multipart write helpers (the JDK stream lacks write(byte[])/write(String)). */
private fun java.io.ByteArrayOutputStream.writeUtf8(s: String) { write(s.toByteArray(), 0, s.length) }
private fun java.io.ByteArrayOutputStream.writeAll(b: ByteArray) { write(b, 0, b.size) }