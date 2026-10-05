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
import ai.koog.prompt.executor.clients.mistralai.MistralAILLMClient
import ai.koog.prompt.executor.clients.mistralai.MistralAIModels

internal fun createMistralModelRuntime(
    configuration: ModelConfiguration,
    httpClientFactory: KoogHttpClient.Factory,
): AgentModelRuntime {
    require(configuration.provider == ModelProvider.Mistral) { "Adapter does not own this provider" }
    val model = MistralAIModels.requireModel(configuration.modelId)
    val client = MistralAILLMClient(
        apiKey = configuration.apiKey,
        httpClientFactory = httpClientFactory,
    )
    return AgentModelRuntime(client, model)
}

private fun LLModelDefinitions.requireModel(modelId: String): LLModel =
    requireNotNull(models.firstOrNull { it.id == modelId }) { "Model '$modelId' is not supported by this provider" }
