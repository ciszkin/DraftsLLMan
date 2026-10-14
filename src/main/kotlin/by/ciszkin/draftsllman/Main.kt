package by.ciszkin.draftsllman

import by.ciszkin.draftsllman.agent.RetryPromptExecutor
import by.ciszkin.draftsllman.agent.Tracer
import by.ciszkin.draftsllman.agent.livePromptExecutor
import by.ciszkin.draftsllman.agent.selectedProvider
import by.ciszkin.draftsllman.coordination.Coordinator
import java.io.File
import java.io.PrintWriter

/**
 * CLI entry point for the DraftsLLMan multi-agent Text-to-SVG pipeline.
 *
 * Usage: `./gradlew run --args='"your drawing prompt"'`
 *
 * Requires `local.properties` (`ollama.endpoint` + `ollama.model`) or the `DRAFTS_OLLAMA_ENDPOINT` /
 * `DRAFTS_OLLAMA_MODEL` env vars. The pipeline: the Architect decomposes the prompt into
 * ComponentBriefs, Draftsmen draw each in isolation, the merge composes them into one global SVG.
 * Writes the SVG to `output/<slug>-<epoch>.svg` (also prints to stdout) + a summary to stderr.
 */
fun main(args: Array<String>) {
    val prompt = args.firstOrNull() ?: run {
        System.err.println("Usage: ./gradlew run --args='\"your drawing prompt\"'")
        System.err.println("Example: ./gradlew run --args='\"a circle centered above a rectangle\"'")
        return
    }
    try {
        val provider = selectedProvider()
        System.err.println("provider: $provider")
        File("output").mkdirs()
        val tracer = Tracer(file = PrintWriter("output/trace.log"))
        // Retry-wrap the executor: transient OpenRouter 429s (rate-limited upstream) are backed off
        // and retried instead of killing a Draftsman run (2026-08-14 live run lost 2 of 6 this way).
        val executor = RetryPromptExecutor(livePromptExecutor())
        val outcome = Coordinator(executor, tracer = tracer).run(prompt)
        val svgFile = File("output/${promptSlug(prompt)}-${System.currentTimeMillis()}.svg")
        svgFile.writeText(outcome.svg)
        System.err.println("SVG written to ${svgFile.path}")
        println(outcome.svg)
        System.err.println("components: ${outcome.componentCount}, architect produced skeleton: ${outcome.architectProducedSkeleton}")
    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        System.err.println(when (selectedProvider()) {
            "openrouter" -> "OpenRouter request failed — check openrouter.apikey / openrouter.model in local.properties."
            else -> "Is your Ollama reachable? Check local.properties (ollama.endpoint / ollama.model)."
        })
        throw e
    }
}

/** Filesystem-safe lowercase slug of the prompt (words joined by '-', alphanumerics only, cap 40). */
internal fun promptSlug(prompt: String): String =
    prompt.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        .joinToString("-").take(40).ifEmpty { "drawing" }

