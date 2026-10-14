package by.ciszkin.draftsllman.architect

import by.ciszkin.draftsllman.engine.DraftsEngine
import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.solver.SolveResult
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class SkeletonConnectionTest {
    private fun approx(expected: Double, actual: Double, t: Double = 1e-6) =
        assertTrue(abs(expected - actual) <= t, "expected $expected got $actual")

    @Test fun `connect constraints link the frame to both wheels LLM-free`() {
        val skeleton = EmitSkeleton(
            components = listOf(
                SkeletonComponent("rear_wheel", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
                SkeletonComponent("front_wheel", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
                SkeletonComponent("frame", "chassis", "line", 200.0, 100.0,
                    mapOf("rearAxle" to "BOTTOM_LEFT", "frontAxle" to "BOTTOM_RIGHT")),
            ),
            constraints = listOf(
                SkeletonConstraint("link_rear", "connect", "frame:rearAxle", "rear_wheel:hub"),
                SkeletonConstraint("link_front", "connect", "frame:frontAxle", "front_wheel:hub"),
            ),
        )
        val graph = SkeletonBuilder.build(skeleton, CanvasSpec(500.0, 500.0))
        val (solved, result) = DraftsEngine().solveInto(graph, pinSizes = true)
        assertTrue(result is SolveResult.Success, "skeleton must solve: $result")

        val rear = solved.nodes["rear_wheel"]!!.box
        val front = solved.nodes["front_wheel"]!!.box
        val frame = solved.nodes["frame"]!!.box
        // frame:rearAxle (BOTTOM_LEFT) coincides with the rear wheel hub (CENTER).
        approx(frame.x, rear.cx)
        approx(frame.bottom, rear.cy)
        // frame:frontAxle (BOTTOM_RIGHT) coincides with the front wheel hub.
        approx(frame.right, front.cx)
        approx(frame.bottom, front.cy)
        // Wheels sit level, 200px apart (the frame's fixed axle width) — a connected skeleton.
        approx(rear.cy, front.cy)
        approx(200.0, front.cx - rear.cx)
    }
}
