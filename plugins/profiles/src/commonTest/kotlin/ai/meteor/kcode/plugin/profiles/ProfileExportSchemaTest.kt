package ai.meteor.kcode.plugin.profiles

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProfileExportSchemaTest {
    @Test
    fun boundedHexColorsDenyArbitraryStringsAndUnknownFormats() {
        val schema = parse("""{"formatVersion":1,"fields":{"config":{"json":{"type":"string","maxLength":9,"format":"hex-color"}}}}""")
        for (color in listOf("#123abc", "#Ff123456")) {
            val value = JsonPrimitive(color)
            assertEquals(value, schema.review(input(value)))
        }
        for (color in listOf("red", "secret", "#12345", "#1234567", "#GG123456", "#123456789", "#123456\n")) {
            assertNull(schema.review(input(JsonPrimitive(color))))
        }
        for (rule in listOf("""{"type":"string","maxLength":9,"format":"regex"}""",
            """{"type":"number","minimum":0,"maximum":1,"format":"hex-color"}""")) {
            assertFailsWith<IllegalArgumentException> { parse("""{"formatVersion":1,"fields":{"config":{"json":$rule}}}""") }
        }
    }

    @Test
    fun boundedNumbersPreserveDecimalsAndDenyStringsOverflowAndInvalidBounds() {
        val schema = parse("""{"formatVersion":1,"fields":{"config":{"json":{"type":"object","properties":{"temperature":{"type":"number","minimum":0,"maximum":2},"concurrency":{"type":"integer","minimum":1,"maximum":64}}}}}}""")
        val value = Json.parseToJsonElement("""{"temperature":0.25,"concurrency":64}""")
        assertEquals(value, schema.review(input(value)))
        for (text in listOf("""{"temperature":"0.25"}""", """{"temperature":2.01}""",
            """{"temperature":-0.01}""", """{"temperature":1e400}""",
            """{"concurrency":1.5}""", """{"concurrency":0}""", """{"concurrency":65}""",
            """{"temperature":true}""", """{"concurrency":9223372036854775807}""")) {
            assertNull(schema.review(input(Json.parseToJsonElement(text))))
        }
        for (rule in listOf("""{"type":"number"}""", """{"type":"number","minimum":2,"maximum":1}""",
            """{"type":"number","minimum":0,"maximum":1e400}""",
            """{"type":"string","maxLength":4,"minimum":0,"maximum":1}""",
            """{"type":"integer","minimum":1.5,"maximum":3}""",
            """{"type":"integer","minimum":0,"maximum":1e20}""")) {
            assertFailsWith<IllegalArgumentException> { parse("""{"formatVersion":1,"fields":{"config":{"json":$rule}}}""") }
        }
    }

    private fun parse(text: String) = ProfileExportSchema.decode(Json.parseToJsonElement(text))
    private fun input(value: JsonElement, kind: String = "json", field: String = "config") =
        ProfileExportValue("feature", "entry", kind, "profile", field, value)

    @Test
    fun schemaDeniesUnknownFieldsNestedSecretsAndWrongCodecs() {
        val schema = parse("""{"formatVersion":1,"fields":{"config":{"json":{"type":"object","required":["enabled"],"properties":{"enabled":{"type":"boolean"},"mode":{"type":"enum","values":["safe","fast"]}}}}}}""")
        val value = Json.parseToJsonElement("""{"enabled":true,"mode":"safe"}""")
        assertEquals(value, schema.review(input(value)))
        for (text in listOf("""{"enabled":"true"}""", """{"mode":"safe"}""",
            """{"enabled":true,"credential":"sentinel"}""", """{"enabled":true,"mode":"secret"}""")) {
            assertNull(schema.review(input(Json.parseToJsonElement(text))))
        }
        assertNull(schema.review(input(value, "string")))
        assertNull(schema.review(input(value, field = "inject/service")))
    }

    @Test
    fun explicitDictionaryMapsAcceptOnlyDeclaredTextValuesAndBounds() {
        val schema = parse("""{"formatVersion":1,"fields":{"config":{"unit":{"type":"null"},"json":{"type":"object","properties":{"translations":{"type":"object","additionalProperties":{"type":"string","maxLength":8}}}}}}}""")
        val value = Json.parseToJsonElement("""{"translations":{"hello":"Welcome"}}""")
        assertEquals(value, schema.review(input(value)))
        assertEquals(JsonNull, schema.review(input(JsonNull, "unit")))
        assertNull(schema.review(input(Json.parseToJsonElement("""{"translations":{"hello":{"secret":"x"}}}"""))))
        assertNull(schema.review(input(Json.parseToJsonElement("""{"translations":{"hello":"too long text"}}"""))))
    }

    @Test
    fun unsupportedSchemaSyntaxAndInvalidRequiredFieldsFailClosed() {
        assertFailsWith<SerializationException> { parse("""{"formatVersion":1,"allowEverything":true,"fields":{}}""") }
        for (rule in listOf("""{"type":"any"}""", """{"type":"string"}""",
            """{"type":"object","required":["missing"]}""", """{"type":"enum","values":[]}""")) {
            assertFailsWith<IllegalArgumentException> { parse("""{"formatVersion":1,"fields":{"config":{"json":$rule}}}""") }
        }
    }

    @Test
    fun enumerationMetadataCannotBeMutatedAfterReviewSelection() {
        val allowed = mutableMapOf<String, JsonElement>("label" to JsonPrimitive("portable"))
        val document = Json.parseToJsonElement("""{"formatVersion":1,"fields":{"config":{"json":{"type":"enum","values":[{}]}}}}""") as JsonObject
        val fields = document["fields"] as JsonObject
        val config = fields["config"] as JsonObject
        val rule = config["json"] as JsonObject
        val changed = JsonObject(document + ("fields" to JsonObject(mapOf("config" to JsonObject(mapOf("json" to
            JsonObject(rule + ("values" to kotlinx.serialization.json.JsonArray(listOf(JsonObject(allowed)))))))))))
        val schema = ProfileExportSchema.decode(changed)
        allowed["label"] = JsonPrimitive("secret")
        assertNull(schema.review(input(JsonObject(allowed))))
        assertEquals(JsonObject(mapOf("label" to JsonPrimitive("portable"))),
            schema.review(input(JsonObject(mapOf("label" to JsonPrimitive("portable"))))))
    }
}
