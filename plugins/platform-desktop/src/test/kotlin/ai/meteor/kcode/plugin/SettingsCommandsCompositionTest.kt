package ai.meteor.kcode.plugin

import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeSettingsCommands
import ai.meteor.kcode.plugin.api.SettingsCommandHandler
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.cordis.dependencies
import org.cordis.plugin

class SettingsCommandsCompositionTest {
    @Test
    fun providerCallbackCannotMutateOrCloseItsOwnRuntime() = runTest {
        lateinit var runtime: KcodePluginRuntime
        var attemptReentry = true
        var saved = 0
        val store = object : AppSettingsStore {
            override val protection = SettingsProtection.Transient
            override suspend fun load() = StoredAppSettings()
            override suspend fun save(settings: StoredAppSettings) {
                if (attemptReentry) {
                    withContext(NonCancellable) {
                        assertFailsWith<IllegalStateException> {
                            runtime.pluginManager.setEnabled("consumer.settings.commands", false)
                        }
                        assertFailsWith<IllegalStateException> { runtime.close() }
                    }
                }
                saved++
            }
        }
        runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            settingsStore = store,
        ))
        try {
            runtime.updateSettings(SettingsUpdate(searchProvider = "exa"))
            attemptReentry = false
            runtime.updateSettings(SettingsUpdate(searchProvider = "google"))
            assertEquals(2, saved)
            runtime.pluginManager.setEnabled("consumer.settings.commands", false)
            assertFails { runtime.updateSettings(SettingsUpdate(searchProvider = "exa")) }
        } finally { runtime.close() }
    }

    @Test
    fun withdrawnCommandsCancelTheWholeUpdateAndWaitForCleanup() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = object : AppSettingsStore {
            override val protection = SettingsProtection.Transient
            override suspend fun load() = StoredAppSettings()
            override suspend fun save(settings: StoredAppSettings) {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
        }
        lateinit var handler: SettingsCommandHandler
        val capture = kcodePlugin(PluginDescriptor("test.settings-commands", "test", "test", emptySet()), plugin<Unit>(
            name = "capture-settings-commands", inject = dependencies(KcodeSettingsCommands.Key),
        ) { ctx, _ -> handler = ctx.require(KcodeSettingsCommands.Key).handler }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            settingsStore = store, featurePlugins = listOf(capture),
        ))
        val old = handler
        try {
            val writing = backgroundScope.async { runtime.updateSettings(SettingsUpdate(searchProvider = "exa")) }
            entered.await()
            val disabling = async { runtime.pluginManager.setEnabled("consumer.settings.commands", false) }
            cleaning.await()
            assertFalse(disabling.isCompleted)
            release.complete(Unit)
            disabling.await()
            writing.join()
            assertTrue(writing.isCancelled)
            assertFailsWith<IllegalStateException> { old.apply(SettingsUpdate(searchProvider = "google"), ModelCatalogSnapshot()) }
            assertFails { runtime.updateSettings(SettingsUpdate(searchProvider = "google")) }
        } finally { release.complete(Unit); runtime.close() }
    }

    @Test
    fun saveFailureReportsNoCommittedResultAndValidationDoesNotWrite() = runTest {
        var saved = 0
        val store = object : AppSettingsStore {
            override val protection = SettingsProtection.Transient
            override suspend fun load() = StoredAppSettings()
            override suspend fun save(settings: StoredAppSettings) { saved++; throw IllegalArgumentException("store failure") }
        }
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }), settingsStore = store,
        ))
        try {
            assertFailsWith<IllegalArgumentException> { runtime.updateSettings(SettingsUpdate(searchProvider = "unsupported")) }
            assertEquals(0, saved)
            assertFailsWith<IllegalArgumentException> { runtime.updateSettings(SettingsUpdate(searchProvider = "exa")) }
            assertEquals(1, saved)
        } finally { runtime.close() }
    }
}
