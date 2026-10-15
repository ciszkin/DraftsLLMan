package by.ciszkin.draftsllman.agent

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.prompt.message.Message

/**
 * Koog's `singleRunStrategy` with reasoning tolerance: `nodeCallLLM` uses [nodeCallLlmToleratingReasoning]
 * and `nodeSendToolResult` uses [requestLlmToleratingReasoning], so a thinking model that returns a
 * `Reasoning`-only response (no tool call, no text) is nudged (up to [maxNudges]) instead of stranding
 * the loop. Node-count-identical to `singleRunStrategy` (no `maxIterations` cost). Used by the Architect
 * (text-only, no vision) and any agent that needs reasoning tolerance without the vision/sliding-window
 * features of [draftsVisionStrategy].
 */
fun reasoningTolerantStrategy(maxNudges: Int = 3): AIAgentGraphStrategy<String, String> =
    strategy<String, String>("reasoning_tolerant") {
        // Inline variant of nodeCallLlmToleratingReasoning (the text+tool guard lives inside
        // requestLlmToleratingReasoning — live finding: a text+tool response must NOT finish the loop).
        val nodeCallLLM by node<String, Message.Assistant>("callLLM") { message ->
            llm.writeSession {
                appendPrompt { user(message) }
                requestLlmToleratingReasoning(maxNudges)
            }
        }
        val nodeExecuteTool by nodeExecuteTools()

        val nodeSendToolResult by node<ReceivedToolResults, Message.Assistant>("sendToolResult") { results ->
            llm.writeSession {
                appendPrompt {
                    user { results.toolResults.forEach { res -> toolResult(res.toMessagePart()) } }
                }
                requestLlmToleratingReasoning(maxNudges)
            }
        }

        edge(nodeStart forwardTo nodeCallLLM)
        edge(nodeCallLLM forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeCallLLM forwardTo nodeFinish onTextMessage { true })
        edge(nodeExecuteTool forwardTo nodeSendToolResult)
        edge(nodeSendToolResult forwardTo nodeFinish onTextMessage { true })
        edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCalls { true })
    }
