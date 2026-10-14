package by.ciszkin.draftsllman.solver

import by.ciszkin.draftsllman.scene.Anchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnchorResolutionTest {
    @Test fun `geometric anchor name resolves directly`() {
        assertEquals(Anchor.CENTER, resolveAnchorName("CENTER", "n", emptyMap()))
        assertEquals(Anchor.TOP_CENTER, resolveAnchorName("TOP_CENTER", "n", emptyMap()))
    }

    @Test fun `named alias resolves via the node's alias map`() {
        val aliases: AnchorAliases = mapOf("wheel" to mapOf("hub" to Anchor.CENTER))
        assertEquals(Anchor.CENTER, resolveAnchorName("hub", "wheel", aliases))
    }

    @Test fun `geometric name shadows a same-named alias`() {
        val aliases: AnchorAliases = mapOf("n" to mapOf("CENTER" to Anchor.TOP_LEFT))
        assertEquals(Anchor.CENTER, resolveAnchorName("CENTER", "n", aliases))
    }

    @Test fun `a colliding alias name resolves to the geometric anchor - the Slices 3_4 crash case`() {
        // The exact live crash (qwen3.7-flash): alias "top" -> RIGHT_CENTER. Geometric-first
        // resolution returns Anchor.TOP (Y-axis), NOT the alias's RIGHT_CENTER (POINT). Both
        // validator and builder route through this resolver, so they agree — the validator now
        // rejects the collision up front rather than the builder crashing in Connect.
        val aliases: AnchorAliases = mapOf("seat_stay" to mapOf("top" to Anchor.RIGHT_CENTER))
        assertEquals(Anchor.TOP, resolveAnchorName("top", "seat_stay", aliases))
        assertEquals(Anchor.TOP, resolveAnchorNameOrNull("top", "seat_stay", aliases))
    }

    @Test fun `alias on one node does not leak to another`() {
        val aliases: AnchorAliases = mapOf("wheel" to mapOf("hub" to Anchor.CENTER))
        assertFailsWith<IllegalStateException> { resolveAnchorName("hub", "frame", aliases) }
    }

    @Test fun `unknown name throws`() {
        assertFailsWith<IllegalStateException> { resolveAnchorName("nope", "n", emptyMap()) }
    }

    @Test fun `resolveAliasMap resolves geometric values case-insensitively and drops invalid ones`() {
        // The alias-VALUE chokepoint shared by validateSkeleton and SkeletonBuilder: geometric values
        // resolve case-insensitively (so a lowercase value the validator accepts also builds); invalid
        // values are dropped (so an unreferenced bad alias never crashes the builder / SafeHarbor).
        val aliases = resolveAliasMap(mapOf(
            "hub" to "center",        // lowercase  -> CENTER
            "axle" to "BOTTOM_LEFT",  // canonical  -> BOTTOM_LEFT
            "h" to "Center",          // mixed-case -> CENTER
            "bad" to "middel",        // invalid    -> dropped
        ))
        assertEquals(Anchor.CENTER, aliases["hub"])
        assertEquals(Anchor.BOTTOM_LEFT, aliases["axle"])
        assertEquals(Anchor.CENTER, aliases["h"])
        assertEquals(null, aliases["bad"])
        assertEquals(null, aliases["absent"])
    }
}
