package by.ciszkin.draftsllman.engine

import by.ciszkin.draftsllman.scene.Anchor
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.GroupNode
import by.ciszkin.draftsllman.scene.LineNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.ShapeNode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolExecutorTest {
    private fun approx(expected: Double, actual: Double, t: Double = 1e-9) =
        assertTrue(abs(expected - actual) <= t, "expected $expected got $actual")

    @Test fun `add_node then export yields svg`() {
        val ex = ToolExecutor.default()
        assertTrue(ex.execute("""{"type":"set_view_box","width":200,"height":120}""") is ToolResult.Committed)
        assertTrue(ex.execute("""{"type":"add_node","id":"a","role":"w","kind":"circle","width":20,"height":20,"preferredX":10,"preferredY":10}""") is ToolResult.Committed)
        val r = ex.execute("""{"type":"export_svg","dummy":0}""")
        assertTrue(r is ToolResult.Query)
        assertTrue((r.payload as String).contains("<circle"))
    }

    @Test fun `gate validates raw coordinate unchanged - no snapping`() {
        val ex = ToolExecutor.default()
        ex.execute("""{"type":"set_view_box","width":200,"height":120}""")
        ex.execute("""{"type":"add_node","id":"a","role":"w","kind":"circle","width":20,"height":20,"preferredX":10.234567,"preferredY":10.0}""")
        val node = ex.engine().current.nodes["a"] as CircleNode
        approx(10.234567, node.box.x) // raw value preserved, not snapped to 10.2
    }

    @Test fun `rollback on a contradictory constraint leaves the post-r1 graph intact`() {
        val ex = ToolExecutor.default()
        ex.execute("""{"type":"add_node","id":"a","role":"w","kind":"circle","width":10,"height":10}""")
        ex.execute("""{"type":"add_node","id":"b","role":"w","kind":"circle","width":10,"height":10}""")
        // LEFT-LEFT on both: position-only conflict (no width/height freedom) -> truly unsatisfiable.
        val r1 = ex.execute("""{"type":"add_constraint","id":"c1","kind":"align","sourceId":"a","sourceAnchor":"LEFT","targetId":"b","targetAnchor":"LEFT"}""")
        assertTrue(r1 is ToolResult.Committed, "r1 must commit: $r1")
        val before = ex.engine().current
        val r2 = ex.execute("""{"type":"add_constraint","id":"c2","kind":"distance","sourceId":"a","sourceAnchor":"LEFT","targetId":"b","targetAnchor":"LEFT","pixels":999}""")
        assertTrue(r2 is ToolResult.SolverFailure, "contradiction must reject: $r2")
        assertEquals(before, ex.engine().current)
    }

    @Test fun `malformed json is rejected before any mutation`() {
        val ex = ToolExecutor.default()
        val before = ex.engine().current
        val r = ex.execute("""{"type":"add_node","id":"a","role":"w","kind":"circle"}""") // missing size
        assertTrue(r is ToolResult.Rejected)
        assertEquals(before, ex.engine().current)
    }

    @Test fun `adding a group commits`() {
        val ex = ToolExecutor.default()
        ex.execute("""{"type":"add_node","id":"a","role":"w","kind":"circle","width":10,"height":10}""")
        val r = ex.execute("""{"type":"add_node","id":"g","role":"bike","kind":"group","width":1,"height":1,"childIds":["a"]}""")
        assertTrue(r is ToolResult.Committed, "group must commit: $r")
        assertTrue(ex.engine().current.nodes["g"] is GroupNode)
    }

    @Test fun `add_node reports the touched node and its solved box`() {
        val ex = ToolExecutor.default()
        ex.execute("""{"type":"set_view_box","width":200,"height":120}""")
        val r = ex.execute("""{"type":"add_node","id":"a","role":"w","kind":"circle","width":20,"height":20,"preferredX":10,"preferredY":10}""")
        val c = r as ToolResult.Committed
        assertEquals("a", c.touchedNodeId)
        val node = ex.engine().current.nodes["a"] as CircleNode
        assertEquals(node.box, c.touchedBox)
    }

    @Test fun `set_view_box reports no touched node`() {
        val ex = ToolExecutor.default()
        val r = ex.execute("""{"type":"set_view_box","width":200,"height":120}""")
        val c = r as ToolResult.Committed
        assertNull(c.touchedNodeId)
        assertNull(c.touchedBox)
    }

    @Test fun `add_constraint that moves nodes reports the union of moved boxes`() {
        val ex = ToolExecutor.default()
        ex.execute("""{"type":"set_view_box","width":200,"height":120}""")
        ex.execute("""{"type":"add_node","id":"a","role":"w","kind":"circle","width":10,"height":10,"preferredX":10,"preferredY":10}""")
        ex.execute("""{"type":"add_node","id":"b","role":"w","kind":"circle","width":10,"height":10,"preferredX":90,"preferredY":10}""")
        val pre = ex.engine().current
        val r = ex.execute("""{"type":"add_constraint","id":"c1","kind":"align","sourceId":"a","sourceAnchor":"LEFT","targetId":"b","targetAnchor":"LEFT"}""")
        val c = r as ToolResult.Committed
        val movedBoxes = ex.engine().current.nodes.entries
            .mapNotNull { (id, n) ->
                val preBox = pre.nodes[id]?.box
                if (preBox != null && preBox != n.box) n.box else null
            }
        assertTrue(movedBoxes.isNotEmpty(), "test setup must move at least one node")
        assertNotNull(c.touchedBox, "a moving constraint must report a touched box")
        assertEquals(ResolvedBox.unionOf(movedBoxes), c.touchedBox)
    }

    @Test fun `add_constraint that moves nothing reports a null touched box`() {
        val ex = ToolExecutor.default()
        ex.execute("""{"type":"set_view_box","width":200,"height":120}""")
        ex.execute("""{"type":"add_node","id":"a","role":"w","kind":"circle","width":10,"height":10,"preferredX":10,"preferredY":10}""")
        ex.execute("""{"type":"add_node","id":"b","role":"w","kind":"circle","width":10,"height":10,"preferredX":10,"preferredY":10}""")
        val r = ex.execute("""{"type":"add_constraint","id":"c1","kind":"align","sourceId":"a","sourceAnchor":"LEFT","targetId":"b","targetAnchor":"LEFT"}""")
        val c = r as ToolResult.Committed
        assertNull(c.touchedBox, "no-op constraint must report null touched box")
    }

    @Test fun `remove_node reports the removed node and its pre-removal box`() {
        val ex = ToolExecutor.default()
        ex.execute("""{"type":"set_view_box","width":200,"height":120}""")
        ex.execute("""{"type":"add_node","id":"a","role":"w","kind":"circle","width":20,"height":20,"preferredX":10,"preferredY":10}""")
        val preBox = (ex.engine().current.nodes["a"] as CircleNode).box
        val r = ex.execute("""{"type":"remove_node","id":"a"}""")
        val c = r as ToolResult.Committed
        assertEquals("a", c.touchedNodeId)
        assertEquals(preBox, c.touchedBox)
    }

    @Test fun `add_node anchors persist and remove_node cleans them up`() {
        val ex = ToolExecutor.default()
        ex.execute("""{"type":"set_view_box","width":200,"height":120}""")
        ex.execute("""{"type":"add_node","id":"wheel","role":"w","kind":"circle","width":20,"height":20,"preferredX":40,"preferredY":60,"anchors":{"hub":"CENTER","top":"TOP_CENTER"}}""")
        assertEquals(Anchor.CENTER, ex.engine().current.anchorAliases["wheel"]?.get("hub"))
        assertEquals(Anchor.TOP_CENTER, ex.engine().current.anchorAliases["wheel"]?.get("top"))

        ex.execute("""{"type":"remove_node","id":"wheel"}""")
        assertTrue(ex.engine().current.anchorAliases["wheel"] == null, "remove_node must drop the node's aliases")
    }

    @Test fun `connect via a named anchor coincides the resolved points`() {
        val ex = ToolExecutor.default()
        ex.execute("""{"type":"set_view_box","width":200,"height":120}""")
        ex.execute("""{"type":"add_node","id":"wheel","role":"w","kind":"circle","width":40,"height":40,"preferredX":40,"preferredY":60,"anchors":{"hub":"CENTER"}}""")
        ex.execute("""{"type":"add_node","id":"frame","role":"w","kind":"circle","width":10,"height":10,"preferredX":150,"preferredY":30}""")
        val r = ex.execute("""{"type":"add_constraint","id":"c1","kind":"connect","sourceId":"frame","sourceAnchor":"CENTER","targetId":"wheel","targetAnchor":"hub"}""")
        assertTrue(r is ToolResult.Committed, "connect via named alias must commit: $r")

        val wheel = ex.engine().current.nodes["wheel"]!!.box
        val frame = ex.engine().current.nodes["frame"]!!.box
        approx(wheel.cx, frame.cx)   // hub -> CENTER: frame center meets wheel center
        approx(wheel.cy, frame.cy)
    }

    // region add_nodes (Slice 5 Task 5) — the Draftsman's unified detail-injection tool, dispatched
    // through the same transactional runMutation path as add_node (validate -> mutate -> solve ->
    // verify -> commit). GeometryTools computes positions; the executor adds the nodes.

    @Test fun `add_nodes single_node adds a detail node and reports a descriptive message`() {
        val ex = ToolExecutor.default()
        ex.execute("""{"type":"set_view_box","width":200,"height":120}""")
        ex.execute("""{"type":"add_node","id":"target","role":"wheel","kind":"circle","width":80,"height":80,"preferredX":60,"preferredY":60}""")
        val r = ex.execute("""{"type":"add_nodes","target_id":"target","node_name":"spoke","node_type":"point","preset":"single_node","center_anchor":"CENTER","size_ratio":0.1}""")
        val c = r as ToolResult.Committed
        assertEquals("ok: added 1 node of type 'point' via single_node to 'target'", c.message)
        assertEquals("target", c.touchedNodeId)
        assertNotNull(c.touchedBox, "add_nodes must report the detail region box for ROI")
        assertTrue(ex.engine().current.nodes.containsKey("target_spoke_0"), "detail node 'target_spoke_0' was not added")
    }

    @Test fun `add_nodes radial_pattern adds count nodes`() {
        val ex = ToolExecutor.default()
        ex.execute("""{"type":"set_view_box","width":200,"height":120}""")
        ex.execute("""{"type":"add_node","id":"target","role":"wheel","kind":"circle","width":80,"height":80,"preferredX":60,"preferredY":60}""")
        val r = ex.execute("""{"type":"add_nodes","target_id":"target","node_name":"spoke","node_type":"point","preset":"radial_pattern","count":4,"center_anchor":"CENTER","boundary_anchor":"BOTTOM_RIGHT","size_ratio":0.1}""")
        val c = r as ToolResult.Committed
        assertEquals("ok: added 4 nodes of type 'point' via radial_pattern to 'target'", c.message)
        val added = ex.engine().current.nodes.keys.filter { it.startsWith("target_spoke_") }
        assertEquals(4, added.size, "radial_pattern count=4 must add four nodes: $added")
    }

    @Test fun `add_nodes with an unknown target is rejected`() {
        val ex = ToolExecutor.default()
        ex.execute("""{"type":"set_view_box","width":200,"height":120}""")
        val r = ex.execute("""{"type":"add_nodes","target_id":"nope","node_name":"x","node_type":"point","preset":"single_node"}""")
        assertTrue(r is ToolResult.Rejected, "unknown target must be Rejected by semantic validation: $r")
    }

    @Test fun `add_nodes line survives the executor re-solve and is committed`() {
        // Locks in the non-point path: a node_type=line detail node must pass the verifier's derived-box
        // branch, survive the solver's position stays, and end up committed as a LineNode.
        val ex = ToolExecutor.default()
        ex.execute("""{"type":"set_view_box","width":200,"height":120}""")
        ex.execute("""{"type":"add_node","id":"target","role":"wheel","kind":"circle","width":80,"height":80,"preferredX":60,"preferredY":60}""")
        val r = ex.execute("""{"type":"add_nodes","target_id":"target","node_name":"spoke","node_type":"line","preset":"linear_pattern","count":3,"start_anchor":"LEFT_CENTER","end_anchor":"RIGHT_CENTER","size_ratio":0.2}""")
        val c = r as ToolResult.Committed
        assertEquals("ok: added 3 nodes of type 'line' via linear_pattern to 'target'", c.message)
        val added = ex.engine().current.nodes.filterKeys { it.startsWith("target_spoke_") }
        assertEquals(3, added.size, "linear_pattern count=3 must commit three line nodes: ${added.keys}")
        assertTrue(added.values.all { it is LineNode }, "every detail node must be a LineNode: ${added.values.map { it::class.simpleName }}")
    }

    @Test fun `add_nodes shape survives the executor re-solve and is committed`() {
        // Same non-point guarantee for node_type=shape (ShapeNode, also a derived-box node).
        val ex = ToolExecutor.default()
        ex.execute("""{"type":"set_view_box","width":200,"height":120}""")
        ex.execute("""{"type":"add_node","id":"target","role":"wheel","kind":"circle","width":80,"height":80,"preferredX":60,"preferredY":60}""")
        val r = ex.execute("""{"type":"add_nodes","target_id":"target","node_name":"cap","node_type":"shape","preset":"boundary_pattern","count":4,"center_anchor":"CENTER","size_ratio":0.1}""")
        assertTrue(r is ToolResult.Committed, "add_nodes shape must commit: $r")
        val added = ex.engine().current.nodes.filterKeys { it.startsWith("target_cap_") }
        assertEquals(4, added.size, "boundary_pattern count=4 must commit four shape nodes: ${added.keys}")
        assertTrue(added.values.all { it is ShapeNode }, "every detail node must be a ShapeNode: ${added.values.map { it::class.simpleName }}")
    }

    // endregion
}
