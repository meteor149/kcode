package ai.meteor.kcode.plugin

import org.cordis.packages.packageFileSha256

import ai.koog.http.client.KoogHttpClient
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.plugin.api.InteractionPolicy
import ai.meteor.kcode.plugin.api.KcodeLlm
import ai.meteor.kcode.plugin.api.PluginDescriptor
import ai.meteor.kcode.plugin.llm.OpenAIModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.AzureOpenAIModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.AnthropicModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.GoogleModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.DeepSeekModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.OpenRouterModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.BedrockModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.MistralModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.AlibabaModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.OllamaModelAdapterPlugin
import ai.meteor.kcode.plugin.llm.GLMModelAdapterPlugin
import ai.meteor.kcode.tools.permission.ToolCallApprover
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.cordis.dependencies
import org.cordis.plugin
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

class AndroidFormalModelAdapterPrivateLoadingTest {
    @Test(timeout = 90000)
    fun privateApkCoreModelAdapters(): Unit = runBlocking {
        verifyEntries { ModelProvider.entries.indexOf(it) < 6 }
    }

    @Test(timeout = 90000)
    fun privateApkAdditionalModelAdapters(): Unit = runBlocking {
        verifyEntries { ModelProvider.entries.indexOf(it) >= 6 }
    }

    private suspend fun verifyEntries(select: (ModelProvider) -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "formal-llm-${System.nanoTime()}").apply { mkdirs() }
        val artifact = File(directory, "llm.apk")
        File(instrumentation.context.applicationInfo.sourceDir).copyTo(artifact)
        check(artifact.setReadOnly())
        val digest = packageFileSha256(artifact)
        lateinit var llm: KcodeLlm
        val capture = kcodePlugin(PluginDescriptor("test.formal-llm", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-formal-llm", inject = dependencies(KcodeLlm.Key)) { ctx, _ ->
                llm = ctx.require(KcodeLlm.Key)
            }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                AndroidDynamicPluginController(ctx, context, loader, inventory, directory)
            },
        ))
        val entries = listOf(
            ModelProvider.OpenAI to OpenAIModelAdapterPlugin::class.java,
            ModelProvider.AzureOpenAI to AzureOpenAIModelAdapterPlugin::class.java,
            ModelProvider.Anthropic to AnthropicModelAdapterPlugin::class.java,
            ModelProvider.Google to GoogleModelAdapterPlugin::class.java,
            ModelProvider.DeepSeek to DeepSeekModelAdapterPlugin::class.java,
            ModelProvider.OpenRouter to OpenRouterModelAdapterPlugin::class.java,
            ModelProvider.Bedrock to BedrockModelAdapterPlugin::class.java,
            ModelProvider.Mistral to MistralModelAdapterPlugin::class.java,
            ModelProvider.Alibaba to AlibabaModelAdapterPlugin::class.java,
            ModelProvider.Ollama to OllamaModelAdapterPlugin::class.java,
            ModelProvider.GLM to GLMModelAdapterPlugin::class.java,
        ).filter { select(it.first) }
        try {
            for ((provider, entry) in entries) {
                val id = "provider.llm.koog.${provider.name}"
                val spec = DynamicPluginSpec(
                    id = id, version = "private-llm", artifactPath = artifact.path,
                    sha256 = digest, entryClass = entry.name,
                    packageName = instrumentation.context.packageName,
                )
                if (provider == ModelProvider.Bedrock) {
                    // The default Android composition no longer mounts the unsupported entry.
                    assertTrue(runtime.diagnostics().plugins.none { it.id == id })
                    runtime.pluginManager.install(spec)
                } else {
                    runtime.pluginManager.replace(spec)
                }
                assertTrue("koog.${provider.name}" in llm.adapterIds())
                val catalog = llm.catalog().providers.singleOrNull { it.provider == provider }
                if (provider == ModelProvider.Bedrock) {
                    assertEquals(null, catalog)
                    continue
                }
                val configuration = ModelConfiguration(provider, requireNotNull(catalog).models.first().id, "fixture", temperature = 0.6)
                assertTrue(requireNotNull(catalog).displayNames.keys.containsAll(listOf("en", "zh")))
                assertTrue(catalog.descriptions.keys.containsAll(listOf("en", "zh")))
                if (provider == ModelProvider.OpenAI) {
                    val model = catalog.models.single { it.id == "gpt-4o-mini" }
                    assertTrue(model.displayNames.keys.containsAll(listOf("en", "zh")))
                    assertTrue(model.descriptions.getValue("zh").isNotBlank())
                }
                val adapter = llm.resolve(configuration)
                assertFailsWith<IllegalStateException> {
                    runtime.pluginManager.replace(spec.copy(version = "invalid-config", config = null))
                }
                assertSame(adapter, llm.resolve(configuration))
                if (provider == ModelProvider.DeepSeek) {
                    val stopped = assertFailsWith<FixtureAllocation> { adapter.create(configuration, allocationProbe) }
                    assertTrue(stopped.stackTrace.any { it.className == "ai.meteor.kcode.plugin.llm.DeepSeekModelRuntimeKt" })
                }
                runtime.pluginManager.setEnabled(id, false)
                assertFalse(adapter.supports(configuration))
                assertTrue(llm.catalog().providers.none { it.provider == provider })
                runtime.pluginManager.setEnabled(id, true)
                assertEquals(catalog, llm.catalog().providers.single { it.provider == provider })
                assertTrue(llm.resolve(configuration).supports(configuration))
                assertFalse(adapter.supports(configuration))
            }
        } finally {
            runtime.close()
            directory.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }

    private class FixtureAllocation : RuntimeException()

    private val allocationProbe = object : KoogHttpClient.Factory {
        override fun create(
            clientName: String,
            baseUrl: String,
            headers: Map<String, String>,
            queryParameters: Map<String, String>,
            requestTimeoutMillis: Long,
            connectTimeoutMillis: Long,
            socketTimeoutMillis: Long,
            json: Json,
        ): KoogHttpClient = throw FixtureAllocation()
    }
}
