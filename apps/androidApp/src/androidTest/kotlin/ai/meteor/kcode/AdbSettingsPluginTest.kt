package ai.meteor.kcode

import ai.meteor.kcode.plugin.KcodePluginRuntime
import ai.meteor.kcode.plugin.KcodePluginRuntimeConfig
import ai.meteor.kcode.plugin.kcodePlugin
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.tools.permission.ToolCallApprover
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlin.test.assertFalse
import org.cordis.plugin
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdbSettingsPluginTest {
    @Test(timeout = 60_000)
    fun actionBroadcastUpdatesModelAndKeyWithoutAnExplicitReceiver(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as KcodeApplication
        val store = MemorySettings("preserved-endpoint")
        store.value = store.value.copy(modelApiKeys = mapOf(ModelProvider.OpenAI.name to "preserved-key"))
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            settingsStore = store,
        ))
        application.attachContent(runtime)
        suspend fun configure(target: String): String = withContext(Dispatchers.IO) {
            val command = "am broadcast --include-stopped-packages -a ai.meteor.kcode.action.CONFIGURE_SETTINGS " +
                "$target --es model-provider deepseek --es model deepseek-v4-pro --es model-api-key broadcast-fixture-key"
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .bufferedReader().use { it.readText() }
        }
        try {
            for (target in listOf("-p ${context.packageName}", "-n ${context.packageName}/.AdbSettingsReceiver")) {
                val result = configure(target)
                assertTrue(result.contains("result=-1"), result)
                assertTrue(result.contains("model-api-key"), result)
                assertFalse(result.contains("broadcast-fixture-key"))
                assertEquals(ModelProvider.DeepSeek.name, store.value.provider)
                assertEquals("deepseek-v4-pro", store.value.modelId)
                assertEquals("broadcast-fixture-key", store.value.modelApiKeys[ModelProvider.DeepSeek.name])
                assertEquals("preserved-key", store.value.modelApiKeys[ModelProvider.OpenAI.name])
                assertEquals("preserved-endpoint", store.value.modelEndpoint)
            }
            assertEquals(2, store.saved)
        } finally {
            application.detachContent(runtime)
            runtime.close()
        }
    }

    @Test(timeout = 60_000)
    fun shellBroadcastUsesCurrentSettingsProviderAndRejectsWithdrawnCommands(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as KcodeApplication
        val first = MemorySettings("first")
        val second = MemorySettings("second")
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            settingsStore = first,
        ))
        application.attachContent(runtime)
        suspend fun configure(provider: String): String = withContext(Dispatchers.IO) {
            val command = "am broadcast --include-stopped-packages -a ai.meteor.kcode.action.CONFIGURE_SETTINGS " +
                "-n ${context.packageName}/.AdbSettingsReceiver --es search-provider $provider"
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .bufferedReader().use { it.readText() }
        }
        try {
            assertTrue(configure("exa").contains("result=-1"))
            assertEquals(1, first.saved)
            assertEquals("exa", first.value.webSearchProvider)
            assertEquals("first", first.value.modelEndpoint)
            runtime.replacePlugin(kcodePlugin(
                PluginDescriptor("provider.settings.platform", "test", "test", setOf("settings")),
                plugin<MemorySettings>(name = "fixture-settings-provider") { ctx, store -> KcodeSettings(ctx, store) }, second,
            ))
            assertTrue(configure("bright_data").contains("result=-1"))
            assertEquals(1, first.saved)
            assertEquals(1, second.saved)
            assertEquals("second", second.value.modelEndpoint)
            runtime.pluginManager.setEnabled("consumer.settings.commands", false)
            assertTrue(configure("google").contains("result=0"))
            assertEquals(1, second.saved)
            runtime.pluginManager.setEnabled("consumer.settings.commands", true)
            assertTrue(configure("google").contains("result=-1"))
            assertEquals(2, second.saved)
            runtime.pluginManager.setEnabled("provider.settings.platform", false)
            assertTrue(configure("exa").contains("result=0"))
            assertEquals(2, second.saved)
        } finally {
            application.detachContent(runtime)
            runtime.close()
        }
        assertTrue(configure("google").contains("result=0"))
        assertEquals(2, second.saved)
    }

    @Test(timeout = 60_000)
    fun updatesAwaitRuntimePublicationAndOldDetachCannotRemoveTheNewHost(): Unit = runBlocking {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KcodeApplication
        val firstStore = MemorySettings("first")
        val secondStore = MemorySettings("second")
        suspend fun runtime(store: AppSettingsStore) = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }), settingsStore = store,
        ))
        val first = runtime(firstStore)
        val second = runtime(secondStore)
        try {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                application.updateSettings(SettingsUpdate(searchProvider = "exa"))
            }
            assertFalse(waiting.isCompleted)
            assertEquals(0, firstStore.saved)
            application.attachContent(first)
            assertEquals("first", waiting.await().settings.modelEndpoint)
            application.attachContent(second)
            application.detachContent(first)
            assertEquals("second", application.updateSettings(
                SettingsUpdate(searchProvider = "exa"),
            ).settings.modelEndpoint)
            assertEquals(1, firstStore.saved)
            assertEquals(1, secondStore.saved)
        } finally {
            application.detachContent(first)
            application.detachContent(second)
            first.close()
            second.close()
        }
    }

    private class MemorySettings(endpoint: String) : AppSettingsStore {
        override val protection = SettingsProtection.Transient
        var value = StoredAppSettings(modelEndpoint = endpoint)
        var saved = 0
        override suspend fun load() = value
        override suspend fun save(settings: StoredAppSettings) { saved++; value = settings }
    }
}
