package app.kcode.ui.pages.setting

import app.kcode.model.ModelConfiguration
import app.kcode.model.ModelProvider
import app.kcode.model.modelOption
import app.kcode.model.modelsFor
import app.kcode.model.requiresApiKey
import app.kcode.model.requiresDeployment
import app.kcode.model.requiresEndpoint
import app.kcode.model.requiresRegion
import app.kcode.settings.StoredAppSettings

internal data class PersistenceFailure(
    val reading: Boolean,
    val detail: String?,
)

internal fun StoredAppSettings.toModelConfiguration(): ModelConfiguration? {
    val parsedProvider = ModelProvider.entries.firstOrNull { it.name == provider } ?: return null
    val selectedModel = modelOption(modelId)?.takeIf { it.provider == parsedProvider }
        ?: modelsFor(parsedProvider).firstOrNull()
        ?: return null
    if (parsedProvider.requiresApiKey && apiKey.isBlank()) return null
    if (parsedProvider.requiresEndpoint && modelEndpoint.isBlank()) return null
    if (parsedProvider.requiresRegion && modelRegion.isBlank()) return null
    if (parsedProvider.requiresDeployment && modelDeployment.isBlank()) return null
    return ModelConfiguration(
        provider = parsedProvider,
        modelId = selectedModel.id,
        apiKey = apiKey,
        temperature = temperature.coerceIn(0.0, 1.0),
        endpoint = modelEndpoint,
        region = modelRegion,
        deployment = modelDeployment,
        apiVersion = modelApiVersion,
    )
}

internal fun StoredAppSettings.withConfiguration(value: ModelConfiguration) = copy(
    provider = value.provider.name,
    modelId = value.modelId,
    apiKey = value.apiKey,
    temperature = value.temperature,
    modelEndpoint = value.endpoint,
    modelRegion = value.region,
    modelDeployment = value.deployment,
    modelApiVersion = value.apiVersion,
)
