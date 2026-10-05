package ai.meteor.kcode.plugin

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
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.cordis.dependencies
import org.cordis.plugin
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.nio.file.Files
import kotlin.test.Test

class FormalModelAdapterPrivateLoadingTest {
    @Test
    fun privateJarsOwnEveryDefaultModelAdapter(): Unit = runBlocking { verifyEntries { true } }

    private suspend fun verifyEntries(select: (ModelProvider) -> Boolean) {
        val directory = Files.createTempDirectory("formal-llm").toFile()
        lateinit var llm: KcodeLlm
        val capture = kcodePlugin(PluginDescriptor("test.formal-llm", "test", "test", emptySet()),
            plugin<Unit>(name = "capture-formal-llm", inject = dependencies(KcodeLlm.Key)) { ctx, _ ->
                llm = ctx.require(KcodeLlm.Key)
            }, Unit)
        val runtime = KcodePluginRuntime.create(KcodePluginRuntimeConfig(
            interactionPolicy = InteractionPolicy(approver = ToolCallApprover { true }),
            featurePlugins = listOf(capture),
            dynamicPluginControllerFactory = DynamicPluginControllerFactory { ctx, loader, inventory ->
                DesktopDynamicPluginController(ctx, loader, inventory, directory)
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
                val source = File(System.getProperty("kcode.llm.packaged.dir"), "provider-llm-koog-${provider.name}/desktop/plugin.jar")
                val artifact = File(directory, "${provider.name}.jar").also { source.copyTo(it); check(it.setReadOnly()) }
                val digest = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) }
                val spec = DynamicPluginSpec(
                    id = id, version = "private-llm", artifactPath = artifact.path,
                    sha256 = digest, entryClass = entry.name,
                )
                runtime.pluginManager.replace(spec)
                assertTrue("koog.${provider.name}" in llm.adapterIds())
                val catalog = llm.catalog().providers.singleOrNull { it.provider == provider }
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
                    assertTrue(stopped.stackTrace.any { it.className == "ai.meteor.kcode.plugin.llm.AgentModelRuntimeKt" })
                }
                if (provider == ModelProvider.Bedrock) {
                    // Construct the independently loaded factory, including its AWS identity provider,
                    // without sending a request or resolving credentials.
                    val modelRuntime = adapter.create(configuration.copy(region = "us-west-2"), allocationProbe)
                    assertTrue(modelRuntime.model.id.isNotBlank())
                    assertTrue(implementationClient(modelRuntime.client).javaClass.classLoader !== ai.koog.prompt.executor.clients.LLMClient::class.java.classLoader)
                    assertSame(ai.koog.prompt.executor.clients.LLMClient::class.java, Class.forName(
                        "ai.koog.prompt.executor.clients.LLMClient", false, implementationClient(modelRuntime.client).javaClass.classLoader,
                    ))
                    modelRuntime.client.close()
                }
                val clientRuntime = adapter.create(configuration.copy(
                    endpoint = "https://fixture.invalid", deployment = "fixture", region = "us-west-2",
                ), ai.koog.http.client.ktor.KtorKoogHttpClient.Factory())
                try {
                    val privateLoader = implementationClient(clientRuntime.client).javaClass.classLoader
                    assertTrue(privateLoader !== ai.koog.prompt.executor.clients.LLMClient::class.java.classLoader)
                    assertSame(ai.koog.prompt.executor.clients.LLMClient::class.java, Class.forName(
                        "ai.koog.prompt.executor.clients.LLMClient", false, privateLoader,
                    ))
                } finally {
                    clientRuntime.client.close()
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

    @Test
    fun providerArchivesContainTheirOwnClosureAndOmitUnrelatedClientsAndSharedProtocols() {
        val clients = mapOf(
            "OpenAI" to "ai/koog/prompt/executor/clients/openai/OpenAILLMClient.class",
            "AzureOpenAI" to "ai/koog/prompt/executor/clients/openai/OpenAILLMClient.class",
            "GLM" to "ai/koog/prompt/executor/clients/openai/OpenAILLMClient.class",
            "Anthropic" to "ai/koog/prompt/executor/clients/anthropic/AnthropicLLMClient.class",
            "Google" to "ai/koog/prompt/executor/clients/google/GoogleLLMClient.class",
            "DeepSeek" to "ai/koog/prompt/executor/clients/deepseek/DeepSeekLLMClient.class",
            "OpenRouter" to "ai/koog/prompt/executor/clients/openrouter/OpenRouterLLMClient.class",
            "Bedrock" to "ai/koog/prompt/executor/clients/bedrock/BedrockLLMClient.class",
            "Mistral" to "ai/koog/prompt/executor/clients/mistralai/MistralAILLMClient.class",
            "Alibaba" to "ai/koog/prompt/executor/clients/dashscope/DashscopeLLMClient.class",
            "Ollama" to "ai/koog/prompt/executor/ollama/client/OllamaClient.class",
        )
        clients.forEach { (provider, client) ->
            java.util.zip.ZipFile(File(System.getProperty("kcode.llm.packaged.dir"), "provider-llm-koog-$provider/desktop/plugin.jar")).use { archive ->
                assertTrue(archive.getEntry(client) != null, "$provider must own $client")
                // Bedrock uses Anthropic's model definitions and wire serializers upstream.
                val required = setOf(client) + if (provider == "Bedrock") setOf(clients.getValue("Anthropic")) else emptySet()
                required.forEach { dependency -> assertTrue(archive.getEntry(dependency) != null) }
                (clients.values.toSet() - required).forEach { sibling ->
                    assertTrue(archive.getEntry(sibling) == null, "$provider must omit $sibling")
                }
                assertTrue(archive.getEntry("ai/koog/prompt/executor/clients/LLMClient.class") == null)
            }
        }
    }

    // The SDK intentionally returns an owned facade. Inspect its borrowed implementation
    // only in this boundary test so facade identity cannot hide a host-loaded vendor client.
    private fun implementationClient(client: ai.koog.prompt.executor.clients.LLMClient): ai.koog.prompt.executor.clients.LLMClient {
        val delegate = client.javaClass.declaredFields.single { it.type == ai.koog.prompt.executor.clients.LLMClient::class.java }
        delegate.isAccessible = true
        return delegate.get(client) as ai.koog.prompt.executor.clients.LLMClient
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
