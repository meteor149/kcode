package ai.meteor.kcode.plugin

import ai.meteor.kcode.DefaultMaxAgentConcurrency
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal data class ConcurrencyLimit(val value: Int)

internal fun resolveConcurrencyLimit(value: Any?): ConcurrencyLimit {
    if (value is ConcurrencyLimit) return value
    val fields = when (value) {
        Unit -> JsonObject(emptyMap())
        is JsonObject -> value
        else -> error("Subagent configuration must be an object")
    }
    require(fields.keys.all { it == "maxConcurrency" }) { "Unknown subagent configuration field" }
    val raw = fields["maxConcurrency"]
    val limit = if (raw == null) DefaultMaxAgentConcurrency else {
        require(raw is JsonPrimitive && !raw.isString) { "maxConcurrency must be an integer" }
        requireNotNull(raw.intOrNull) { "maxConcurrency must be an integer" }
    }
    require(limit in 1..64) { "maxConcurrency must be between 1 and 64" }
    return ConcurrencyLimit(limit)
}
