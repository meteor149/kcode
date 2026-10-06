package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.DynamicPluginSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Selected by the host from the exact frozen generation, before reviewing any values. */
fun interface ProfileExportReviewFactory {
    suspend fun create(source: CommittedProfileGeneration): ProfileExportReview
}

/** The supplied reader must verify the locked deployment and return only its manifest metadata. */
class ProfilePackageExportReviews(
    private val readSchema: suspend (DynamicPluginSpec) -> JsonElement?,
) : ProfileExportReviewFactory {
    override suspend fun create(source: CommittedProfileGeneration): ProfileExportReview {
        source.validate(restoring = true)
        val policies = linkedMapOf<String, ProfileExportReview>()
        for (stored in source.composition.external) {
            val schema = readSchema(stored.toSpec()) ?: continue
            policies[stored.id] = ProfileExportSchema.decode(schema)
        }
        return ProfileExportPolicies(policies)
    }
}

/** A closed, declarative schema dialect; unknown fields and rule types fail closed. */
@Serializable
class ProfileExportSchema private constructor(
    private val formatVersion: Int,
    private val fields: Map<String, Map<String, Rule>>,
) : ProfileExportReview {
    @Serializable
    private data class Rule(
        val type: String,
        val values: List<JsonElement>? = null,
        val properties: Map<String, Rule> = emptyMap(),
        val required: Set<String> = emptySet(),
        val additionalProperties: Rule? = null,
        val maxLength: Int? = null,
    ) {
        fun validate(depth: Int = 0) {
            require(depth <= 16 && type in setOf("null", "boolean", "integer", "enum", "object", "string")) { "Invalid export schema rule" }
            require(if (type == "enum") !values.isNullOrEmpty() && values.distinct().size == values.size else values == null) {
                "Invalid export schema enumeration"
            }
            require(type == "object" || properties.isEmpty() && required.isEmpty() && additionalProperties == null) { "Invalid export schema properties" }
            require(if (type == "string") maxLength != null && maxLength in 1..65536 else maxLength == null) { "Invalid export string limit" }
            require(required.all { it in properties } && properties.keys.all(String::isNotBlank)) { "Invalid required export properties" }
            properties.values.forEach { it.validate(depth + 1) }
            additionalProperties?.validate(depth + 1)
        }

        fun accepts(value: JsonElement): Boolean = when (type) {
            "null" -> value == JsonNull
            "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
            "enum" -> value in values.orEmpty()
            "string" -> value is JsonPrimitive && value.isString && value.content.length <= requireNotNull(maxLength)
            "object" -> value is JsonObject && required.all { it in value } && value.all { (key, item) ->
                (properties[key] ?: additionalProperties)?.accepts(item) == true
            }
            else -> false
        }
    }

    override fun review(value: ProfileExportValue): JsonElement? {
        val rule = fields[value.field]?.get(value.configurationKind) ?: return null
        return value.value.takeIf(rule::accepts)
    }

    companion object {
        fun decode(document: JsonElement): ProfileExportSchema = Json.decodeFromJsonElement(serializer(),
            Json.parseToJsonElement(document.toString())).also { schema ->
            require(schema.formatVersion == 1 && schema.fields.isNotEmpty()) { "Unsupported export schema" }
            schema.fields.forEach { (name, field) ->
                require(name == "config" || name.startsWith("inject/") && name.length > 7 ||
                    name.startsWith("intercept/") && name.length > 10) { "Invalid export schema field" }
                require(field.isNotEmpty() && field.keys.all { it in setOf("unit", "null", "boolean", "int", "long", "string", "json") }) { "Invalid export codec" }
                field.values.forEach { it.validate() }
            }
        }
    }
}
