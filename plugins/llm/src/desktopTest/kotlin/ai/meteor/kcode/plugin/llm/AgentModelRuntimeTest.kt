package ai.meteor.kcode.plugin.llm

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.bedrock.BedrockModels
import ai.koog.prompt.executor.clients.dashscope.DashscopeModels
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.mistralai.MistralAIModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.ollama.client.OllamaModels
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.meteor.kcode.model.DashscopeRegion
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentModelRuntimeTest {
    @Test
    fun unsupportedModelsFailBeforeAllocatingHttpClients() {
        val providers = ModelProvider.entries.filter { it != ModelProvider.GLM }
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
                createAgentModelRuntime(
                    ModelConfiguration(provider, "missing-fixture-model", "fixture", temperature = 0.6),
                    factory,
                )
            }
            assertEquals(0, allocations, provider.name)
        }
    }

    @Test
    fun selectableModelsMatchKoogChatModels() {
        val explicitExtensions = mapOf(
            ModelProvider.Alibaba to setOf("qwen3.8-max"),
        )
        val koogModels = mapOf(
            ModelProvider.OpenAI to OpenAIModels.models,
            ModelProvider.AzureOpenAI to OpenAIModels.models,
            ModelProvider.Anthropic to AnthropicModels.models,
            ModelProvider.Google to GoogleModels.models,
            ModelProvider.DeepSeek to DeepSeekModels.models,
            ModelProvider.OpenRouter to OpenRouterModels.models,
            ModelProvider.Bedrock to BedrockModels.models,
            ModelProvider.Mistral to MistralAIModels.models,
            ModelProvider.Alibaba to DashscopeModels.models,
            ModelProvider.Ollama to OllamaModels.models,
        )

        koogModels.forEach { (provider, catalog) ->
            val expected = catalog.filter(LLModel::supportsAgentChat).mapTo(linkedSetOf(), LLModel::id).apply {
                addAll(explicitExtensions[provider].orEmpty())
            }
            val actual = modelsFor(provider).mapTo(linkedSetOf()) { it.id }
            assertEquals(expected, actual, "$provider should expose every Koog chat model")
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
                createAgentModelRuntime(
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

private fun LLModel.supportsAgentChat(): Boolean = capabilities.orEmpty().let {
    LLMCapability.Embed !in it && LLMCapability.Moderation !in it
}

private object ClientCreated : RuntimeException()
