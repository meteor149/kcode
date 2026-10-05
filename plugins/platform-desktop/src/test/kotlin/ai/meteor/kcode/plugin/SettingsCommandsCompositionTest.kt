package ai.meteor.kcode.plugin

import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeSettingsCommands
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.SettingsCommandHandler
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.copy
import ai.meteor.kcode.test.language
import ai.meteor.kcode.settings.SettingsPatch
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.cordis.dependencies
import org.cordis.plugin

class SettingsCommandsCompositionTest {
    @Test
    fun commandAndUiPatchShareThePublishedStoreTransaction() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var persisted = StoredAppSettings()
        var saves = 0
        val backend = object : AppSettingsStore {
            override val protection = SettingsProtection.Transient
            override suspend fun load() = persisted
            override suspend fun save(settings: StoredAppSettings) {
                if (++saves == 1) { entered.complete(Unit); release.await() }
                persisted = settings
            }
        }
        lateinit var store: AppSettingsStore
        val capture = kcodePlugin(PluginDescriptor("test.settings-store", "test", "test", emptySet()), plugin<Unit>(
            name = "capture-settings-store", inject = dependencies(KcodeSettings.Key),
        ) { ctx, _ -> store = ctx.require(KcodeSettings.Key).store }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            settingsStore = backend, featurePlugins = listOf(capture),
        ))
        try {
            val draft = store.load()
            val patch = SettingsPatch.between(draft, draft.copy(language = "en"))
            val command = async { runtime.updateSettings(SettingsUpdate(mapOf("search-provider" to "exa"))) }
            entered.await()
            var uiEntered = false
            val ui = async(start = CoroutineStart.UNDISPATCHED) {
                store.transaction { uiEntered = true; commit(patch.apply(current)) }
            }
            assertFalse(uiEntered)
            release.complete(Unit)
            command.await(); ui.await()
            assertEquals(kotlinx.serialization.json.JsonPrimitive("exa"), persisted.namespaces["feature.web-search"]?.get("provider"))
            assertEquals("en", persisted.language)
            assertEquals(persisted, store.load())
        } finally { release.complete(Unit); runtime.close() }
        assertFailsWith<IllegalStateException> { store.transaction { commit(current) } }
    }

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
            runtime.updateSettings(SettingsUpdate(mapOf("search-provider" to "exa")))
            attemptReentry = false
            runtime.updateSettings(SettingsUpdate(mapOf("search-provider" to "google")))
            assertEquals(2, saved)
            runtime.pluginManager.setEnabled("consumer.settings.commands", false)
            assertFails { runtime.updateSettings(SettingsUpdate(mapOf("search-provider" to "exa"))) }
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
            val writing = backgroundScope.async { runtime.updateSettings(SettingsUpdate(mapOf("search-provider" to "exa"))) }
            entered.await()
            val disabling = async { runtime.pluginManager.setEnabled("consumer.settings.commands", false) }
            cleaning.await()
            assertFalse(disabling.isCompleted)
            release.complete(Unit)
            disabling.await()
            writing.join()
            assertTrue(writing.isCancelled)
            assertFailsWith<IllegalStateException> { old.apply(SettingsUpdate(mapOf("search-provider" to "google")), ModelCatalogSnapshot()) }
            assertFails { runtime.updateSettings(SettingsUpdate(mapOf("search-provider" to "google"))) }
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
            assertFailsWith<IllegalArgumentException> { runtime.updateSettings(SettingsUpdate(mapOf("search-provider" to "unsupported"))) }
            assertEquals(0, saved)
            assertFailsWith<IllegalArgumentException> { runtime.updateSettings(SettingsUpdate(mapOf("search-provider" to "exa"))) }
            assertEquals(1, saved)
        } finally { runtime.close() }
    }
}
