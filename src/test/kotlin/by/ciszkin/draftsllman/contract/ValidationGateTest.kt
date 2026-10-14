package by.ciszkin.draftsllman.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidationGateTest {
    @Serializable data class Args(val cx: Double, val radius: Double, val sizeRatio: Double)

    /** THE MONEY TEST (spec §5.1): sub-decimal ratio params must survive the gate UNCHANGED —
     *  the old snap step rewrote every JSON float to 1 decimal (0.05 -> 0.1, doubling the
     *  Draftsman's detail node sizes; observed in the 2026-08-18 live runs). */
    @Test fun `gate validates the raw element unchanged - sub-decimal params survive`() {
        val gate = ValidationGate(KotlinxSchemaGenerator(), OptimumSchemaValidator())
        val result = gate.validate(Args.serializer().descriptor, Json.parseToJsonElement("""{"cx":1.234,"radius":2.0,"sizeRatio":0.05}"""))
        assertTrue(result.errors.isEmpty())
        val element = result.element as JsonObject
        assertEquals("1.234", (element["cx"] as JsonPrimitive).content, "cx must not be rounded")
        assertEquals("0.05", (element["sizeRatio"] as JsonPrimitive).content, "sizeRatio must survive exactly")
    }

    @Test fun `gate still reports schema violations`() {
        val gate = ValidationGate(KotlinxSchemaGenerator(), OptimumSchemaValidator())
        val result = gate.validate(Args.serializer().descriptor, Json.parseToJsonElement("""{"cx":"not-a-number","radius":2.0}"""))
        assertTrue(result.errors.isNotEmpty(), "schema validation must still reject bad types")
    }
}
