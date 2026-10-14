package by.ciszkin.draftsllman.engine

import org.jetbrains.skia.Image
import org.jetbrains.skiko.Library
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestVisualCritiqueTest {
    @Test fun `foveated critique returns base64 pngs of the requested sizes`() {
        Library.load()
        val ex = ToolExecutor.default()
        ex.execute("""{"type":"set_view_box","width":200,"height":120}""")
        ex.execute("""{"type":"add_node","id":"a","role":"w","kind":"circle","width":20,"height":20,"preferredX":10,"preferredY":10}""")
        val r = ex.execute("""{"type":"request_visual_critique","nodeId":"a","globalSize":64,"roiSize":96}""")
        assertTrue(r is ToolResult.Critique, "expected Critique, got $r")
        val cr = r.result
        assertNotNull(cr.boxUsed, "a critique with nodeId must report the ROI box used")
        assertEquals(ex.engine().current.nodes["a"]!!.box, cr.boxUsed)
        val g = Image.makeFromEncoded(Base64.getDecoder().decode(cr.globalPngB64))
        assertEquals(64, g.width); assertEquals(64, g.height)
        val roi = Image.makeFromEncoded(Base64.getDecoder().decode(cr.roiPngB64!!))
        assertEquals(96, roi.width); assertEquals(96, roi.height)
    }

    @Test fun `critique without nodeId returns only the global map`() {
        Library.load()
        val ex = ToolExecutor.default()
        ex.execute("""{"type":"set_view_box","width":100,"height":100}""")
        val r = ex.execute("""{"type":"request_visual_critique","globalSize":64}""")
        val cr = (r as ToolResult.Critique).result
        assertTrue(cr.globalPngB64.isNotEmpty())
        assertNull(cr.roiPngB64)
    }

    @Test fun `critique rejects an unknown node`() {
        val ex = ToolExecutor.default()
        val r = ex.execute("""{"type":"request_visual_critique","nodeId":"nope"}""")
        assertTrue(r is ToolResult.Rejected, "expected Rejected, got $r")
    }

    @Test fun `critique rejects a non-positive size`() {
        val ex = ToolExecutor.default()
        val r = ex.execute("""{"type":"request_visual_critique","globalSize":0}""")
        assertTrue(r is ToolResult.Rejected, "expected Rejected, got $r")
    }
}
