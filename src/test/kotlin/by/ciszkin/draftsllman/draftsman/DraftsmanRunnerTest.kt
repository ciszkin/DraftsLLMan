package by.ciszkin.draftsllman.draftsman

import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.MessagePart
import by.ciszkin.draftsllman.agent.ScriptedPromptExecutor
import by.ciszkin.draftsllman.agent.Tracer
import by.ciszkin.draftsllman.architect.ComponentBrief
import by.ciszkin.draftsllman.scene.Anchor
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.LineNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneNode
import by.ciszkin.draftsllman.scene.Style
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the Draftsman through a scripted executor (no network): DRAWN, EMPTY, MAX_ITERATIONS, NO_OP,
 * and run-to-run isolation (fresh scene per Brief).
 */
class DraftsmanRunnerTest {
    private val model = LLModel(provider = LLMProvider.Ollama, id = "test")

    private fun brief(id: String) =
        ComponentBrief(id, "role", "draw a thing", ResolvedBox(0.0, 0.0, 100.0, 100.0))

    @Test fun `drawn component carries the fragment`() {
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall("add_node", """{"id":"a","role":"x","kind":"circle","width":10.0,"height":10.0}""")
            enqueueDone()
        }
        val outcome = DraftsmanRunner(scripted, model).run(brief("comp"))

