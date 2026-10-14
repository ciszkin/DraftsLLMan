package by.ciszkin.draftsllman.contract

import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.OutputCollector
import io.github.optimumcode.json.schema.ValidationOutput
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.schema.generator.json.serialization.SerializationClassJsonSchemaGenerator

data class ValidationError(val path: String, val message: String)

interface SchemaGenerator { fun generate(descriptor: SerialDescriptor): JsonElement }
interface SchemaValidator { fun validate(schema: JsonElement, element: JsonElement): List<ValidationError> }

/** Wraps kotlinx-schema-generator-json (0.x). Swap body for DIY walker if needed. */
class KotlinxSchemaGenerator : SchemaGenerator {
    private val gen = SerializationClassJsonSchemaGenerator()
    // SerialDescriptors are singletons, so the generated schema is invariant per descriptor — cache it.
    private val cache = HashMap<SerialDescriptor, JsonElement>()
    override fun generate(descriptor: SerialDescriptor): JsonElement = cache.getOrPut(descriptor) {
        val schema = Json.parseToJsonElement(gen.encodeToString(gen.generateSchema(descriptor)))
        stripPolymorphicRootAdditionalProperties(schema)
    }

    // The generator emits additionalProperties=false at the ROOT of sealed (oneOf) schemas, but
    // defines no root properties there — so every key would be "additional" and rejected before oneOf
    // is evaluated. Drop it; the per-variant $defs subtypes already enforce additionalProperties=false.
    private fun stripPolymorphicRootAdditionalProperties(schema: JsonElement): JsonElement {
        if (schema !is JsonObject) return schema
        return if ("oneOf" in schema) JsonObject(schema.filterKeys { it != "additionalProperties" }) else schema
    }
}

/** Wraps io.github.optimumcode:json-schema-validator (0.x). */
class OptimumSchemaValidator : SchemaValidator {
    // Compiling a schema into a validator is the expensive step; cache it by schema element.
    private val schemaCache = HashMap<JsonElement, JsonSchema>()
    override fun validate(schema: JsonElement, element: JsonElement): List<ValidationError> {
        val validator = schemaCache.getOrPut(schema) { JsonSchema.fromJsonElement(schema) }
        val basic = validator.validate(element, OutputCollector.basic())
        if (basic.valid) return emptyList()
        fun collect(u: ValidationOutput.OutputUnit): List<ValidationError> =
            (u.error?.let { listOf(ValidationError(u.instanceLocation.toString(), it)) } ?: emptyList()) +
                u.errors.flatMap { collect(it) }
        return basic.errors.flatMap { collect(it) }
    }
}
