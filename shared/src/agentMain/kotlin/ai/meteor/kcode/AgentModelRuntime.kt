package ai.meteor.kcode

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.dashscope.DashscopeLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.mistralai.MistralAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
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
            val model = when (configuration.modelId) {
                "gpt-4o" -> OpenAIModels.Chat.GPT4o
                else -> OpenAIModels.Chat.GPT4oMini
            }
            OpenAILLMClient(
                apiKey = configuration.apiKey,
                httpClientFactory = httpClientFactory,
            ) to model
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
            AzureOpenAICompatibleClient(settings, httpClient) to
                providerModel(LLMProvider.Azure, configuration.modelId)
        }

        ModelProvider.Anthropic -> AnthropicLLMClient(
            apiKey = configuration.apiKey,
            httpClientFactory = httpClientFactory,
        ) to providerModel(LLMProvider.Anthropic, configuration.modelId)

        ModelProvider.Google -> GoogleLLMClient(
            apiKey = configuration.apiKey,
            httpClientFactory = httpClientFactory,
        ) to providerModel(LLMProvider.Google, configuration.modelId)

        ModelProvider.DeepSeek -> {
            val model = when (configuration.modelId) {
                "deepseek-v4-pro" -> DeepSeekModels.DeepSeekV4Pro
                else -> DeepSeekModels.DeepSeekV4Flash
            }
            DeepSeekLLMClient(
                apiKey = configuration.apiKey,
                httpClientFactory = httpClientFactory,
            ) to model
        }

        ModelProvider.OpenRouter -> OpenRouterLLMClient(
            apiKey = configuration.apiKey,
            httpClientFactory = httpClientFactory,
        ) to providerModel(LLMProvider.OpenRouter, configuration.modelId)

        ModelProvider.Bedrock -> createBedrockClient(configuration) to
            providerModel(LLMProvider.Bedrock, configuration.modelId)

        ModelProvider.Mistral -> MistralAILLMClient(
            apiKey = configuration.apiKey,
            httpClientFactory = httpClientFactory,
        ) to providerModel(LLMProvider.MistralAI, configuration.modelId)

        ModelProvider.Alibaba -> DashscopeLLMClient(
            apiKey = configuration.apiKey,
            httpClientFactory = httpClientFactory,
        ) to providerModel(LLMProvider.Alibaba, configuration.modelId)

        ModelProvider.Ollama -> OllamaClient(
            httpClientFactory = httpClientFactory,
            baseUrl = configuration.endpoint,
        ) to providerModel(LLMProvider.Ollama, configuration.modelId, toolChoice = false)

        ModelProvider.GLM -> ZhipuCompatibleClient(
            configuration.apiKey,
            httpClientFactory,
        ) to LLModel(
            provider = LLMProvider.ZhipuAI,
            id = configuration.modelId,
        )
    }
    return AgentModelRuntime(client, model)
}

private fun providerModel(
    provider: LLMProvider,
    id: String,
    toolChoice: Boolean = true,
): LLModel = LLModel(
    provider = provider,
    id = id,
    capabilities = buildList {
        add(LLMCapability.Temperature)
        add(LLMCapability.Completion)
        add(LLMCapability.Tools)
        if (toolChoice) add(LLMCapability.ToolChoice)
    },
)

internal expect fun createBedrockClient(configuration: ModelConfiguration): LLMClient

private class ZhipuCompatibleClient(
    apiKey: String,
    httpClientFactory: KoogHttpClient.Factory,
) : OpenAILLMClient(
    apiKey = apiKey,
    settings = OpenAIClientSettings(
        baseUrl = "https://open.bigmodel.cn",
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
