package app.kcode

import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.typeToken
import app.kcode.search.WebSearchTool
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolArgumentNormalizerTest {
    private val parameters = setOf("query", "maxResults")
    private val required = setOf("query")

    @Test
    fun leavesCanonicalArgumentsUnchanged() {
        val raw = """{"query":"Koog Endive Java","maxResults":10}"""

        assertEquals(raw, normalizeToolArguments(raw, parameters, required))
    }

    @Test
    fun unwrapsProtocolArgumentsObject() {
        val raw = """{"arguments":{"query":"Koog Endive Java","maxResults":10}}"""

        assertEquals(
            """{"query":"Koog Endive Java","maxResults":10}""",
            normalizeToolArguments(raw, parameters, required),
        )
    }

    @Test
    fun unwrapsQuotedJsonStringSeenOnDevice() {
        val canonical = """{"query":"Koog Endive Java","maxResults":10}"""
        val quotedCanonical = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.json.Json.encodeToString(canonical),
        )
        val raw = """{"arguments":$quotedCanonical}"""

        assertEquals(canonical, normalizeToolArguments(raw, parameters, required))
    }

    @Test
    fun normalizedDevicePayloadDecodesWithKoogSerializer() {
        val canonical = """{"query":"Koog Endive Java","maxResults":10}"""
        val raw = """{"arguments":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.Json.encodeToString(canonical))}}"""
        val normalized = normalizeToolArguments(raw, parameters, required)

        val decoded = KotlinxSerializer().decodeFromString<WebSearchTool.Args>(
            normalized,
            typeToken<WebSearchTool.Args>(),
        )

        assertEquals("Koog Endive Java", decoded.query)
        assertEquals(10, decoded.maxResults)
    }

    @Test
    fun unwrapsRepeatedObjectAndStringEnvelopes() {
        var nested = """{"query":"Koog Endive Java","maxResults":10}"""
        repeat(6) {
            nested = """{"arguments":${kotlinx.serialization.json.Json.encodeToString(nested)}}"""
        }

        assertEquals(
            """{"query":"Koog Endive Java","maxResults":10}""",
            normalizeToolArguments(nested, parameters, required),
        )
    }

    @Test
    fun rejectsUnknownOrMissingParameters() {
        val unknown = """{"arguments":"{\"query\":\"x\",\"unexpected\":true}"}"""
        val missing = """{"arguments":"{\"maxResults\":10}"}"""

        assertEquals(unknown, normalizeToolArguments(unknown, parameters, required))
        assertEquals(missing, normalizeToolArguments(missing, parameters, required))
    }

    @Test
    fun doesNotUnwrapARealArgumentsParameter() {
        val raw = """{"arguments":{"value":"kept"}}"""

        assertEquals(raw, normalizeToolArguments(raw, setOf("arguments"), setOf("arguments")))
    }
}
