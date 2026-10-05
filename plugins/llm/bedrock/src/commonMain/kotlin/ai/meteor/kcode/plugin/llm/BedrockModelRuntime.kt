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


internal fun createBedrockModelRuntime(
    configuration: ModelConfiguration,
    httpClientFactory: KoogHttpClient.Factory,
): AgentModelRuntime {
    require(configuration.provider == ModelProvider.Bedrock) { "Adapter does not own this provider" }
    val model = createBedrockModel(configuration.modelId)
    val client = createBedrockClient(configuration)
    return AgentModelRuntime(client, model)
}

private fun LLModelDefinitions.requireModel(modelId: String): LLModel =
    requireNotNull(models.firstOrNull { it.id == modelId }) { "Model '$modelId' is not supported by this provider" }

internal expect fun createBedrockClient(configuration: ModelConfiguration): LLMClient
internal expect fun createBedrockModel(modelId: String): LLModel
internal expect fun bedrockAvailable(): Boolean
