package ai.meteor.kcode.plugin

import ai.meteor.kcode.model.ModelConfiguration
import ai.meteor.kcode.model.DashscopeRegion
import ai.meteor.kcode.tools.search.SearchSettingsPolicy
import ai.meteor.kcode.tools.search.SearchSettingsConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ai.meteor.kcode.plugin.ui.api.SettingsSectionRequest
import ai.meteor.kcode.plugin.ui.api.UiRenderer
import ai.meteor.kcode.plugin.settings.ModelServiceSettings
import ai.meteor.kcode.plugin.settings.InternetSearchSettings

internal object ModelSettingsRenderer : UiRenderer<SettingsSectionRequest> {
    @Composable
    override fun Render(request: SettingsSectionRequest) {
        val catalog = request.page.modelCatalog
        if (catalog.providers.isEmpty()) return
        val current = request.page.current
        val settings = request.page.appSettings
        val storedProvider = catalog.providers.firstOrNull { it.provider.name == settings.provider }?.provider
            ?: catalog.providers.first().provider
        val modelApiKeys = settings.modelApiKeys
        val dashscopeRegion = DashscopeRegion.fromCode(settings.dashscopeRegion) ?: DashscopeRegion.ChinaMainland
        val persistenceFailure = request.page.persistenceFailure
        val saveModel: (ModelConfiguration, Map<String, String>) -> Unit = saveModel@{ saved, keys ->
            val existing = current?.takeIf { it.provider == saved.provider }?.let { catalog.modelOption(saved.provider, it.modelId) }
            val model = existing ?: catalog.modelsFor(saved.provider).firstOrNull() ?: return@saveModel
            request.page.onModelSettingsChange(saved.copy(
                    modelId = model.id,
                    temperature = if (existing != null) current.temperature else model.defaultTemperature,
                ), keys)
        }
        var selectedProvider by remember(current, storedProvider) {
            mutableStateOf(current?.provider ?: storedProvider)
        }
        val provider = selectedProvider.takeIf { catalog.provider(it) != null } ?: storedProvider
        val defaults = catalog.provider(provider)!!.defaults
        var apiKeys by remember(modelApiKeys, current) {
            mutableStateOf(
                if (current == null || current.apiKey.isBlank()) {
                    modelApiKeys
                } else {
                    modelApiKeys + (current.provider.name to current.apiKey)
                },
            )
        }
        var endpoint by remember(current, provider, settings.modelEndpoint) { mutableStateOf(
            current?.takeIf { it.provider == provider }?.endpoint
                ?: settings.modelEndpoint.takeIf { settings.provider == provider.name && it.isNotBlank() }
                ?: defaults.endpoint,
        ) }
        var region by remember(current, provider, settings.modelRegion) { mutableStateOf(
            current?.takeIf { it.provider == provider }?.region
                ?: settings.modelRegion.takeIf { settings.provider == provider.name && it.isNotBlank() }
                ?: defaults.region,
        ) }
        var deployment by remember(current, provider, settings.modelDeployment) { mutableStateOf(
            current?.takeIf { it.provider == provider }?.deployment
                ?: settings.modelDeployment.takeIf { settings.provider == provider.name && it.isNotBlank() }
                ?: defaults.deployment,
        ) }
        var apiVersion by remember(current, provider, settings.modelApiVersion) { mutableStateOf(
            current?.takeIf { it.provider == provider }?.apiVersion
                ?: settings.modelApiVersion.takeIf { settings.provider == provider.name && it.isNotBlank() }
                ?: defaults.apiVersion,
        ) }
        var selectedDashscopeRegion by remember(dashscopeRegion) { mutableStateOf(dashscopeRegion) }
        var showKey by remember { mutableStateOf(false) }
        ModelServiceSettings(
            catalog = catalog,
            provider = provider,
            apiKey = apiKeys[provider.name].orEmpty(),
            dashscopeRegion = selectedDashscopeRegion,
            endpoint = endpoint,
            region = region,
            deployment = deployment,
            apiVersion = apiVersion,
            showKey = showKey,
            persistenceFailure = persistenceFailure,
            onProviderChange = { selectedProvider = it },
            onApiKeyChange = { apiKey ->
                apiKeys = apiKeys + (provider.name to apiKey)
            },
            onDashscopeRegionChange = { selectedDashscopeRegion = it },
            onEndpointChange = { endpoint = it },
            onRegionChange = { region = it },
            onDeploymentChange = { deployment = it },
            onApiVersionChange = { apiVersion = it },
            onToggleKey = { showKey = !showKey },
            onSave = save@{
                val model = catalog.modelsFor(provider).firstOrNull() ?: return@save
                saveModel(ModelConfiguration(
                        provider = provider,
                        modelId = model.id,
                        apiKey = apiKeys[provider.name].orEmpty().trim(),
                        temperature = model.defaultTemperature,
                        endpoint = endpoint.trim(),
                        region = region.trim(),
                        deployment = deployment.trim(),
                        apiVersion = apiVersion.trim(),
                        dashscopeRegion = selectedDashscopeRegion,
                    ), apiKeys.mapValues { it.value.trim() })
                request.onReturn()
            },
        )
    }
}

internal class SearchSettingsRenderer(private val policy: SearchSettingsPolicy) : UiRenderer<SettingsSectionRequest> {
    @Composable
    override fun Render(request: SettingsSectionRequest) {
        val providers = policy.providers() ?: return
        if (providers.isEmpty()) return
        val settings = request.page.appSettings
        val configuration = policy.resolve(settings)
        var searchProvider by remember(policy, configuration.provider) { mutableStateOf(configuration.provider) }
        var apiKeys by remember(policy, configuration.apiKeys) { mutableStateOf(configuration.apiKeys) }
        var showSearchKey by remember { mutableStateOf(false) }
        val selected = providers.firstOrNull { it.id == searchProvider } ?: return
        InternetSearchSettings(
            providers = providers,
            provider = selected,
            apiKey = apiKeys[selected.id].orEmpty(),
            showKey = showSearchKey,
            onProviderChange = { searchProvider = it.id },
            onApiKeyChange = { apiKeys = apiKeys + (selected.id to it) },
            onToggleKey = { showSearchKey = !showSearchKey },
            onSave = {
                request.page.onSettingsChange(policy.update(settings, SearchSettingsConfiguration(
                    searchProvider, apiKeys.mapValues { it.value.trim() },
                )))
                request.onReturn()
            },
        )
    }
}
