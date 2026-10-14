package by.ciszkin.draftsllman.contract

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonElement

data class GateResult(val errors: List<ValidationError>, val element: JsonElement)

/** Schema-validation chokepoint for LLM tool args. Validates the RAW element — no rewriting:
 *  runtime values enter the Scene Graph exactly as emitted (Zero-Coordinate policy §9; the old
 *  1-decimal snap corrupted sub-decimal ratio params like size_ratio 0.05 -> 0.1). */
class ValidationGate(
    private val generator: SchemaGenerator,
    private val validator: SchemaValidator,
) {
    fun validate(descriptor: SerialDescriptor, raw: JsonElement): GateResult =
        GateResult(validator.validate(generator.generate(descriptor), raw), raw)
}
