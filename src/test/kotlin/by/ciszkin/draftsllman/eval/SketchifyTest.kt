package by.ciszkin.draftsllman.eval

import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.Color as SceneColor
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.Style
import org.jetbrains.skiko.Library
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SketchifyTest {

    /** Tiny canvas with a single black circle filling most of the frame. */
    private fun circleGraph(): SceneGraph = SceneGraph(
        nodes = mapOf(
            "c" to CircleNode(
                id = "c",
                semanticRole = "component",
                box = ResolvedBox(40.0, 40.0, 120.0, 120.0),
                style = Style(fill = SceneColor.BLACK),
            ),
        ),
        rootIds = listOf("c"),
        canvas = CanvasSpec(200.0, 200.0),
    )

    private fun assertPng(bytes: ByteArray, tag: String) {
        assertTrue(bytes.size > 200, "$tag: size ${bytes.size} should exceed 200 (canvas 200x200 PNG)")
        assertTrue(
            bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte(),
            "$tag: missing PNG magic (0x89 0x50 0x4E 0x47)",
        )
    }

    @Test
    fun `produces a valid non-trivial PNG`() {
        Library.load()
        val bytes = Sketchify(jitterLevel = 1, seed = 42).sketch(circleGraph())
        assertPng(bytes, "jitter=1")
    }

    @Test
    fun `same seed produces identical bytes and different seeds differ`() {
        Library.load()
        val a = Sketchify(jitterLevel = 1, seed = 7).sketch(circleGraph())
        val b = Sketchify(jitterLevel = 1, seed = 7).sketch(circleGraph())
        val c = Sketchify(jitterLevel = 1, seed = 99).sketch(circleGraph())
        assertContentEquals(a, b, "same (graph, seed, jitter) must be byte-identical")
        assertFalse(a.contentEquals(c), "different seeds must not produce identical bytes")
    }

    @Test
    fun `blank canvas sketch differs from an inked sketch but both are valid`() {
        Library.load()
        val inked = Sketchify(jitterLevel = 0, seed = 42).sketch(circleGraph())
        val blank = Sketchify(jitterLevel = 0, seed = 42).sketch(
            SceneGraph(canvas = CanvasSpec(200.0, 200.0)),
        )
        assertPng(inked, "inked")
        assertPng(blank, "blank")
        assertFalse(inked.contentEquals(blank), "inked and blank canvases must not produce identical bytes")
    }

    @Test
    fun `higher jitter level still produces a valid PNG`() {
        Library.load()
        for (level in 2..3) {
            val bytes = Sketchify(jitterLevel = level, seed = 42).sketch(circleGraph())
            assertPng(bytes, "jitter=$level")
        }
    }
}
