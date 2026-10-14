package by.ciszkin.draftsllman.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A recorded sequence of model turns (assistant messages) — the replay fixture. */
@Serializable
data class Trace(val turns: List<Turn>) {
    @Serializable
    sealed interface Turn {
        @Serializable @SerialName("tool_call")
        data class ToolCall(val tool: String, val args: String) : Turn

        @Serializable @SerialName("text")
        data class Text(val content: String) : Turn
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = false; classDiscriminator = "type" }
        fun encodeToString(trace: Trace): String = json.encodeToString(serializer(), trace)
        fun decodeFromString(s: String): Trace = json.decodeFromString(serializer(), s)

        /** Load a trace from a test resource path, or null if absent (fixture not yet captured). */
        fun fromResource(resource: String): Trace? =
            Trace::class.java.getResourceAsStream(resource)?.bufferedReader()?.use { decodeFromString(it.readText()) }
    }
}

/** Replays a [Trace] into a [ScriptedPromptExecutor] by enqueuing each turn in order. */
fun ScriptedPromptExecutor.enqueueTrace(trace: Trace) {
    trace.turns.forEach { turn ->
        when (turn) {
            is Trace.Turn.ToolCall -> enqueueToolCall(turn.tool, turn.args)
            is Trace.Turn.Text -> enqueueDone(turn.content)
        }
    }
}
