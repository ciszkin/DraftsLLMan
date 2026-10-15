package by.ciszkin.draftsllman.agent

import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.dsl.builder.node
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart

/**
 * koog's `onTextMessage` finish-edge matches ANY message with a Text part, so a response carrying
 * BOTH text and a tool call (common on OpenAI-compatible chat-completions gateways) can finish the
 * loop WITHOUT executing the tool (live finding 2026-09-03, OpenCode Go kimi-k3: the emit call was
 * dropped). Stripping the text from tool-carrying responses leaves only the tool edge active — the
 * tool executes; a pure-text response still finishes the loop.
 *
 * Applied inside [requestLlmToleratingReasoning] — the single choke point every agent-loop LLM call
 * goes through — so a call site cannot forget it (the 2026-09-03 fix applied it per call site and
 * two `nodeSendToolResult` sites were missed).
 */
internal fun Message.Assistant.routeToToolsWhenPresent(): Message.Assistant =
    if (parts.any { it is MessagePart.Tool.Call })
        copy(parts = parts.filterNot { it is MessagePart.Text })
    else this

/** Thrown when the model keeps returning reasoning-only responses past [maxNudges]. */
internal class ReasoningLoopReached(val maxNudges: Int) :
    RuntimeException("model returned only reasoning after $maxNudges nudges")

internal const val REASONING_NUDGE =
    "You returned only reasoning with no final answer. Provide your final answer or tool call now."

/** A response the agent loop can route on (a tool call or text) — i.e. NOT reasoning-only. */
private fun Message.Assistant.isRoutable(): Boolean =
    parts.any { it is MessagePart.Tool.Call || it is MessagePart.Text }

/**
 * Calls [requestLLM]; if the response is reasoning-only (no tool call, no text), appends a nudge
 * user-message and retries, up to [maxNudges]. Returns the first routable response with the
 * text+tool guard applied ([routeToToolsWhenPresent]) — a pending tool call always beats koog's
 * `onTextMessage` finish edge, at every call site, by construction; throws [ReasoningLoopReached]
 * if the model stays reasoning-only past the cap. Runs inside `writeSession`, so it adds NO graph
 * nodes (zero `maxIterations` cost). Shared by [draftsVisionStrategy] and [reasoningTolerantStrategy].
 */
internal suspend fun AIAgentLLMWriteSession.requestLlmToleratingReasoning(maxNudges: Int): Message.Assistant {
    repeat(maxNudges) {
        val response = requestLLM()
        if (response.isRoutable()) return response.routeToToolsWhenPresent()
        appendPrompt { user { text(REASONING_NUDGE) } }
    }
    val last = requestLLM()
    if (last.isRoutable()) return last.routeToToolsWhenPresent()
    throw ReasoningLoopReached(maxNudges)
}

/**
 * A reusable `nodeCallLLM` that appends the input prompt as a user message then calls
 * [requestLlmToleratingReasoning] — mirroring Koog's `nodeLLMRequest` but tolerating reasoning-only
 * responses. Shared by [draftsVisionStrategy] and [reasoningTolerantStrategy].
 */
internal fun nodeCallLlmToleratingReasoning(maxNudges: Int) =
    node<String, Message.Assistant>("callLLM") { message ->
        llm.writeSession {
            appendPrompt { user(message) }
            requestLlmToleratingReasoning(maxNudges)
        }
    }
