package by.ciszkin.draftsllman.agent

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import by.ciszkin.draftsllman.engine.ToolExecutor
import by.ciszkin.draftsllman.engine.ToolResult
import kotlinx.coroutines.runBlocking
import org.jetbrains.skiko.Library
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in live smoke test proving the real [OllamaClient] wiring talks to a remote Ollama.
 *
 * **Off by design:** gated by the `DRAFTS_LIVE_SMOKE=1` environment variable, so a normal
 * `./gradlew test` run skips it (the remote endpoint is on a LAN this sandbox/CI cannot reach).
 * Set the var only when you can actually reach the Ollama host.
 *
 * This proves wiring, not quality: it issues one trivial chat request and asserts the assistant
 * replies with non-empty text. No agent loop, no tools — it uses [DEFAULT_LLM] (the configured model).
 *
 * Override knobs (see Models.kt):
 *  - `DRAFTS_OLLAMA_ENDPOINT` / `ollama.endpoint` — Ollama base URL.
 *  - `DRAFTS_OLLAMA_MODEL` / `ollama.model` — model id.
 */
@EnabledIfEnvironmentVariable(named = "DRAFTS_LIVE_SMOKE", matches = "1")
class LiveOllamaSmokeTest {

    @Test
    fun `live ollama chat returns non-empty assistant text`() {
        val endpoint = defaultOllamaEndpoint()
        val model = DEFAULT_LLM

        val executor = OllamaClient(baseUrl = endpoint)
        val prompt = Prompt.build(id = "live-smoke") {
            user("Reply with the single word OK.")
        }

        val response: Message.Assistant = runBlocking { executor.execute(prompt, model, emptyList()) }

        assertTrue(
            response.textContent().isNotEmpty(),
            "expected a non-empty assistant reply but got: $response"
        )
        executor.close()
    }

    @Test
    fun `text-to-svg against the live model produces an svg`() {
        val cli = TextToSvgCli("a circle centered above a rectangle")
        val outcome = cli.run()
        val svg = cli.svg()
        assertTrue(svg.isNotBlank(), "expected a non-empty SVG; outcome=$outcome")
        assertTrue(svg.contains("<svg"), "expected an <svg> document: $svg")
    }

    @Test
    fun `live vision model accepts a critique image and replies`() {
        Library.load()
        // Render a real critique image via the engine so the PNG is valid and decodable by the model.
        val engine = ToolExecutor.default()
        engine.execute("""{"type":"set_view_box","width":64,"height":64}""")
        engine.execute("""{"type":"add_node","id":"a","role":"w","kind":"circle","width":20.0,"height":20.0}""")
        val critique = (engine.execute(
            """{"type":"request_visual_critique","nodeId":"a","globalSize":64,"roiSize":64}"""
        ) as ToolResult.Critique).result

        val endpoint = defaultOllamaEndpoint()
        val modelId = System.getenv("DRAFTS_OLLAMA_MODEL") ?: "gemma4:12b"
        val model = LLModel(provider = LLMProvider.Ollama, id = modelId, capabilities = DEFAULT_LLM_CAPABILITIES)
        val ollama = OllamaClient(baseUrl = endpoint)
        val prompt = Prompt.build(id = "vision-smoke") {
            user {
                text("Here is a rendered SVG frame. Reply with the single word OK.")
                image(AttachmentSource.Image(
                    content = AttachmentContent.Binary.Base64(critique.globalPngB64),
                    format = "png",
                ))
            }
        }

        val response = runBlocking { ollama.execute(prompt, model, emptyList()) }
        assertTrue(
            response.textContent().isNotEmpty(),
            "expected a non-empty reply to the image-bearing prompt (is the model vision-capable?): $response"
        )
        ollama.close()
    }
}
