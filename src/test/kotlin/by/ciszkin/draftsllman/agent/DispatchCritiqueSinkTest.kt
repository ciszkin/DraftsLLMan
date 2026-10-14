package by.ciszkin.draftsllman.agent

import by.ciszkin.draftsllman.contract.ToolArgs
import by.ciszkin.draftsllman.engine.ToolExecutor
import org.jetbrains.skiko.Library
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DispatchCritiqueSinkTest {
    private fun newExecutor(): ToolExecutor = ToolExecutor.default()

    @Test fun `dispatch offers the critique result to the sink`() {
        Library.load()
        val executor = newExecutor()
        executor.execute("""{"type":"set_view_box","width":120,"height":120}""")
        executor.execute("""{"type":"add_node","id":"a","role":"w","kind":"circle","width":20.0,"height":20.0}""")
        val sink = CritiqueImageSink()
        val feedback = dispatch(executor, sink, ToolArgs.RequestVisualCritique(nodeId = "a", globalSize = 64, roiSize = 64))
        assertTrue(feedback.contains("critique rendered"), "expected critique feedback, got: $feedback")
        val offered = sink.poll()
        assertNotNull(offered, "dispatch must offer the critique result to the sink")
        assertTrue(offered.globalPngB64.isNotEmpty())
        assertTrue(offered.roiPngB64!!.isNotEmpty())
    }

    @Test fun `a non-critique tool does not offer to the sink`() {
        val executor = newExecutor()
        executor.execute("""{"type":"set_view_box","width":100,"height":100}""")
        val sink = CritiqueImageSink()
        dispatch(executor, sink, ToolArgs.ExportSvg())
        assertNull(sink.poll(), "export_svg must not offer a critique to the sink")
    }
}
