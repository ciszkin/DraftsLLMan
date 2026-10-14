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
import java.util.Base64

/**
 * Builds the image [AttachmentSource.Image] part carrying the INPUT sketch PNG — what the
 * vision-first [by.ciszkin.draftsllman.sketch.SketchRunner] agent sees as its primary stimulus.
 */
internal fun sketchImageAttachment(png: ByteArray): AttachmentSource.Image =
    AttachmentSource.Image(
        content = AttachmentContent.Binary.Base64(Base64.getEncoder().encodeToString(png)),
        format = "png",
    )

/**
 * The S1 "sketch vision" graph: node-count-identical to [reasoningTolerantStrategy] (no
 * `maxIterations` cost), with ONE difference — `nodeCallLLM` injects the input sketch image
 * (polled once from [SketchSink]) as the first user message it sees, so the recovered structure is
 * driven by the raster, not a text prompt. The loop then behaves like the Architect: reasoning-only
 * responses are nudged ([requestLlmToleratingReasoning]), tool calls execute, text ends the run.
 */
fun sketchVisionStrategy(sink: SketchSink, maxNudges: Int = 3): AIAgentGraphStrategy<String, String> =
    strategy<String, String>("sketch_vision") {
        val nodeCallLLM by node<String, Message.Assistant>("callLLM") { message ->
            llm.writeSession {
                appendPrompt { user(message) }
                sink.poll()?.let { png ->
                    appendPrompt {
                        user {
                            text("Attached: the hand-drawn sketch image. Recover the clean structure it depicts.")
                            image(sketchImageAttachment(png))
                        }
                    }
                }
                requestLlmToleratingReasoning(maxNudges).routeToToolsWhenPresent()
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