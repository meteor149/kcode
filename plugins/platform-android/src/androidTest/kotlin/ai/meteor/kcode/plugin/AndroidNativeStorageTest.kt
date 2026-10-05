package ai.meteor.kcode.plugin

import org.cordis.packages.packageFileSha256

import ai.meteor.kcode.history.ConversationHistoryRepository
import ai.meteor.kcode.plugin.api.AndroidPluginHostInputs
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeHistory
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.history.AndroidNativeHistoryPlugin
import ai.meteor.kcode.plugin.settingsstorage.AndroidNativeSettingsPlugin
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.LegacySettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import ai.meteor.kcode.tools.permission.ToolCallApprover
import android.app.Activity
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidNativeStorageTest {
    @Test(timeout = 60_000)
    fun actualArtifactOwnsNativeStorageAndReopensDurableData(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "native-storage-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "storage.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir() = directory
            override fun getDatabasePath(name: String) = File(directory, name)
        }
        val activity = withContext(Dispatchers.Main.immediate) {
            object : Activity() { override fun getApplicationContext(): android.content.Context = isolated }
        }
        val workspace = File(directory, "agent_workspace").apply { mkdirs() }
        check(artifact.setReadOnly())
        val digest = packageFileSha256(artifact)
        lateinit var settings: AppSettingsStore
        lateinit var history: ConversationHistoryRepository
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            profile = KcodePluginProfile(includeDefaults = false),
            hostInputs = AndroidPluginHostInputs(activity),
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(kcodePlugin(
                PluginDescriptor("test.capture", "test", "test", emptySet()),
                plugin<Unit>(name = "capture-storage", inject = dependencies(KcodeSettings.Key, KcodeHistory.Key)) { ctx, _ ->
                    settings = ctx.require(KcodeSettings.Key).store
                    history = ctx.require(KcodeHistory.Key).repository
                }, Unit,
            )),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        val classes = listOf(AndroidNativeSettingsPlugin::class.java, AndroidNativeHistoryPlugin::class.java)

        val specs = classes.mapIndexed { index, entry -> DynamicPluginSpec(
            id = "fixture.storage.$index", version = "native", entryClass = entry.name,
            artifactPath = artifact.path, sha256 = digest, packageName = instrumentation.context.packageName,
        ) }
        try {
            specs.forEach { runtime.pluginManager.install(it) }
            // The coordinator is shared SDK; its borrowed storage implementation stays private.
            assertEquals(AppSettingsStore::class.java.classLoader, settings.javaClass.classLoader)
            val settingsLoader = settings.javaClass.getDeclaredField("delegate").apply { isAccessible = true }
                .get(settings).javaClass.classLoader
            assertNotSame(classes[0].classLoader, settingsLoader)
            assertNotSame(classes[1].classLoader, history.javaClass.classLoader)
            assertEquals(settingsLoader, Class.forName(
                "ai.meteor.kcode.plugin.settingsstorage.DefaultSettingsKt", false, settingsLoader,
            ).classLoader)
            assertEquals(LegacySettings(
                provider = "OpenAI", modelId = "gpt-4o-mini", dashscopeRegion = "china_mainland",
                temperature = 0.7, language = "zh",
                shellExecutionMode = "app", toolPermissionMode = "ask",
            ), settings.load())
            val saved = LegacySettings(language = "en", namespaces = mapOf(
                "private.feature/v2" to (Json.parseToJsonElement(
                    """{"credential":"private-fixture","unknown":[null,{"key.with.dots":""}]}""",
                ) as JsonObject),
            ))
            settings.save(saved)
            history.appendMessage(1, "native", 1, "User", "durable")
            val oldSettings = settings
            val oldHistory = history
            specs.forEach { runtime.pluginManager.setEnabled(it.id, false) }
            assertFailsWith<IllegalStateException> { oldSettings.load() }
            assertFailsWith<IllegalStateException> { oldHistory.loadAll() }
            specs.forEach { runtime.pluginManager.setEnabled(it.id, true) }
            assertNotSame(oldSettings, settings)
            assertNotSame(oldHistory, history)
            assertEquals(saved, settings.load())
            assertEquals("durable", history.loadAll().single().messages.single().content)
            val lastSettings = settings
            val lastHistory = history
            specs.forEach { runtime.pluginManager.uninstall(it.id) }
            assertFailsWith<IllegalStateException> { lastSettings.load() }
            assertFailsWith<IllegalStateException> { lastHistory.loadAll() }
        } finally {
            try { runtime.close() } finally { artifact.setWritable(true); directory.deleteRecursively() }
        }
    }
}
