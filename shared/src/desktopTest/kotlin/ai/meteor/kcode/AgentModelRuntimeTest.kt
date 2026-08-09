package ai.meteor.kcode

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
import ai.meteor.kcode.model.modelsFor
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentModelRuntimeTest {
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
                        dashscopeRegion = region,
                    ),
                    httpClientFactory = factory,
                )
            }
            assertEquals(region.baseUrl, capturedBaseUrl)
        }
    }
}

private fun LLModel.supportsAgentChat(): Boolean = capabilities.orEmpty().let {
    LLMCapability.Embed !in it && LLMCapability.Moderation !in it
}

private object ClientCreated : RuntimeException()
