package by.ciszkin.draftsllman.agent

import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart

/**
 * Installs tracing on the agent: every LLM turn + tool call is logged to [tracer] with the
 * [agentName] prefix. Brief lines (one-liner per event) go to stderr immediately; detailed lines
 * (full response / tool args) go to the tracer's file.
 */
fun GraphAIAgent.FeatureContext.installTracing(agentName: String, tracer: Tracer) {
    var turn = 0
    handleEvents {
        onLLMCallCompleted { ctx ->
            turn++
            tracer.brief("[$agentName] LLM turn $turn → ${summarize(ctx.response)}")
            tracer.detail("  response: ${ctx.response}")
        }
        onToolCallCompleted { ctx ->
            val result = ctx.toolResult?.toString()?.take(200) ?: "(null)"
            tracer.brief("[$agentName]   ${ctx.toolName} → $result")
            tracer.detail("  args: ${ctx.toolArgs}")
        }
    }
}

private fun summarize(response: Message.Assistant?): String = when {
    response == null -> "null (error?)"
    response.parts.any { it is MessagePart.Tool.Call } -> {
        val tool = response.parts.filterIsInstance<MessagePart.Tool.Call>().first()
        "${tool.tool}(tool_call)"
    }
    response.parts.any { it is MessagePart.Text } -> "DONE(text)"
    else -> "reasoning-only"
}
