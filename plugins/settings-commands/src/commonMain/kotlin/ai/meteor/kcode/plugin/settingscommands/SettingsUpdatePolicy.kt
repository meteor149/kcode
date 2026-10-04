package ai.meteor.kcode.plugin.settingscommands

import ai.meteor.kcode.model.DashscopeRegion
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.settings.AppliedSettingsUpdate
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.tools.search.SearchSettingsPolicy

internal fun StoredAppSettings.applySettingsUpdate(update: SettingsUpdate, catalog: ModelCatalogSnapshot, searchPolicy: SearchSettingsPolicy? = null): AppliedSettingsUpdate {
    require(!update.isEmpty) { "No settings were supplied" }

    val currentModelProvider = parseStoredModelProvider(provider)
    val selectedModelProvider = update.modelProvider?.let { parseModelProvider(it, catalog) } ?: currentModelProvider
    if (update.modelProvider != null || update.model != null || update.modelApiKey != null ||
        update.modelEndpoint != null || update.modelRegion != null || update.modelDeployment != null ||
        update.modelApiVersion != null || update.dashscopeRegion != null || update.temperature != null) {
        require(catalog.provider(selectedModelProvider) != null) {
            "Open kcode and enable the selected model provider before configuring it"
        }
    }
    val suppliedModel = update.model
    val suppliedSearchApiKey = update.searchApiKey
    val selectedModel = when {
        suppliedModel != null -> suppliedModel.trim().also {
            require(it.isNotEmpty()) { "model must not be empty" }
            require(catalog.modelOption(selectedModelProvider, it) != null) {
                "model is not supported by the selected model provider"
            }
        }
        selectedModelProvider != currentModelProvider && catalog.modelOption(selectedModelProvider, modelId) == null ->
            catalog.modelsFor(selectedModelProvider).firstOrNull()?.id
                ?: throw IllegalArgumentException("Open kcode and enable the selected model provider before configuring it")
        else -> modelId
    }
    val searchSettings = if (update.searchProvider != null || suppliedSearchApiKey != null) {
        val policy = requireNotNull(searchPolicy) { "Open kcode and enable search settings before configuring it" }
        val providers = checkNotNull(policy.providers()) { "Search settings provider is closed" }
        val current = policy.resolve(this)
        val selected = update.searchProvider?.let { raw ->
            providers.firstOrNull { it.id.equals(raw.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("search-provider must be one of: ${providers.joinToString { it.id }}")
        } ?: providers.firstOrNull { it.id == current.provider }
            ?: throw IllegalArgumentException("Search provider is not enabled")
        require(suppliedSearchApiKey == null || selected.requiresApiKey) {
            "The selected search provider does not use an API key"
        }
        val keys = if (suppliedSearchApiKey == null) current.apiKeys else current.apiKeys + (selected.id to suppliedSearchApiKey)
        policy.update(this, current.copy(provider = selected.id, apiKeys = keys))
    } else this

    val selectedTemperature = update.temperature?.let { rawValue ->
        rawValue.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 }
            ?: throw IllegalArgumentException("temperature must be a number from 0 to 1")
    } ?: temperature
    val selectedDashscopeRegion = update.dashscopeRegion?.let { rawValue ->
        DashscopeRegion.entries.firstOrNull { it.code.equals(rawValue.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "dashscope-region must be one of: ${DashscopeRegion.entries.joinToString { it.code }}",
            )
    }?.code ?: dashscopeRegion

    val updatedModelApiKeys = when (val apiKey = update.modelApiKey) {
        null -> modelApiKeys
        "" -> modelApiKeys - selectedModelProvider.name
        else -> modelApiKeys + (selectedModelProvider.name to apiKey)
    }
    val updatedSettings = searchSettings.copy(
        provider = selectedModelProvider.name,
        modelId = selectedModel,
        modelApiKeys = updatedModelApiKeys,
        modelEndpoint = update.modelEndpoint?.trim() ?: modelEndpoint,
        modelRegion = update.modelRegion?.trim() ?: modelRegion,
        modelDeployment = update.modelDeployment?.trim() ?: modelDeployment,
        modelApiVersion = update.modelApiVersion?.trim() ?: modelApiVersion,
        dashscopeRegion = selectedDashscopeRegion,
        temperature = selectedTemperature,
    )
    val changedFields = buildList {
        if (update.modelProvider != null) add("model-provider")
        if (update.model != null || selectedModel != modelId) add("model")
        if (update.modelApiKey != null) add("model-api-key")
        if (update.modelEndpoint != null) add("model-endpoint")
        if (update.modelRegion != null) add("model-region")
        if (update.modelDeployment != null) add("model-deployment")
        if (update.modelApiVersion != null) add("model-api-version")
        if (update.dashscopeRegion != null) add("dashscope-region")
        if (update.temperature != null) add("temperature")
        if (update.searchProvider != null) add("search-provider")
        if (update.searchApiKey != null) add("search-api-key")
    }
    return AppliedSettingsUpdate(updatedSettings, changedFields)
}

private fun parseStoredModelProvider(value: String): ModelProvider =
    runCatching { ModelProvider(value) }.getOrDefault(ModelProvider.OpenAI)

private fun parseModelProvider(value: String, catalog: ModelCatalogSnapshot): ModelProvider {
    val raw = value.trim()
    catalog.providers.firstOrNull { it.provider.id == raw }?.let { return it.provider }
    val normalized = raw.filter(Char::isLetterOrDigit)
    val legacy = ModelProvider.entries.firstOrNull {
        it.id.filter(Char::isLetterOrDigit).equals(normalized, ignoreCase = true)
    }
    if (legacy != null) return legacy
    throw IllegalArgumentException("model-provider must identify an enabled provider: ${catalog.providers.joinToString { it.provider.id }}")
}
