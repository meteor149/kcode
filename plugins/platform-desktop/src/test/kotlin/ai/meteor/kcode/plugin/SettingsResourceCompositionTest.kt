package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.SettingsStoreFactory
import ai.meteor.kcode.plugin.api.SettingsStoreResource
import ai.meteor.kcode.plugin.settingsstorage.FactorySettingsProviderPlugin
import ai.meteor.kcode.plugin.settingsstorage.desktopSettingsStoreFactory
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.LegacySettings
import ai.meteor.kcode.test.copy
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.nio.file.Files
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
import org.cordis.dependencies
import org.cordis.plugin

class SettingsResourceCompositionTest {
    @Test
    fun disabledSettingsProviderAllocatesNothingUntilEnabled() = runTest {
        var opened = 0
        var closed = 0
        val runtime = KcodePluginRuntime.create(config(SettingsStoreFactory {
            opened++
            SettingsStoreResource(MemorySettings()) { closed++ }
        }).copy(profile = KcodePluginProfile(disabled = setOf("provider.settings.platform"))))
        try {
            assertEquals(0, opened)
            runtime.pluginManager.setEnabled("provider.settings.platform", true)
            assertEquals(1, opened)
            runtime.pluginManager.setEnabled("provider.settings.platform", false)
            assertEquals(1, closed)
        } finally { runtime.close() }
    }


    @Test
    fun dataStoreReleasesItsFileAndRestoresSettingsOnRemountAndFailedReplacement() = runTest {
        val directory = Files.createTempDirectory("kcode-settings-owned-")
        val factory = desktopSettingsStoreFactory(directory.resolve("settings.preferences_pb"))
        var opened = 0
        var closed = 0
        lateinit var current: AppSettingsStore
        lateinit var raw: AppSettingsStore
        val owned = SettingsStoreFactory {
            val resource = factory.create()
            raw = resource.store
            opened++
            SettingsStoreResource(resource.store) { resource.close(); closed++ }
        }
        val runtime = KcodePluginRuntime.create(config(owned, capture { current = it }))
        val settings = LegacySettings(provider = "custom", modelApiKeys = mapOf("custom" to "fixture"), toolPermissionMode = "deny")
        try {
            current.save(settings)
            val previous = current
            val previousRaw = raw
            runtime.pluginManager.setEnabled("provider.settings.platform", false)
            assertEquals(1, closed)
            assertFailsWith<IllegalStateException> { previous.load() }
            assertFailsWith<IllegalStateException> { previous.protection }
            assertFailsWith<IllegalStateException> { previousRaw.load() }
            runtime.pluginManager.setEnabled("provider.settings.platform", true)
            assertEquals(settings, current.load())
            assertFailsWith<IllegalArgumentException> {
                runtime.replacePlugin(kcodePlugin(descriptor("provider.settings.platform"), FactorySettingsProviderPlugin,
                    SettingsStoreFactory { throw IllegalArgumentException("allocation failed") }))
            }
            assertEquals(3, opened)
            assertEquals(2, closed)
            assertEquals(settings, current.load())
        } finally {
            runtime.close()
            assertEquals(opened, closed)
            assertTrue(directory.toFile().deleteRecursively())
        }
    }

    @Test
    fun cancelledAssemblyClosesAllocatedSettingsBeforeReturning() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var closed = false
        val creating = backgroundScope.async {
            KcodePluginRuntime.create(config(SettingsStoreFactory {
                entered.complete(Unit); release.await()
                SettingsStoreResource(MemorySettings()) { closed = true }
            }))
        }
        entered.await()
        creating.cancel()
        release.complete(Unit)
        creating.join()
        assertTrue(creating.isCancelled)
        assertTrue(closed)
    }

    @Test
    fun nativeResourceReleaseWaitsForCancelledWriteCleanup() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var closed = false
        lateinit var current: AppSettingsStore
        val store = object : MemorySettings() {
            override suspend fun save(settings: StoredAppSettings) {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
                }
            }
        }
        val runtime = KcodePluginRuntime.create(config(SettingsStoreFactory {
            SettingsStoreResource(store) { closed = true }
        }, capture { current = it }))
        try {
            val writing = backgroundScope.async { current.save(StoredAppSettings()) }
            entered.await()
            val disabling = async { runtime.pluginManager.setEnabled("provider.settings.platform", false) }
            cleaning.await()
            assertFalse(closed)
            assertFalse(disabling.isCompleted)
            release.complete(Unit)
            disabling.await()
            writing.join()
            assertTrue(closed)
            assertTrue(writing.isCancelled)
        } finally { release.complete(Unit); runtime.close() }
    }

    private fun config(factory: SettingsStoreFactory, capture: KcodePluginMount? = null) = KcodePluginRuntimeConfig(
        interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
        settingsStoreFactory = factory, featurePlugins = listOfNotNull(capture),
    )
    private fun capture(bind: (AppSettingsStore) -> Unit) = kcodePlugin(descriptor("test.settings-resource"), plugin<Unit>(
        name = "capture-settings", inject = dependencies(KcodeSettings.Key),
    ) { ctx, _ -> bind(ctx.require(KcodeSettings.Key).store) }, Unit)
    private fun descriptor(id: String) = PluginDescriptor(id, "test", "test", emptySet())
    private open class MemorySettings : AppSettingsStore {
        override val protection = SettingsProtection.Transient
        override suspend fun load() = StoredAppSettings()
        override suspend fun save(settings: StoredAppSettings) = Unit
    }
}
