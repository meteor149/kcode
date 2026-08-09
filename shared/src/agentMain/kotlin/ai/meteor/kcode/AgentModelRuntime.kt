package ai.meteor.kcode

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.dashscope.DashscopeClientSettings
import ai.koog.prompt.executor.clients.dashscope.DashscopeModels
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.mistralai.MistralAILLMClient
import ai.koog.prompt.executor.clients.mistralai.MistralAIModels
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.ollama.client.OllamaModels
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider

internal data class AgentModelRuntime(
    val client: LLMClient,
    val model: LLModel,
)

internal fun createAgentModelRuntime(
    configuration: ModelConfiguration,
    httpClientFactory: KoogHttpClient.Factory,
): AgentModelRuntime {
    val (client, model) = when (configuration.provider) {
        ModelProvider.OpenAI -> {
            OpenAILLMClient(
                apiKey = configuration.apiKey,
                httpClientFactory = httpClientFactory,
            ) to OpenAIModels.requireModel(configuration.modelId)
        }

        ModelProvider.AzureOpenAI -> {
            val endpoint = configuration.endpoint.trimEnd('/') +
                "/openai/deployments/${configuration.deployment}/"
            val settings = OpenAIClientSettings(
                baseUrl = endpoint,
                chatCompletionsPath = "chat/completions?api-version=${configuration.apiVersion.ifBlank { "2024-10-21" }}",
            )
            val httpClient = httpClientFactory.create(
                clientName = "AzureOpenAIClient",
                baseUrl = endpoint,
                headers = mapOf("api-key" to configuration.apiKey),
            )
            AzureOpenAICompatibleClient(settings, httpClient) to OpenAIModels
                .requireModel(configuration.modelId)
                .copy(provider = LLMProvider.Azure)
        }

        ModelProvider.Anthropic -> AnthropicLLMClient(
            apiKey = configuration.apiKey,
            httpClientFactory = httpClientFactory,
        ) to AnthropicModels.requireModel(configuration.modelId)

        ModelProvider.Google -> GoogleLLMClient(
            apiKey = configuration.apiKey,
            httpClientFactory = httpClientFactory,
        ) to GoogleModels.requireModel(configuration.modelId)

        ModelProvider.DeepSeek -> DeepSeekLLMClient(
            apiKey = configuration.apiKey,
            httpClientFactory = httpClientFactory,
        ) to DeepSeekModels.requireModel(configuration.modelId)

        ModelProvider.OpenRouter -> OpenRouterLLMClient(
            apiKey = configuration.apiKey,
            httpClientFactory = httpClientFactory,
        ) to OpenRouterModels.requireModel(configuration.modelId)

        ModelProvider.Bedrock -> createBedrockClient(configuration) to
            createBedrockModel(configuration.modelId)

        ModelProvider.Mistral -> MistralAILLMClient(
            apiKey = configuration.apiKey,
            httpClientFactory = httpClientFactory,
        ) to MistralAIModels.requireModel(configuration.modelId)

        ModelProvider.Alibaba -> {
            val model = if (configuration.modelId == "qwen3.8-max") {
                providerModel(LLMProvider.Alibaba, configuration.modelId)
            } else {
                DashscopeModels.requireModel(configuration.modelId)
            }
            KcodeDashscopeLLMClient(
                apiKey = configuration.apiKey,
                settings = DashscopeClientSettings(
                    baseUrl = configuration.dashscopeRegion.baseUrl,
                    chatCompletionsPath = "compatible-mode/v1/chat/completions",
                ),
                httpClientFactory = httpClientFactory,
            ) to model
        }

        ModelProvider.Ollama -> {
            val model = OllamaModels.requireModel(configuration.modelId)
            OllamaClient(
                httpClientFactory = httpClientFactory,
                baseUrl = configuration.endpoint,
            ) to model.copy(
                capabilities = model.capabilities.orEmpty() - LLMCapability.ToolChoice,
            )
        }

        ModelProvider.GLM -> ZhipuCompatibleClient(
            configuration.apiKey,
            httpClientFactory,
        ) to providerModel(LLMProvider.ZhipuAI, configuration.modelId).copy(
            capabilities = providerModelCapabilities + LLMCapability.OpenAIEndpoint.Completions,
        )
    }
    return AgentModelRuntime(client, model)
}

internal expect fun createBedrockClient(configuration: ModelConfiguration): LLMClient

internal expect fun createBedrockModel(modelId: String): LLModel

private val providerModelCapabilities: List<LLMCapability> = listOf(
    LLMCapability.Temperature,
    LLMCapability.Completion,
    LLMCapability.Tools,
    LLMCapability.ToolChoice,
)

private fun providerModel(provider: LLMProvider, modelId: String): LLModel = LLModel(
    provider = provider,
    id = modelId,
    capabilities = providerModelCapabilities,
)

private fun ai.koog.prompt.executor.clients.LLModelDefinitions.requireModel(modelId: String): LLModel =
    requireNotNull(models.firstOrNull { it.id == modelId }) {
        "Model '$modelId' is not supported by Koog for this provider"
    }

private class ZhipuCompatibleClient(
    apiKey: String,
    httpClientFactory: KoogHttpClient.Factory,
) : OpenAILLMClient(
    apiKey = apiKey,
    settings = OpenAIClientSettings(
        baseUrl = "https://open.bigmodel.cn/",
        chatCompletionsPath = "api/paas/v4/chat/completions",
    ),
    httpClientFactory = httpClientFactory,
) {
    override fun llmProvider(): LLMProvider = LLMProvider.ZhipuAI
}

private class AzureOpenAICompatibleClient(
    settings: OpenAIClientSettings,
    httpClient: KoogHttpClient,
) : OpenAILLMClient(settings = settings, httpClient = httpClient) {
    override fun llmProvider(): LLMProvider = LLMProvider.Azure
}
