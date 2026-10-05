package ai.meteor.kcode.plugin

import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.settings.ModelSettingsPolicy
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.LegacySettings
import ai.meteor.kcode.test.copy
import ai.meteor.kcode.test.provider
import ai.meteor.kcode.test.temperature
import ai.meteor.kcode.plugin.modelsettings.ModelSettingsProviderPlugin
import ai.meteor.kcode.plugin.api.KcodeModelSettings
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.api.PluginState
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import org.cordis.dependencies
import org.cordis.plugin
import java.nio.file.Files
import kotlin.test.Test

class ModelSettingsPrivateLoadingTest {
    @Test
    fun privatePolicyConfigurationWithdrawalAndRestartRebindTheApplication(): Unit = runBlocking {
        val directory = Files.createTempDirectory("model-settings-private").toFile()
        val artifact = File(directory, "model-settings.jar")
        File(ModelSettingsProviderPlugin::class.java.protectionDomain.codeSource.location.toURI()).copyTo(artifact)
        check(artifact.setReadOnly())
        lateinit var policy: ModelSettingsPolicy
        val capture = kcodePlugin(
            PluginDescriptor("test.model-settings", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-model-settings", inject = dependencies(KcodeModelSettings.Key)) { ctx, _ ->
                policy = ctx.require(KcodeModelSettings.Key).policy
            },
            Unit,
        )
        fun configuration() = KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(capture),
            pluginCompositionStore = FilePluginCompositionStore(directory),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory)
            },
        )
        var runtime = KcodePluginRuntime.create(configuration())
        val provider = runtime.modelCatalog().providers.first { it.models.isNotEmpty() }
        val settings = LegacySettings(
            provider = provider.provider.name,
            modelId = provider.models.first().id,
            modelApiKeys = mapOf(provider.provider.name to "fixture"),
            temperature = 1.75,
            dashscopeRegion = "china_mainland",
        )
        try {
            assertEquals(1.0, policy.resolve(settings, runtime.modelCatalog())?.temperature)
            val deployment = DynamicPluginSpec(
                id = "provider.model-settings.catalog",
                version = "private-policy",
                artifactPath = artifact.path,
                sha256 = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) },
                entryClass = ModelSettingsProviderPlugin::class.java.name,
                config = Json.parseToJsonElement("""{"maximumTemperature":2.0}"""),
            )
            runtime.pluginManager.replace(deployment)
            val original = policy
            assertNotSame(ModelSettingsPolicy::class.java.classLoader, original.javaClass.classLoader)
            assertNotSame(ModelSettingsProviderPlugin::class.java.classLoader, original.javaClass.classLoader)
            assertEquals(original.javaClass.classLoader, Class.forName(
                "ai.meteor.kcode.plugin.modelsettings.TemperatureRange", false, original.javaClass.classLoader,
            ).classLoader)
            val resolved = checkNotNull(original.resolve(settings, runtime.modelCatalog()))
            assertEquals(1.75, resolved.temperature)
            val namespaced = policy.update(settings, resolved)
            assertEquals(true, namespaced.namespaces.containsKey("feature.model-settings"))
            assertEquals(resolved, policy.resolve(namespaced, runtime.modelCatalog()))
            assertEquals(namespaced, original.update(settings, resolved))
            assertEquals(null, original.resolve(settings, ModelCatalogSnapshot()))
            assertFailsWith<IllegalStateException> {
                runtime.pluginManager.replace(deployment.copy(config = Json.parseToJsonElement(
                    """{"minimumTemperature":1.0,"maximumTemperature":0.5}""",
                )))
            }
            assertSame(original, policy)
            assertEquals(1.75, policy.resolve(settings, runtime.modelCatalog())?.temperature)
            runtime.pluginManager.setEnabled("provider.model-settings.catalog", false)
            assertEquals(PluginState.Active, runtime.diagnostics().plugins.first { it.id == "provider.ui.compose" }.state)
            assertFailsWith<IllegalStateException> { original.resolve(settings, runtime.modelCatalog()) }
            assertFailsWith<IllegalStateException> { original.update(settings, resolved) }
            runtime.close()
            runtime = KcodePluginRuntime.create(configuration())
            assertEquals(PluginState.Active, runtime.diagnostics().plugins.first { it.id == "provider.ui.compose" }.state)
            runtime.pluginManager.setEnabled("provider.model-settings.catalog", true)
            assertNotSame(original, policy)
            assertEquals(resolved, policy.resolve(namespaced, runtime.modelCatalog()))
            assertEquals(1.75, policy.resolve(settings, runtime.modelCatalog())?.temperature)
            assertEquals(PluginState.Active, runtime.diagnostics().plugins.first { it.id == "provider.ui.compose" }.state)
            val restored = policy
            runtime.pluginManager.uninstall("provider.model-settings.catalog")
            assertFailsWith<IllegalStateException> { restored.resolve(settings, runtime.modelCatalog()) }
            assertEquals(PluginState.Active, runtime.diagnostics().plugins.first { it.id == "provider.ui.compose" }.state)
            runtime.pluginManager.setEnabled("provider.model-settings.catalog", true)
            assertNotSame(restored, policy)
            assertEquals(1.0, policy.resolve(settings, runtime.modelCatalog())?.temperature)
        } finally {
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }
}
