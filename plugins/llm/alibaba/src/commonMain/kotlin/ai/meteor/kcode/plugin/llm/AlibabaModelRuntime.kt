package ai.meteor.kcode.plugin.llm

import ai.meteor.kcode.AgentModelRuntime
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.DashscopeRegion
import ai.meteor.kcode.model.ModelProvider
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.LLModelDefinitions
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.executor.clients.dashscope.DashscopeClientSettings
import ai.koog.prompt.executor.clients.dashscope.DashscopeModels

internal fun createAlibabaModelRuntime(
    configuration: ModelConfiguration,
    httpClientFactory: KoogHttpClient.Factory,
): AgentModelRuntime {
    require(configuration.provider == ModelProvider.Alibaba) { "Adapter does not own this provider" }
    val model = resolveAlibabaModel(configuration.modelId)
    val client = KcodeDashscopeLLMClient(
        apiKey = configuration.apiKey,
        settings = DashscopeClientSettings(
            baseUrl = dashscopeBaseUrl(DashscopeRegion.fromCode(configuration.region) ?: configuration.dashscopeRegion ?: DashscopeRegion.ChinaMainland),
            chatCompletionsPath = "compatible-mode/v1/chat/completions",
        ),
        httpClientFactory = httpClientFactory,
    )
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


/** Endpoint policy belongs to the Alibaba adapter, not to the persisted region vocabulary. */
private fun dashscopeBaseUrl(region: DashscopeRegion): String = when (region) {
    DashscopeRegion.ChinaMainland -> "https://dashscope.aliyuncs.com/"
    DashscopeRegion.Singapore -> "https://dashscope-intl.aliyuncs.com/"
    DashscopeRegion.UnitedStates -> "https://dashscope-us.aliyuncs.com/"
}
