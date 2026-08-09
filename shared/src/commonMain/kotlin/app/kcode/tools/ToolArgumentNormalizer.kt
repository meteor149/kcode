package app.kcode.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MAX_TOOL_ARGUMENT_BYTES = 64 * 1024
private const val MAX_TOOL_ARGUMENT_NESTING = 16

/**
 * Repairs an OpenAI-compatible function call only when its decoded shape matches the
 * registered tool schema. Some providers occasionally return the protocol-level
 * `arguments` field inside the arguments JSON again, and may encode that value as JSON
 * one or more additional times.
 *
 * Malformed, oversized, ambiguous, and schema-mismatched inputs are deliberately left
 * untouched so Koog can report its normal validation error.
 */
internal fun normalizeToolArguments(
    raw: String,
    parameterNames: Set<String>,
    requiredParameterNames: Set<String>,
): String {
    if (raw.length > MAX_TOOL_ARGUMENT_BYTES) return raw

    var element = runCatching { Json.parseToJsonElement(raw) }.getOrElse { return raw }
    repeat(MAX_TOOL_ARGUMENT_NESTING) {
        when (val current = element) {
            is JsonObject -> {
                val keys = current.keys
                if (keys.all(parameterNames::contains) &&
                    requiredParameterNames.all(keys::contains)
                ) {
                    return if (it == 0) raw else current.toString()
                }

                // Do not reinterpret a real parameter named "arguments" as a transport envelope.
                if ("arguments" in parameterNames || keys != setOf("arguments")) return raw
                element = current.getValue("arguments")
            }

            is JsonPrimitive -> {
                if (!current.isString || current.content.length > MAX_TOOL_ARGUMENT_BYTES) return raw
                element = runCatching { Json.parseToJsonElement(current.content) }.getOrElse { return raw }
            }

            else -> return raw
        }
    }
    return raw
}
