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
        val specification = catalog.providers.firstOrNull { it.provider.name == settings.provider } ?: return null
        val provider = specification.provider
        val selectedModel = catalog.modelOption(provider, settings.modelId) ?: return null
        val selectedApiKey = settings.modelApiKeys[provider.name].orEmpty()
        if (specification.requirements.apiKey && selectedApiKey.isBlank()) return null
        if (specification.requirements.endpoint && settings.modelEndpoint.isBlank()) return null
        if (specification.requirements.region && settings.modelRegion.isBlank()) return null
        if (specification.requirements.deployment && settings.modelDeployment.isBlank()) return null
        if (!settings.temperature.isFinite()) return null
        return ModelConfiguration(
            provider = provider,
            modelId = selectedModel.id,
            apiKey = selectedApiKey,
            temperature = settings.temperature.coerceIn(temperature.minimum, temperature.maximum),
            endpoint = settings.modelEndpoint,
            region = settings.modelRegion,
            deployment = settings.modelDeployment,
            apiVersion = settings.modelApiVersion,
            dashscopeRegion = DashscopeRegion.entries.firstOrNull { it.code == settings.dashscopeRegion }
                ?: DashscopeRegion.ChinaMainland,
        )
    }

    override fun update(settings: StoredAppSettings, configuration: ModelConfiguration): StoredAppSettings {
        check(live.value) { "Model settings policy has been disposed" }
        return settings.copy(
            provider = configuration.provider.name,
            modelId = configuration.modelId,
            modelApiKeys = settings.modelApiKeys + (configuration.provider.name to configuration.apiKey),
            temperature = configuration.temperature,
            modelEndpoint = configuration.endpoint,
            modelRegion = configuration.region,
            modelDeployment = configuration.deployment,
            modelApiVersion = configuration.apiVersion,
            dashscopeRegion = configuration.dashscopeRegion?.code ?: settings.dashscopeRegion,
        )
    }
}
