package ai.meteor.kcode.plugin.executionsettings

import ai.meteor.kcode.plugin.api.ShellModePolicy
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.LegacySettings
import ai.meteor.kcode.test.shellExecutionMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StoredShellModeSettingsPolicyTest {
    private fun document(value: String) = Json.parseToJsonElement(value) as JsonObject

    @Test
    fun namespaceOverridesLegacyAndUpdatesPreserveUnknownData() {
        val policy = StoredShellModeSettingsPolicy()
        val future = document("""{"future":[null,{"key.with.dots":false}]}""")
        val stored = LegacySettings(shellExecutionMode = "root", namespaces = mapOf(
            "feature.execution-settings" to future,
            "disabled.feature" to future,
        ))
        assertEquals(ShellExecutionMode.App, policy.resolve(stored))
        val saved = policy.update(stored, ShellExecutionMode.Adb)
        assertEquals("root", saved.shellExecutionMode)
        assertEquals(ShellExecutionMode.Adb, policy.resolve(saved))
        assertEquals(future["future"], saved.namespaces["feature.execution-settings"]!!["future"])
        assertEquals(future, saved.namespaces["disabled.feature"])
        policy.close()
        assertFailsWith<IllegalStateException> { policy.resolve(saved) }
        assertFailsWith<IllegalStateException> { policy.update(saved, ShellExecutionMode.Root) }
    }

    @Test
    fun legacyFallbackAndStrictNamespaceTypesDoNotRewriteUnknownModes() {
        val policy = StoredShellModeSettingsPolicy()
        assertEquals(ShellExecutionMode.Root, policy.resolve(LegacySettings(shellExecutionMode = "root")))
        val unknown = StoredAppSettings(namespaces = mapOf("feature.execution-settings" to document("""{"mode":"future.mode"}""")))
        assertEquals(ShellExecutionMode.App, policy.resolve(unknown))
        assertEquals(document("""{"mode":"future.mode"}"""), unknown.namespaces["feature.execution-settings"])
        for (value in listOf("""{"mode":null}""", """{"mode":7}""")) {
            assertFailsWith<IllegalArgumentException> { policy.resolve(StoredAppSettings(namespaces =
                mapOf("feature.execution-settings" to document(value)))) }
        }
        policy.close()
    }

    @Test
    fun callbackOnlyPoliciesExposeNoSettingsCapability() {
        assertNull(ShellModePolicy { ShellExecutionMode.App }.settings)
    }
}
