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

internal fun createAzureOpenAIModelRuntime(
    configuration: ModelConfiguration,
    httpClientFactory: KoogHttpClient.Factory,
): AgentModelRuntime {
    require(configuration.provider == ModelProvider.AzureOpenAI) { "Adapter does not own this provider" }
    val model = OpenAIModels.requireModel(configuration.modelId).copy(provider = LLMProvider.Azure)
    val client = run {
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
    return AgentModelRuntime(client, model)
}

private fun LLModelDefinitions.requireModel(modelId: String): LLModel =
    requireNotNull(models.firstOrNull { it.id == modelId }) { "Model '$modelId' is not supported by this provider" }

private class AzureOpenAICompatibleClient(
    settings: OpenAIClientSettings,
    httpClient: KoogHttpClient,
) : OpenAILLMClient(settings = settings, httpClient = httpClient) {
    override fun llmProvider(): LLMProvider = LLMProvider.Azure
}
