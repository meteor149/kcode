package ai.meteor.kcode.plugin.llm

import ai.meteor.kcode.AgentModelRuntime

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
import ai.meteor.kcode.model.DashscopeRegion

internal fun createAgentModelRuntime(
    configuration: ModelConfiguration,
    httpClientFactory: KoogHttpClient.Factory,
): AgentModelRuntime {
    // Resolve metadata before allocating a network client; invalid configuration allocates nothing.
    val model = when (configuration.provider) {
        ModelProvider.OpenAI -> OpenAIModels.requireModel(configuration.modelId)
        ModelProvider.AzureOpenAI -> OpenAIModels.requireModel(configuration.modelId).copy(provider = LLMProvider.Azure)
        ModelProvider.Anthropic -> AnthropicModels.requireModel(configuration.modelId)
        ModelProvider.Google -> GoogleModels.requireModel(configuration.modelId)
        ModelProvider.DeepSeek -> DeepSeekModels.requireModel(configuration.modelId)
        ModelProvider.OpenRouter -> OpenRouterModels.requireModel(configuration.modelId)
        ModelProvider.Bedrock -> createBedrockModel(configuration.modelId)
        ModelProvider.Mistral -> MistralAIModels.requireModel(configuration.modelId)
        ModelProvider.Alibaba -> resolveAlibabaModel(configuration.modelId)
        ModelProvider.Ollama -> OllamaModels.requireModel(configuration.modelId).let {
            it.copy(capabilities = it.capabilities.orEmpty() - LLMCapability.ToolChoice)
        }
        ModelProvider.GLM -> providerModel(LLMProvider.ZhipuAI, configuration.modelId).copy(
            capabilities = providerModelCapabilities + LLMCapability.OpenAIEndpoint.Completions,
        )
        else -> error("The built-in Koog adapter does not own provider '${configuration.provider.id}'")
    }
    val client = when (configuration.provider) {
        ModelProvider.OpenAI -> OpenAILLMClient(
            apiKey = configuration.apiKey,
            httpClientFactory = httpClientFactory,
        )
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
            AzureOpenAICompatibleClient(settings, httpClient)
        }
        ModelProvider.Anthropic -> AnthropicLLMClient(
            apiKey = configuration.apiKey,
            httpClientFactory = httpClientFactory,
        )
        ModelProvider.Google -> GoogleLLMClient(
            apiKey = configuration.apiKey,
            httpClientFactory = httpClientFactory,
        )
        ModelProvider.DeepSeek -> DeepSeekLLMClient(
            apiKey = configuration.apiKey,
            httpClientFactory = httpClientFactory,
        )
        ModelProvider.OpenRouter -> OpenRouterLLMClient(
            apiKey = configuration.apiKey,
            httpClientFactory = httpClientFactory,
        )
        ModelProvider.Bedrock -> createBedrockClient(configuration)
        ModelProvider.Mistral -> MistralAILLMClient(
            apiKey = configuration.apiKey,
            httpClientFactory = httpClientFactory,
        )
        ModelProvider.Alibaba -> KcodeDashscopeLLMClient(
            apiKey = configuration.apiKey,
            settings = DashscopeClientSettings(
                baseUrl = dashscopeBaseUrl(configuration.dashscopeRegion ?: DashscopeRegion.ChinaMainland),
                chatCompletionsPath = "compatible-mode/v1/chat/completions",
            ),
            httpClientFactory = httpClientFactory,
        )
        ModelProvider.Ollama -> OllamaClient(
            httpClientFactory = httpClientFactory,
            baseUrl = configuration.endpoint,
        )
        ModelProvider.GLM -> ZhipuCompatibleClient(configuration.apiKey, httpClientFactory)
        else -> error("The built-in Koog adapter does not own provider '${configuration.provider.id}'")
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

internal fun resolveAlibabaModel(modelId: String): LLModel = if (modelId == "qwen3.8-max") {
    providerModel(LLMProvider.Alibaba, modelId).copy(
        capabilities = providerModelCapabilities + listOf(
            LLMCapability.Vision.Image,
            LLMCapability.Vision.Video,
        ),
    )
} else {
    DashscopeModels.requireModel(modelId)
}

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

internal expect fun modelProviderAvailable(provider: ModelProvider): Boolean

/** Endpoint policy belongs to the Alibaba adapter, not to the persisted region vocabulary. */
private fun dashscopeBaseUrl(region: DashscopeRegion): String = when (region) {
    DashscopeRegion.ChinaMainland -> "https://dashscope.aliyuncs.com/"
    DashscopeRegion.Singapore -> "https://dashscope-intl.aliyuncs.com/"
    DashscopeRegion.UnitedStates -> "https://dashscope-us.aliyuncs.com/"
}
