package ai.meteor.kcode.plugin

import ai.meteor.kcode.AgentShellExecutor
import ai.meteor.kcode.plugin.api.KcodeShellMode
import ai.meteor.kcode.plugin.api.ShellModePolicy
import ai.meteor.kcode.plugin.api.PluginOperationOwner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.cordis.Disposable
import ai.meteor.kcode.plugin.executionsettings.SettingsShellModePlugin
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.tools.permission.ToolCallApprover
import ai.meteor.kcode.plugin.api.KcodeShell
import ai.meteor.kcode.plugin.api.KcodeUbuntuShell
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.plugin.api.ShellBackend
import ai.meteor.kcode.plugin.api.ShellRequest
import ai.meteor.kcode.plugin.provider.settingsShellProviderPlugin
import ai.meteor.kcode.plugin.provider.settingsUbuntuShellProviderPlugin
import ai.meteor.kcode.plugin.settingsstorage.SettingsProviderPlugin
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.ShellExecutionMode
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.LegacySettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.cordis.dependencies
import org.cordis.plugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsShellCompositionTest {
    @Test
    fun bothWorldsReadCommittedSettingsAndRebindWithTheProvider() = runTest {
        for (ubuntu in listOf(false, true)) {
            lateinit var backend: ShellBackend
            lateinit var readMode: suspend () -> ShellExecutionMode
            var mounts = 0
            val factory: (suspend () -> ShellExecutionMode) -> AgentShellExecutor = { mode ->
                mounts++
                readMode = mode
                object : AgentShellExecutor {
                    override suspend fun execute(command: String, workingDirectory: String?) =
                        AgentShellExecutor.ExecutionResult("${mode().code}:$command:$workingDirectory", 0)
                }
            }
            val provider = if (ubuntu) settingsUbuntuShellProviderPlugin(factory) else settingsShellProviderPlugin(factory)
            val first = MemorySettings(ShellExecutionMode.App)
            val second = MemorySettings(ShellExecutionMode.Adb)
            val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
                interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
                settingsStore = first, featurePlugins = listOf(modeProvider(), provider, capture(ubuntu) { backend = it }),
            ))
            try {
                assertEquals("app:one:/workspace", backend.run(ShellRequest("one", "/workspace")).output)
                first.save(LegacySettings(shellExecutionMode = "root", namespaces = mapOf(
                    "feature.execution-settings" to (Json.parseToJsonElement("""{"mode":"adb"}""") as JsonObject),
                )))
                assertEquals("adb:namespace:null", backend.run(ShellRequest("namespace")).output)
                first.save(LegacySettings(shellExecutionMode = ShellExecutionMode.Root.code))
                assertEquals(ShellExecutionMode.Root, readMode())
                val previous = backend
                val previousReader = readMode
                runtime.replacePlugin(kcodePlugin(descriptor("provider.settings.platform"), SettingsProviderPlugin, second))
                assertFailsWith<IllegalStateException> { previous.run(ShellRequest("stale")) }
                assertFailsWith<IllegalStateException> { previousReader() }
                assertEquals("adb:two:null", backend.run(ShellRequest("two")).output)
                val beforeDisable = backend
                runtime.pluginManager.setEnabled("provider.settings.platform", false)
                assertEquals(PluginState.Pending, runtime.diagnostics().plugins.first { it.id == provider.descriptor.id }.state)
                assertFailsWith<IllegalStateException> { beforeDisable.run(ShellRequest("stale")) }
                runtime.pluginManager.setEnabled("provider.settings.platform", true)
                assertEquals("adb:three:null", backend.run(ShellRequest("three")).output)
                assertEquals(3, mounts)
            } finally { runtime.close() }
        }
    }

    @Test
    fun settingsWithdrawalWaitsForRunningShellCleanupInBothWorlds() = runTest {
        for (ubuntu in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            lateinit var backend: ShellBackend
            val factory: (suspend () -> ShellExecutionMode) -> AgentShellExecutor = { mode ->
                object : AgentShellExecutor {
                    override suspend fun execute(command: String, workingDirectory: String?): AgentShellExecutor.ExecutionResult {
                        assertEquals(ShellExecutionMode.App, mode())
                        entered.complete(Unit)
                        try { awaitCancellation() } finally {
                            withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                        }
                    }
                }
            }
            val provider = if (ubuntu) settingsUbuntuShellProviderPlugin(factory) else settingsShellProviderPlugin(factory)
            val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
                interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
                settingsStore = MemorySettings(ShellExecutionMode.App),
                featurePlugins = listOf(modeProvider(), provider, capture(ubuntu) { backend = it }),
            ))
            try {
                val previous = backend
                val running = backgroundScope.async { previous.run(ShellRequest("hold")) }
                entered.await()
                val disabling = async { runtime.pluginManager.setEnabled("provider.settings.platform", false) }
                cleaning.await()
                assertFalse(disabling.isCompleted)
                assertFailsWith<IllegalStateException> { previous.run(ShellRequest("stale")) }
                release.complete(Unit)
                disabling.await()
                running.join()
                assertTrue(running.isCancelled)
            } finally { release.complete(Unit); runtime.close() }
        }
    }

    @Test
    fun shellFactoriesFollowReplaceableCallerPoliciesWithoutSettingsStorage() = runTest {
        for (ubuntu in listOf(false, true)) {
            lateinit var backend: ShellBackend
            var mounts = 0
            val factory: (suspend () -> ShellExecutionMode) -> AgentShellExecutor = { readMode ->
                mounts++
                object : AgentShellExecutor {
                    override suspend fun execute(command: String, workingDirectory: String?) =
                        AgentShellExecutor.ExecutionResult(readMode().code, 0)
                }
            }
            val provider = if (ubuntu) settingsUbuntuShellProviderPlugin(factory) else settingsShellProviderPlugin(factory)
            fun callerPolicy(mode: ShellExecutionMode) = kcodePlugin(
                descriptor("test.caller-mode"), plugin<Unit>(name = "caller-mode") { ctx, _ ->
                    val owner = PluginOperationOwner("caller mode")
                    collect(Disposable { owner.close() })
                    KcodeShellMode(ctx, ShellModePolicy { owner.run { mode } })
                }, Unit,
            )
            val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
                interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
                profile = KcodePluginProfile(includeDefaults = false),
                featurePlugins = listOf(callerPolicy(ShellExecutionMode.Root), provider, capture(ubuntu) { backend = it }),
            ))
            try {
                assertTrue(runtime.diagnostics().plugins.none { it.id == "provider.settings.platform" })
                assertEquals("root", backend.run(ShellRequest("mode")).output)
                val original = backend
                runtime.replacePlugin(callerPolicy(ShellExecutionMode.Adb))
                assertFailsWith<IllegalStateException> { original.run(ShellRequest("stale")) }
                assertEquals("adb", backend.run(ShellRequest("mode")).output)
                val replaced = backend
                runtime.pluginManager.setEnabled("test.caller-mode", false)
                assertEquals(PluginState.Pending, runtime.diagnostics().plugins.single { it.id == provider.descriptor.id }.state)
                assertFailsWith<IllegalStateException> { replaced.run(ShellRequest("stale")) }
                runtime.pluginManager.setEnabled("test.caller-mode", true)
                assertEquals("adb", backend.run(ShellRequest("mode")).output)
                assertEquals(3, mounts)
            } finally { runtime.close() }
        }
    }

    private fun modeProvider() = kcodePlugin(
        descriptor("test.settings-mode"), SettingsShellModePlugin, Unit,
    )

    private fun capture(ubuntu: Boolean, accept: (ShellBackend) -> Unit) = kcodePlugin(
        descriptor("test.capture-shell"), plugin<Unit>(
            name = "capture-settings-shell",
            inject = if (ubuntu) dependencies(KcodeUbuntuShell.Key) else dependencies(KcodeShell.Key),
        ) { ctx, _ -> accept(if (ubuntu) ctx.require(KcodeUbuntuShell.Key).executor else ctx.require(KcodeShell.Key).executor) }, Unit,
    )
    private fun descriptor(id: String) = PluginDescriptor(id, "test", "test", emptySet())
    private class MemorySettings(mode: ShellExecutionMode) : AppSettingsStore {
        override val protection = SettingsProtection.Transient
        private var value = LegacySettings(shellExecutionMode = mode.code)
        override suspend fun load() = value
        override suspend fun save(settings: StoredAppSettings) { value = settings }
    }
}
