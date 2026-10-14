package by.ciszkin.draftsllman.library

import by.ciszkin.draftsllman.scene.InstanceNode
import by.ciszkin.draftsllman.scene.ResolvedBox
import by.ciszkin.draftsllman.scene.Style
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstanceNodeTest {
    @Test fun `instance node emits a use element referencing its master`() {
        val node = InstanceNode("inst", "wheel", "wheel-master", x = 100.0, y = 200.0, scale = 1.5)
        val svg = node.toSvgString()
        assertTrue(svg.startsWith("<use"), "expected a <use> element: $svg")
        assertTrue(svg.contains("href=\"#wheel-master\""), "expected href to the master: $svg")
        assertTrue(svg.contains("x=\"100.00\""), "expected x position: $svg")
        assertTrue(svg.contains("scale(1.50)"), "expected scale transform: $svg")
    }

    @Test fun `withBox updates position`() {
        val node = InstanceNode("inst", "wheel", "wheel-master", x = 0.0, y = 0.0)
        val moved = node.withBox(ResolvedBox(50.0, 60.0, 0.0, 0.0)) as InstanceNode
        assertEquals(50.0, moved.x)
        assertEquals(60.0, moved.y)
    }

    @Test fun `box is derived from x y`() {
        val node = InstanceNode("inst", "wheel", "wheel-master", x = 30.0, y = 40.0)
        assertEquals(ResolvedBox(30.0, 40.0, 0.0, 0.0), node.box)
    }

    @Test fun `scale 1 0 emits no transform`() {
        val node = InstanceNode("inst", "wheel", "wheel-master", x = 0.0, y = 0.0, scale = 1.0)
        val svg = node.toSvgString()
        assertTrue(!svg.contains("transform"), "scale 1.0 should not emit a transform: $svg")
    }
}
