package ai.meteor.kcode.plugin

import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeSettingsCommands
import ai.meteor.kcode.plugin.api.KcodeSettings
import ai.meteor.kcode.plugin.api.KcodeSearchSettings
import ai.meteor.kcode.plugin.api.SettingsCommandHandler
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.settingscommands.SettingsCommandsPlugin
import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.settings.AppliedSettingsUpdate
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.settings.ToolPermissionMode
import ai.meteor.kcode.tools.permission.ToolCallApprover
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.cordis.Context
import org.cordis.EffectScope
import org.cordis.Plugin
import org.cordis.dependencies
import org.cordis.plugin
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSettingsCommandsApkTest {
    @Test(timeout = 60_000)
    fun isolatedSettingsConsumerUsesSharedRequestsAndResultsAndWithdrawsItsHandler(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "settings-command-apk-${System.nanoTime()}").apply { mkdirs() }
        val apk = File(directory, "settings-command.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(apk)
        check(apk.setReadOnly())
        var saved = 0
        var stored = StoredAppSettings(modelEndpoint = "fixture")
        val store = object : AppSettingsStore {
            override val protection = SettingsProtection.Transient
            override suspend fun load() = stored
            override suspend fun save(settings: StoredAppSettings) { saved++; stored = settings }
        }
        lateinit var handler: SettingsCommandHandler
        val capture = kcodePlugin(PluginDescriptor("test.settings-commands", "test", "test", emptySet()), plugin<Unit>(
            name = "capture-settings-commands", inject = dependencies(KcodeSettingsCommands.Key),
        ) { ctx, _ -> handler = ctx.require(KcodeSettingsCommands.Key).handler }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy({ ToolPermissionMode.Bypass }, ToolCallApprover { true }),
            settingsStore = store,
            profile = KcodePluginProfile(disabled = setOf("consumer.settings.commands")),
            featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        try {
            assertFails { runtime.updateSettings(SettingsUpdate(searchProvider = "exa")) }
            assertEquals(0, saved)
            runtime.pluginManager.install(DynamicPluginSpec(
                id = "fixture.settings-commands", version = "test", entryClass = AndroidFixtureSettingsCommands::class.java.name,
                artifactPath = apk.path,
                sha256 = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) },
                packageName = instrumentation.context.packageName,
            ))
            val old = handler
            assertTrue(old.javaClass.classLoader !== SettingsCommandsPlugin::class.java.classLoader)
            val result = runtime.updateSettings(SettingsUpdate(searchProvider = "exa"))
            assertTrue(result.javaClass === AppliedSettingsUpdate::class.java)
            assertTrue(result.settings.javaClass === StoredAppSettings::class.java)
            assertEquals("exa", stored.webSearchProvider)
            assertEquals("fixture", result.settings.modelEndpoint)
            assertEquals(1, saved)
            runtime.pluginManager.setEnabled("fixture.settings-commands", false)
            assertFailsWith<IllegalStateException> { old.apply(SettingsUpdate(searchProvider = "google"), ModelCatalogSnapshot()) }
            assertFails { runtime.updateSettings(SettingsUpdate(searchProvider = "google")) }
            assertEquals(1, saved)
            runtime.pluginManager.setEnabled("fixture.settings-commands", true)
            assertNotSame(old, handler)
            runtime.updateSettings(SettingsUpdate(searchProvider = "google"))
            assertEquals(2, saved)
            runtime.pluginManager.uninstall("fixture.settings-commands")
            assertFails { runtime.updateSettings(SettingsUpdate(searchProvider = "exa")) }
            assertEquals(2, saved)
        } finally {
            runtime.close()
            apk.setWritable(true)
            directory.deleteRecursively()
        }
    }
}

class AndroidFixtureSettingsCommands : Plugin<Unit> {
    override val name = "fixture-settings-commands"
    override val inject = dependencies(KcodeSettings.Key, KcodeSearchSettings.Key)
    override suspend fun apply(ctx: Context, config: Unit, effect: EffectScope) {
        SettingsCommandsPlugin.apply(ctx, config, effect)
    }
}