        assertEquals(DraftsmanStatus.DRAWN, outcome.status)
        val component = outcome.component!!
        assertEquals("comp", component.briefId)
        assertTrue(component.nodes.containsKey("a"), "expected the drawn node in the component")
    }

    @Test fun `finishing without drawing yields EMPTY`() {
        val scripted = ScriptedPromptExecutor().apply { enqueueDone() }
        val outcome = DraftsmanRunner(scripted, model).run(brief("comp"))

        assertEquals(DraftsmanStatus.EMPTY, outcome.status)
        assertNull(outcome.component)
    }

    @Test fun `endless draws terminate MAX_ITERATIONS`() {
        val scripted = ScriptedPromptExecutor().apply {
            repeat(8) { i -> enqueueToolCall("add_node", """{"id":"n$i","role":"x","kind":"circle","width":5.0,"height":5.0}""") }
        }
        val outcome = DraftsmanRunner(scripted, model, maxIterations = 3).run(brief("comp"))

        assertEquals(DraftsmanStatus.MAX_ITERATIONS, outcome.status)
        val partial = outcome.component
            ?: error("MAX_ITERATIONS with draws should carry a partial fragment")
        assertTrue(partial.nodes.isNotEmpty(), "the partial fragment contains the drawn nodes")
    }

    @Test fun `read-only stalls terminate NO_OP`() {
        val scripted = ScriptedPromptExecutor().apply {
            repeat(4) { enqueueToolCall("export_svg", """{}""") }
        }
        val outcome = DraftsmanRunner(scripted, model).run(brief("comp"))

        assertEquals(DraftsmanStatus.NO_OP, outcome.status)
        assertNull(outcome.component, "export_svg draws nothing -> no fragment")
    }

    @Test fun `each run is isolated — fresh scene per brief`() {
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall("add_node", """{"id":"a","role":"x","kind":"circle","width":10.0,"height":10.0}""")
            enqueueDone()
            enqueueToolCall("add_node", """{"id":"b","role":"x","kind":"circle","width":10.0,"height":10.0}""")
            enqueueDone()
        }
        val runner = DraftsmanRunner(scripted, model)
        val first = runner.run(brief("one"))
        val second = runner.run(brief("two"))

        assertEquals(DraftsmanStatus.DRAWN, first.status)
        assertEquals(DraftsmanStatus.DRAWN, second.status)
        val firstNodes = (first.component ?: error("run 1 should be DRAWN")).nodes
        val secondNodes = (second.component ?: error("run 2 should be DRAWN")).nodes
        assertTrue(firstNodes.containsKey("a"))
        assertTrue(secondNodes.containsKey("b"))
        assertFalse(secondNodes.containsKey("a"), "run 2 must not see run 1's nodes (fresh scene)")
    }

    @Test fun `a tracer captures the draftsman LLM turns and tool calls`() {
        val captured = StringBuilder()
        val tracer = Tracer(briefSink = { captured.appendLine(it) })
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall("add_node", """{"id":"a","role":"x","kind":"circle","width":10.0,"height":10.0}""")
            enqueueDone()
        }
        DraftsmanRunner(scripted, model, tracer = tracer).run(brief("comp"))

        val log = captured.toString()
        assertTrue(log.contains("[Draftsman:comp] LLM turn 1"), "expected draftsman LLM turn in trace: $log")
        assertTrue(log.contains("add_node"), "expected tool call in trace: $log")
    }

    /**
     * The Phase 3 acceptance bar at the unit level: [DraftsmanRunner.runOnSkeleton] seeds the isolated
     * engine with the component at its SOLVED GLOBAL box, so `add_nodes` computes detail in GLOBAL
     * coordinates. A wheel solved at global center (300,300) + a radial_pattern of 4 spokes MUST yield
     * spokes whose start points lie on a circle of radius 20 around (300,300) — never around (0,0).
     */
    @Test fun `runOnSkeleton seeds the global solved box so add_nodes detail lands at global positions`() {
        // Solved wheel: box (260,260,80,80) -> center (300,300); named anchor "hub" -> CENTER.
        val solvedBox = ResolvedBox(260.0, 260.0, 80.0, 80.0)
        val solvedNode: SceneNode = CircleNode("rear", "wheel", solvedBox, Style())
        val brief = ComponentBrief("rear", "wheel", "add spokes", solvedBox)
        val scripted = ScriptedPromptExecutor().apply {
            enqueueToolCall(
                "add_nodes",
                """{"target_id":"rear","node_name":"spoke","node_type":"line","preset":"radial_pattern","count":4,"center_anchor":"hub","boundary_anchor":"TOP_CENTER","displacement":0.5}""",
            )
            enqueueDone()
        }

        val outcome = DraftsmanRunner(scripted, model).runOnSkeleton(
            brief, solvedNode, anchors = mapOf("hub" to Anchor.CENTER), canvas = CanvasSpec(500.0, 500.0),
        )

        assertEquals(DraftsmanStatus.DRAWN, outcome.status)
        val detail = outcome.component ?: error("expected a detail component")
        // The seed node is excluded — only the 4 fresh spokes are returned.
        assertFalse(detail.nodes.containsKey("rear"), "seed node must not be in the detail component")
        val spokes = (0..3).map { i ->
            detail.nodes["rear_spoke_$i"] as? LineNode
                ?: error("expected rear_spoke_$i to be a LineNode: ${detail.nodes["rear_spoke_$i"]}")
        }
        // radius = dist(CENTER, TOP_CENTER) = 40; displacement 0.5 -> r = 20. Each start is 20 from (300,300).
        spokes.forEachIndexed { i, spoke ->
            val d = spoke.start
            val dist = sqrt((d.first - 300.0) * (d.first - 300.0) + (d.second - 300.0) * (d.second - 300.0))
            assertTrue(abs(dist - 20.0) < 1e-9, "spoke $i start $d is $dist from (300,300), expected 20")
        }
        // Centroid of evenly-spaced radial starts == the solved global center (300,300).
        val cx = spokes.map { it.start.first }.average()
        val cy = spokes.map { it.start.second }.average()
        assertTrue(abs(cx - 300.0) < 1e-9, "spoke centroid x=$cx expected 300")
        assertTrue(abs(cy - 300.0) < 1e-9, "spoke centroid y=$cy expected 300")
        // Detail is GLOBAL, not at the local origin.
        assertTrue(spokes.all { abs(it.start.first) > 1.0 || abs(it.start.second) > 1.0 },
            "spokes must not sit at the local origin: ${spokes.map { it.start }}")
    }

    @Test fun `runOnSkeleton finishing without add_nodes yields EMPTY and no detail`() {
        val solvedBox = ResolvedBox(260.0, 260.0, 80.0, 80.0)
        val solvedNode: SceneNode = CircleNode("rear", "wheel", solvedBox, Style())
        val brief = ComponentBrief("rear", "wheel", "add spokes", solvedBox)
        val scripted = ScriptedPromptExecutor().apply { enqueueDone() }

        val outcome = DraftsmanRunner(scripted, model).runOnSkeleton(
            brief, solvedNode, anchors = emptyMap(), canvas = CanvasSpec(500.0, 500.0),
        )

        assertEquals(DraftsmanStatus.EMPTY, outcome.status)
        assertNull(outcome.component)
    }

    /** The coordinate-free handoff (spec 2026-08-19): the Phase-3 system prompt carries ZERO
     *  absolute coordinates — qualitative size, declared anchors, connections instead. */
    @Test fun `runOnSkeleton prompt is coordinate-free and lists declared anchors`() {
        val solvedBox = ResolvedBox(260.0, 260.0, 80.0, 80.0)
        val solvedNode: SceneNode = CircleNode("rear", "wheel", solvedBox, Style())
        val brief = ComponentBrief("rear", "wheel", "add spokes", solvedBox)
        val scripted = ScriptedPromptExecutor().apply { enqueueDone() }

        DraftsmanRunner(scripted, model).runOnSkeleton(
            brief, solvedNode,
            anchors = mapOf("hub" to Anchor.CENTER),
            canvas = CanvasSpec(500.0, 500.0),
            connections = listOf(ComponentConnection("connect", "rear:hub", "frame:rearAxle")),
        )

        val promptText = scripted.prompts.first().messages.joinToString(" ") { msg ->
            msg.parts.filterIsInstance<MessagePart.Text>().joinToString(" ") { it.text }
        }
        assertTrue(promptText.contains("Declared anchors: hub (CENTER)"), "anchors must be listed:\n$promptText")
        // 80/500 = 16% -> nearest 5% band = 15%.
        assertTrue(promptText.contains("~15% of canvas width"), "size band expected:\n$promptText")
        assertTrue(promptText.contains("rear:hub ↔ frame:rearAxle (connect)"), "connections expected:\n$promptText")
        assertFalse(promptText.contains("top-left="), "absolute position leaked:\n$promptText")
        assertFalse(Regex("\\(-?\\d+, -?\\d+\\)").containsMatchIn(promptText), "pair coordinates leaked:\n$promptText")
        assertFalse(Regex("\\d+×\\d+").containsMatchIn(promptText), "W×H size leaked:\n$promptText")
    }
}
