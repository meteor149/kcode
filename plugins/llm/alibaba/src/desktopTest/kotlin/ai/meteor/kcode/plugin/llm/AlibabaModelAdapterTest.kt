package ai.meteor.kcode.plugin.llm

import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import ai.koog.prompt.executor.clients.dashscope.DashscopeModels
import ai.koog.prompt.llm.LLMCapability
import ai.koog.http.client.KoogHttpClient
import ai.meteor.kcode.model.DashscopeRegion
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json

class AlibabaModelAdapterTest {
    @Test
    fun catalogAndFactoryHaveTheSameOwner() {
        val adapter = nativeAlibabaAdapter()
        val catalog = requireNotNull(adapter.catalog)
        assertEquals(ModelProvider.Alibaba, catalog.provider)
        assertTrue(catalog.models.isNotEmpty())
        assertTrue(catalog.models.all { it.provider == catalog.provider })
        assertTrue(catalog.displayNames["en"].orEmpty().isNotBlank())
        assertTrue(catalog.displayNames["zh"].orEmpty().isNotBlank())
        assertTrue(adapter.supports(ModelConfiguration(catalog.provider, catalog.models.first().id, "fixture", 0.6)))
        assertTrue(!adapter.supports(ModelConfiguration(ModelProvider("external.fixture"), "fixture", "fixture", 0.6)))
    }
    @Test
    fun selectableModelsMatchOwnedClientDefinitions() {
        val expected = DashscopeModels.models.filter { model ->
            LLMCapability.Embed !in model.capabilities.orEmpty() &&
                LLMCapability.Moderation !in model.capabilities.orEmpty()
        }.mapTo(linkedSetOf()) { it.id }
        expected.add("qwen3.8-max")
        val actual = requireNotNull(nativeAlibabaAdapter().catalog).models.mapTo(linkedSetOf()) { it.id }
        assertEquals(expected, actual)
    }

    @Test
    fun unsupportedModelsFailBeforeAllocatingHttpClients() {
        val providers = listOf(ModelProvider.Alibaba)
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
                createAlibabaModelRuntime(
                    ModelConfiguration(provider, "missing-fixture-model", "fixture", temperature = 0.6),
                    factory,
                )
            }
            assertEquals(0, allocations, provider.name)
        }
    }

    @Test
    fun dashscopeClientUsesSelectedRegion() {
        DashscopeRegion.entries.forEach { region ->
            var capturedBaseUrl: String? = null
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
                    capturedBaseUrl = baseUrl
                    throw ClientCreated
                }
            }

            assertFailsWith<ClientCreated> {
                createAlibabaModelRuntime(
                    configuration = ModelConfiguration(
                        provider = ModelProvider.Alibaba,
                        modelId = "qwen3-max",
                        apiKey = "test-key",
                        dashscopeRegion = region, temperature = 0.6,
                    ),
                    httpClientFactory = factory,
                )
            }
            assertEquals(when (region) {
                DashscopeRegion.ChinaMainland -> "https://dashscope.aliyuncs.com/"
                DashscopeRegion.Singapore -> "https://dashscope-intl.aliyuncs.com/"
                DashscopeRegion.UnitedStates -> "https://dashscope-us.aliyuncs.com/"
            }, capturedBaseUrl)
        }
    }

    @Test
    fun qwen38MaxSupportsImageInput() {
        val model = resolveAlibabaModel("qwen3.8-max")

        assertEquals("qwen3.8-max", model.id)
        assertTrue(model.supports(LLMCapability.Vision.Image))
        assertTrue(model.supports(LLMCapability.Vision.Video))
    }

    @Test
    fun dashscopeSerializesBase64VideoAsVideoUrlContent() {
        val payload = rewriteDashscopeVideoContent(
            """{"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:video/mp4;base64,AAAA"}}]}]}""",
        )
        val content = Json.parseToJsonElement(payload)
            .jsonObject["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.single().jsonObject

        assertEquals("video_url", content["type"]!!.jsonPrimitive.content)
        assertEquals("data:video/mp4;base64,AAAA", content["video_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertTrue("image_url" !in content)
    }
}

private object ClientCreated : RuntimeException()
