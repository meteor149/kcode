package ai.meteor.kcode.ui.pages.setting

import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.DashscopeRegion
import ai.meteor.kcode.model.modelOption
import ai.meteor.kcode.model.modelsFor
import ai.meteor.kcode.model.requiresApiKey
import ai.meteor.kcode.model.requiresDeployment
import ai.meteor.kcode.model.requiresEndpoint
import ai.meteor.kcode.model.requiresRegion
import ai.meteor.kcode.settings.StoredAppSettings

internal data class PersistenceFailure(
    val reading: Boolean,
    val detail: String?,
)

internal fun StoredAppSettings.toModelConfiguration(): ModelConfiguration? {
    val parsedProvider = ModelProvider.entries.firstOrNull { it.name == provider } ?: return null
    val selectedModel = modelOption(parsedProvider, modelId)
        ?: modelsFor(parsedProvider).firstOrNull()
        ?: return null
    val selectedApiKey = modelApiKeys[parsedProvider.name].orEmpty()
    if (parsedProvider.requiresApiKey && selectedApiKey.isBlank()) return null
    if (parsedProvider.requiresEndpoint && modelEndpoint.isBlank()) return null
    if (parsedProvider.requiresRegion && modelRegion.isBlank()) return null
    if (parsedProvider.requiresDeployment && modelDeployment.isBlank()) return null
    return ModelConfiguration(
        provider = parsedProvider,
        modelId = selectedModel.id,
        apiKey = selectedApiKey,
        temperature = temperature.coerceIn(0.0, 1.0),
        endpoint = modelEndpoint,
        region = modelRegion,
        deployment = modelDeployment,
        apiVersion = modelApiVersion,
        dashscopeRegion = DashscopeRegion.fromCode(dashscopeRegion),
    )
}

internal fun StoredAppSettings.withConfiguration(value: ModelConfiguration) = copy(
    provider = value.provider.name,
    modelId = value.modelId,
    modelApiKeys = modelApiKeys + (value.provider.name to value.apiKey),
    temperature = value.temperature,
    modelEndpoint = value.endpoint,
    modelRegion = value.region,
    modelDeployment = value.deployment,
    modelApiVersion = value.apiVersion,
    dashscopeRegion = value.dashscopeRegion.code,
)
