package by.ciszkin.draftsllman.library

import by.ciszkin.draftsllman.scene.CanvasSpec
import by.ciszkin.draftsllman.scene.CircleNode
import by.ciszkin.draftsllman.scene.InstanceNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.SceneGraph
import by.ciszkin.draftsllman.scene.Style
import by.ciszkin.draftsllman.scene.svg.SvgExporter
import kotlin.test.Test
import kotlin.test.assertTrue

class ComponentExportTest {
    @Test fun `export renders defs for masters and use for instances`() {
        val master = Master("wheel", listOf(
            CircleNode("rim", "rim", ResolvedBox(0.0, 0.0, 10.0, 10.0), Style()),
        ))
        val library = ComponentLibrary().apply { put(master) }
        val graph = SceneGraph(
            nodes = mapOf("inst" to InstanceNode("inst", "wheel", "wheel", 100.0, 200.0)),
            rootIds = listOf("inst"),
            canvas = CanvasSpec(500.0, 500.0),
        )
        val svg = SvgExporter.emit(graph, library)
        assertTrue(svg.contains("<defs>"), "expected <defs>: $svg")
        assertTrue(svg.contains("id=\"wheel\""), "expected master group id: $svg")
        assertTrue(svg.contains("<use"), "expected <use> for the instance: $svg")
        assertTrue(svg.contains("href=\"#wheel\""), "expected href to the master: $svg")
    }

    @Test fun `no library means no defs`() {
        val graph = SceneGraph(
            nodes = mapOf("c" to CircleNode("c", "c", ResolvedBox(0.0, 0.0, 10.0, 10.0), Style())),
            rootIds = listOf("c"),
            canvas = CanvasSpec(100.0, 100.0),
        )
        val svg = SvgExporter.emit(graph) // no library param — default empty
        assertTrue(!svg.contains("<defs>"), "no instances → no <defs>: $svg")
    }
}
