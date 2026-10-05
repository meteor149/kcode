package ai.meteor.kcode.plugin.modelsettings

import ai.meteor.kcode.model.DashscopeRegion
import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.settings.ModelSettingsPolicy
import ai.meteor.kcode.settings.StoredAppSettings
import kotlinx.coroutines.flow.MutableStateFlow

internal class CatalogModelSettingsPolicy(private val temperature: TemperatureRange) : ModelSettingsPolicy {
    private val live = MutableStateFlow(true)

    fun close() { live.value = false }

    override fun resolve(settings: StoredAppSettings, catalog: ModelCatalogSnapshot): ModelConfiguration? {
        check(live.value) { "Model settings policy has been disposed" }
        val values = ModelSettingsDocument.read(settings)
        val specification = catalog.providers.firstOrNull { it.provider.name == values.provider } ?: return null
        val provider = specification.provider
        val selectedModel = catalog.modelOption(provider, values.modelId) ?: return null
        val selectedApiKey = values.modelApiKeys[provider.name].orEmpty()
        if (specification.requirements.apiKey && selectedApiKey.isBlank()) return null
        if (specification.requirements.endpoint && values.modelEndpoint.isBlank()) return null
        if (specification.requirements.region && values.modelRegion.isBlank()) return null
        if (specification.requirements.deployment && values.modelDeployment.isBlank()) return null
        if (!values.temperature.isFinite()) return null
        return ModelConfiguration(
            provider = provider,
            modelId = selectedModel.id,
            apiKey = selectedApiKey,
            temperature = values.temperature.coerceIn(temperature.minimum, temperature.maximum),
            endpoint = values.modelEndpoint,
            region = values.modelRegion,
            deployment = values.modelDeployment,
            apiVersion = values.modelApiVersion,
            dashscopeRegion = DashscopeRegion.entries.firstOrNull { it.code == values.dashscopeRegion }
                ?: DashscopeRegion.ChinaMainland,
        )
    }

    override fun update(settings: StoredAppSettings, configuration: ModelConfiguration): StoredAppSettings {
        check(live.value) { "Model settings policy has been disposed" }
        require(configuration.temperature.isFinite()) { "Temperature must be finite" }
        val values = ModelSettingsDocument.read(settings)
        return ModelSettingsDocument.write(settings, values.copy(
            provider = configuration.provider.name,
            modelId = configuration.modelId,
            modelApiKeys = values.modelApiKeys + (configuration.provider.name to configuration.apiKey),
            temperature = configuration.temperature.coerceIn(temperature.minimum, temperature.maximum),
            modelEndpoint = configuration.endpoint,
            modelRegion = configuration.region,
            modelDeployment = configuration.deployment,
            modelApiVersion = configuration.apiVersion,
            dashscopeRegion = configuration.dashscopeRegion?.code ?: values.dashscopeRegion,
        ))
    }
}
