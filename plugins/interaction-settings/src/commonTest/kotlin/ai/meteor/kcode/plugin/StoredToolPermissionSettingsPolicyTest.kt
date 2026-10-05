package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.LegacySettings
import ai.meteor.kcode.test.toolPermissionMode
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StoredToolPermissionSettingsPolicyTest {
    private fun document(value: String) = Json.parseToJsonElement(value) as JsonObject

    @Test
    fun namespaceOverridesLegacyAndPreservesUnknownData() {
        val policy = StoredToolPermissionSettingsPolicy()
        val future = document("""{"future":[null,{"key.with.dots":false}]}""")
        val stored = LegacySettings(toolPermissionMode = "bypass", namespaces = mapOf(
            "feature.interaction-settings" to future, "disabled.feature" to future,
        ))
        assertEquals(ToolPermissionMode.Ask, policy.resolve(stored))
        val updated = policy.update(stored, ToolPermissionMode.Deny)
        assertEquals(ToolPermissionMode.Deny, policy.resolve(updated))
        assertEquals("bypass", updated.toolPermissionMode)
        assertEquals(future["future"], updated.namespaces["feature.interaction-settings"]!!["future"])
        assertEquals(future, updated.namespaces["disabled.feature"])
        policy.close()
        assertFailsWith<IllegalStateException> { policy.resolve(updated) }
        assertFailsWith<IllegalStateException> { policy.update(updated, ToolPermissionMode.Bypass) }
    }

    @Test
    fun strictTypesAndUnknownCodesDoNotRewriteStoredData() {
        val policy = StoredToolPermissionSettingsPolicy()
        assertEquals(ToolPermissionMode.Bypass, policy.resolve(LegacySettings(toolPermissionMode = "bypass")))
        val unknown = document("""{"mode":"future.mode"}""")
        assertEquals(ToolPermissionMode.Ask, policy.resolve(StoredAppSettings(namespaces = mapOf("feature.interaction-settings" to unknown))))
        assertEquals("future.mode", unknown["mode"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        for (value in listOf("""{"mode":null}""", """{"mode":3}""", """{"mode":false}""")) {
            assertFailsWith<IllegalArgumentException> {
                policy.resolve(StoredAppSettings(namespaces = mapOf("feature.interaction-settings" to document(value))))
            }
        }
    }

    @Test
    fun callerCallbacksHaveNoImplicitConfigurationControls() {
        assertNull(InteractionPolicy(approver = ToolCallApprover { true }).settings)
    }
}
