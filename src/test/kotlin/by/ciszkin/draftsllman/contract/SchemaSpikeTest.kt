package by.ciszkin.draftsllman.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaSpikeTest {
    @Serializable @SerialName("spike")
    data class Sample(@SerialName("cx") val centerX: Double, val radius: Double, val fill: String = "#000")

    @Serializable
    sealed interface Poly {
        @Serializable @SerialName("add") data class Add(val id: String, val n: Int) : Poly
        @Serializable @SerialName("set") data class Set(val key: String) : Poly
    }

    private val gen = KotlinxSchemaGenerator()
    private val val_ = OptimumSchemaValidator()

    @Test fun `flat class schema validates types, defaults, extra fields`() {
        val schema = gen.generate(Sample.serializer().descriptor)
        assertTrue(val_.validate(schema, Json.parseToJsonElement("""{"cx":1.5,"radius":2.0}""")).isEmpty())
        assertTrue(val_.validate(schema, Json.parseToJsonElement("""{"cx":"no","radius":2.0}""")).isNotEmpty())
        assertTrue(val_.validate(schema, Json.parseToJsonElement("""{"cx":1.0,"radius":2.0,"bogus":9}""")).isNotEmpty())
    }

    @Test fun `sealed interface schema accepts known variant and rejects unknown discriminator`() {
        val schema = gen.generate(Poly.serializer().descriptor)
        val ok = val_.validate(schema, Json.parseToJsonElement("""{"type":"add","id":"a","n":1}"""))
        assertEquals(emptyList(), ok, "valid add variant must pass: $ok")
        assertTrue(val_.validate(schema, Json.parseToJsonElement("""{"type":"bogus"}""")).isNotEmpty())
        assertTrue(val_.validate(schema, Json.parseToJsonElement("""{"type":"add","id":"a"}""")).isNotEmpty())
    }

    @Test fun `toolargs schema includes the request_visual_critique variant`() {
        val schema = gen.generate(ToolArgs.serializer().descriptor)
        val text = schema.toString()
        assertTrue(text.contains("request_visual_critique"), "schema must list request_visual_critique: $text")
        val errs = val_.validate(schema, Json.parseToJsonElement("""{"type":"request_visual_critique","nodeId":"a","globalSize":256,"roiSize":512}"""))
        assertEquals(emptyList(), errs, "valid request_visual_critique must pass schema validation: $errs")
    }
}
