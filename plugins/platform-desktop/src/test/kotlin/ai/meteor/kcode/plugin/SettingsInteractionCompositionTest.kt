package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.settingsstorage.SettingsProviderPlugin

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeInteraction
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ai.meteor.kcode.plugin.api.PluginState
import org.cordis.dependencies
import org.cordis.plugin

class SettingsInteractionCompositionTest {
    @Test
    fun settingsReplacementChangesPermissionPolicyAndWithdrawalRevokesBothCallbacks() = runTest {
        var approvals = 0
        var obsoleteModeQueries = 0
        lateinit var policy: InteractionPolicy
        val first = MemorySettings(ToolPermissionMode.Deny)
        val second = MemorySettings(ToolPermissionMode.Bypass)
        val capture = capture { policy = it }
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ obsoleteModeQueries++; error("obsolete host mode") }, ToolCallApprover { approvals++; true }),
            settingsBackedInteraction = true, settingsStore = first, featurePlugins = listOf(capture),
        ))
        try {
            assertEquals(ToolPermissionMode.Deny, policy.permissionModeProvider())
            val previous = policy
            runtime.replacePlugin(kcodePlugin(descriptor("provider.settings.platform"), SettingsProviderPlugin, second))
            assertFailsWith<IllegalStateException> { previous.permissionModeProvider() }
            assertFailsWith<IllegalStateException> { previous.approver.approve(request()) }
            assertEquals(ToolPermissionMode.Bypass, policy.permissionModeProvider())
            second.save(StoredAppSettings(toolPermissionMode = ToolPermissionMode.Ask.code))
            assertEquals(ToolPermissionMode.Ask, policy.permissionModeProvider())
            assertTrue(policy.approver.approve(request()))
            assertEquals(1, approvals)
            val replacement = policy
            runtime.pluginManager.setEnabled("provider.settings.platform", false)
            assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == "provider.interaction.platform" }.state)
            assertFailsWith<IllegalStateException> { replacement.permissionModeProvider() }
            assertFailsWith<IllegalStateException> { replacement.approver.approve(request()) }
            assertEquals(0, obsoleteModeQueries)
            runtime.pluginManager.setEnabled("provider.settings.platform", true)
            assertEquals(ToolPermissionMode.Ask, policy.permissionModeProvider())
        } finally { runtime.close() }
    }

    @Test
    fun settingsWithdrawalWaitsForPermissionQueryCleanupBeforeReturning() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        lateinit var policy: InteractionPolicy
        val store = object : AppSettingsStore {
            override val protection = SettingsProtection.Transient
            override suspend fun load(): StoredAppSettings {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
            override suspend fun save(settings: StoredAppSettings) = Unit
        }
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            settingsBackedInteraction = true, settingsStore = store, featurePlugins = listOf(capture { policy = it }),
        ))
        try {
            val previous = policy
            val querying = backgroundScope.async { previous.permissionModeProvider() }
            entered.await()
            val disabling = async { runtime.pluginManager.setEnabled("provider.settings.platform", false) }
            cleaning.await()
            assertFalse(disabling.isCompleted)
            release.complete(Unit)
            disabling.await()
            querying.join()
            assertTrue(querying.isCancelled)
            assertFailsWith<IllegalStateException> { previous.permissionModeProvider() }
        } finally { release.complete(Unit); runtime.close() }
    }

    private fun capture(bind: (InteractionPolicy) -> Unit) = kcodePlugin(descriptor("test.permission-policy"), plugin<Unit>(
        name = "capture-policy", inject = dependencies(KcodeInteraction.Key),
    ) { ctx, _ -> bind(ctx.require(KcodeInteraction.Key).policy) }, Unit)

    private fun descriptor(id: String) = PluginDescriptor(id, "test", "test", emptySet())
    private fun request() = ai.meteor.kcode.tools.permission.ToolApprovalRequest("permission-test", "fixture", "approval")

    private class MemorySettings(mode: ToolPermissionMode) : AppSettingsStore {
        override val protection = SettingsProtection.Transient
        private var value = StoredAppSettings(toolPermissionMode = mode.code)
        override suspend fun load() = value
        override suspend fun save(settings: StoredAppSettings) { value = settings }
    }
}
