package ai.meteor.kcode.plugin.llm

import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLMCapability
import ai.koog.http.client.KoogHttpClient
import kotlinx.serialization.json.Json

class AzureOpenAIModelAdapterTest {
    @Test
    fun catalogAndFactoryHaveTheSameOwner() {
        val adapter = nativeAzureOpenAIAdapter()
        val catalog = requireNotNull(adapter.catalog)
        assertEquals(ModelProvider.AzureOpenAI, catalog.provider)
        assertTrue(catalog.models.isNotEmpty())
        assertTrue(catalog.models.all { it.provider == catalog.provider })
        assertTrue(catalog.displayNames["en"].orEmpty().isNotBlank())
        assertTrue(catalog.displayNames["zh"].orEmpty().isNotBlank())
        assertTrue(adapter.supports(ModelConfiguration(catalog.provider, catalog.models.first().id, "fixture", 0.6)))
        assertTrue(!adapter.supports(ModelConfiguration(ModelProvider("external.fixture"), "fixture", "fixture", 0.6)))
    }
    @Test
    fun selectableModelsMatchOwnedClientDefinitions() {
        val expected = OpenAIModels.models.filter { model ->
            LLMCapability.Embed !in model.capabilities.orEmpty() &&
                LLMCapability.Moderation !in model.capabilities.orEmpty()
        }.mapTo(linkedSetOf()) { it.id }
        val actual = requireNotNull(nativeAzureOpenAIAdapter().catalog).models.mapTo(linkedSetOf()) { it.id }
        assertEquals(expected, actual)
    }

    @Test
    fun unsupportedModelsFailBeforeAllocatingHttpClients() {
        val providers = listOf(ModelProvider.AzureOpenAI)
        providers.forEach { provider ->
            var allocations = 0
            val factory = object : KoogHttpClient.Factory {
                override fun create(
                    clientName: String,
                    baseUrl: String,
                    headers: Map<String, String>,
                    queryParameters: Map<String, String>,
                    requestTimeoutMillis: Long,
                    connectTimeoutMillis: Long,
                    socketTimeoutMillis: Long,
                    json: Json,
                ): KoogHttpClient {
                    allocations++
                    error("Invalid model must allocate no HTTP client")
                }
            }
            assertFailsWith<IllegalArgumentException> {
                createAzureOpenAIModelRuntime(
                    ModelConfiguration(provider, "missing-fixture-model", "fixture", temperature = 0.6),
                    factory,
                )
            }
            assertEquals(0, allocations, provider.name)
        }
    }


}

private object ClientCreated : RuntimeException()
