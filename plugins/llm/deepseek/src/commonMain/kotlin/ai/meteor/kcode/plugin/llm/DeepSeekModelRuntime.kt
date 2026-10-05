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
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels

internal fun createDeepSeekModelRuntime(
    configuration: ModelConfiguration,
    httpClientFactory: KoogHttpClient.Factory,
): AgentModelRuntime {
    require(configuration.provider == ModelProvider.DeepSeek) { "Adapter does not own this provider" }
    val model = DeepSeekModels.requireModel(configuration.modelId)
    val client = DeepSeekLLMClient(
        apiKey = configuration.apiKey,
        httpClientFactory = httpClientFactory,
    )
    return AgentModelRuntime(client, model)
}

private fun LLModelDefinitions.requireModel(modelId: String): LLModel =
    requireNotNull(models.firstOrNull { it.id == modelId }) { "Model '$modelId' is not supported by this provider" }
