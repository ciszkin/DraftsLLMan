package by.ciszkin.draftsllman.agent

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import by.ciszkin.draftsllman.engine.CritiqueResult

/**
 * Builds the image [AttachmentSource.Image] parts for a rendered [critique]: always the global map,
 * plus the ROI crop when one was produced (i.e. the critique call carried a `nodeId`). Pure.
 */
internal fun critiqueImageAttachments(critique: CritiqueResult): List<AttachmentSource.Image> = buildList {
    add(base64Png(critique.globalPngB64))
    critique.roiPngB64?.let { add(base64Png(it)) }
}

private fun base64Png(base64: String): AttachmentSource.Image =
    AttachmentSource.Image(content = AttachmentContent.Binary.Base64(base64), format = "png")

/**
 * The DraftsLLMan agent graph: identical in NODE COUNT to Koog's `singleRunStrategy` (so its
 * `maxIterations` budget and DONE termination are unchanged), but `nodeLLMSendToolResults` is REPLACED
 * by [nodeSendToolResult] — a 1-for-1 substitute (same `ReceivedToolResults -> Message.Assistant`) that,
 * before requesting the LLM, appends the rendered critique image(s) as a `Message.User` when a critique
 * was just produced (the only producer that offers into [sink]).
 *
 * Why REPLACE and not SPLICE: Koog's `maxAgentIterations` counts NODE EXECUTIONS, not LLM calls
 * (`AIAgentSubgraph.executeWithInnerContext` increments once per node, including `nodeFinish`). Adding a
 * separate injection node between `nodeExecuteTool` and `nodeSendToolResult` charges one extra iteration
 * per loop cycle, pushing a modest tool trace past the cap before `nodeFinish` can run — observed as
 * `MAX_ITERATIONS` even though the DONE message was already produced. Verified against Koog 1.1.1 sources.
 *
 * The image lands as its own `Message.User` after the tool-result user message and before `requestLLM()`
 * — exactly the placement `OllamaClient` requires (it honors image parts only in `Message.User`, and only
 * as `AttachmentContent.Binary`). Both `nodeCallLLM` and `nodeSendToolResult` use
 * [requestLlmToleratingReasoning] (shared via [ReasoningTolerance]) to tolerate reasoning-only responses.
 */
fun draftsVisionStrategy(sink: CritiqueImageSink, maxNudges: Int = 3): AIAgentGraphStrategy<String, String> =
    strategy<String, String>("drafts_vision") {
        val nodeCallLLM by nodeCallLlmToleratingReasoning(maxNudges)
        val nodeExecuteTool by nodeExecuteTools()

        // 1-for-1 replacement of nodeLLMSendToolResults (verbatim body) + conditional image injection.
        val nodeSendToolResult by node<ReceivedToolResults, Message.Assistant>("sendToolResult") { results ->
            val critique = sink.poll()
            llm.writeSession {
                if (critique != null) rewritePrompt { summarisePriorCritiqueImages(it) } // sliding window: drop prior critique images
                appendPrompt {
                    user { results.toolResults.forEach { res -> toolResult(res.toMessagePart()) } }
                    if (critique != null) {
                        user {
                            text("Visual critique attached: global map" +
                                (if (critique.roiPngB64 != null) " + ROI crop" else "") +
                                " of the current canvas. Review it and continue.")
                            critiqueImageAttachments(critique).forEach { image(it) }
                        }
                    }
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
