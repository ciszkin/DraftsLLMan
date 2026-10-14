package by.ciszkin.draftsllman.architect

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SkeletonSerializationTest {
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = false; classDiscriminator = "type" }

    @Test fun `EmitSkeleton round-trips components and constraints`() {
        val skeleton = EmitSkeleton(
            components = listOf(
                SkeletonComponent("wheel", "wheel", "circle", 80.0, 80.0, mapOf("hub" to "CENTER")),
            ),
            constraints = listOf(
                SkeletonConstraint("c1", "connect", "frame:rearAxle", "wheel:hub"),
                SkeletonConstraint("gap", "distance_ratio", "a:right", "b:left", ratio = 0.25, relativeTo = "canvas_width"),
            ),
        )
        val encoded = json.encodeToString(EmitSkeleton.serializer(), skeleton)
        val decoded = json.decodeFromString(EmitSkeleton.serializer(), encoded)
        assertEquals(skeleton, decoded)
    }
}
