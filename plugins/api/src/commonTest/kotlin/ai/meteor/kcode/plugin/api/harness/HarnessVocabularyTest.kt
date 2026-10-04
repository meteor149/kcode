package ai.meteor.kcode.plugin.api.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class HarnessVocabularyTest {
    @Test
    fun ambientEnvironmentScrubIsCaseInsensitiveAndKeepsExecutionConfiguration() {
        val parent = mapOf(
            "PATH" to "tools", "HOME" to "workspace", "http_proxy" to "proxy",
            "DEEPSEEK_API_KEY" to "fixture", "password" to "fixture", "authToken" to "fixture",
            "secret" to "fixture", "dsh_SESSION_ID" to "old-owner", "DSH_AGENT_ID" to "old-agent",
        )
        assertEquals(mapOf("PATH" to "tools", "HOME" to "workspace", "http_proxy" to "proxy"), scrubHarnessParentEnvironment(parent))
        assertEquals("old-owner", parent["dsh_SESSION_ID"])
    }

    @Test
    fun toolCallSerializerPreservesRawArgumentsWithoutParsingOrNormalizing() {
        val arguments = "{\"x\":1,\"x\":2, \"unknown\":null}"
        val call = HarnessToolCall(1, 2, "call-1", "plugin.tool", arguments)
        val serializer = HarnessSessionEvents.ToolCall.serializer
        assertEquals(call, Json.decodeFromString(serializer, Json.encodeToString(serializer, call)))
    }

    @Test
    fun messageCodecPreservesIdentityProviderAndOpaqueAdapterReplayState() {
        val source = buildJsonObject {
            put("kind", "model")
            put("provider", "custom")
            put("model", "opaque-model")
            put("replayState", buildJsonObject { put("plugin-owned", "unchanged") })
        }
        val message = HarnessLoggedMessage(
            "message-1", HarnessMessageRole.Assistant,
            listOf(buildJsonObject { put("type", "text"); put("text", "answer") }), source,
        )
        val record = HarnessAssistantMessage(1, 2, message)
        val serializer = HarnessSessionEvents.AssistantMessage.serializer
        assertEquals(record, Json.decodeFromString(serializer, Json.encodeToString(serializer, record)))
    }
}
