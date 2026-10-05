package ai.meteor.kcode.plugin.llm

import ai.meteor.kcode.AgentModelRuntime
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.LLModelDefinitions
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels

internal fun createGLMModelRuntime(
    configuration: ModelConfiguration,
    httpClientFactory: KoogHttpClient.Factory,
): AgentModelRuntime {
    require(configuration.provider == ModelProvider.GLM) { "Adapter does not own this provider" }
    val model = providerModel(LLMProvider.ZhipuAI, configuration.modelId).copy(
        capabilities = providerModelCapabilities + LLMCapability.OpenAIEndpoint.Completions,
    )
    val client = ZhipuCompatibleClient(configuration.apiKey, httpClientFactory)
    return AgentModelRuntime(client, model)
}

private fun LLModelDefinitions.requireModel(modelId: String): LLModel =
    requireNotNull(models.firstOrNull { it.id == modelId }) { "Model '$modelId' is not supported by this provider" }

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
