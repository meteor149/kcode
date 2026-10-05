package ai.meteor.kcode.plugin.modelsettings

import ai.meteor.kcode.model.DashscopeRegion
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.ModelCatalogSnapshot
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.settings.AppliedSettingsUpdate
import ai.meteor.kcode.settings.StoredAppSettings

fun StoredAppSettings.applyModelSettingsUpdate(update: SettingsUpdate, catalog: ModelCatalogSnapshot): AppliedSettingsUpdate =
    applyModelSettingsUpdate(update, catalog, TemperatureRange(0.0, 1.0))

internal fun StoredAppSettings.applyModelSettingsUpdate(
    update: SettingsUpdate,
    catalog: ModelCatalogSnapshot,
    range: TemperatureRange,
): AppliedSettingsUpdate {
    val persisted = this
    return with(ModelSettingsDocument.read(persisted)) {
        require(!update.isEmpty) { "No settings were supplied" }

        val currentModelProvider = parseStoredModelProvider(provider)
        val selectedModelProvider = update.values["model-provider"]?.let { parseModelProvider(it, catalog) } ?: currentModelProvider
        if (update.values["model-provider"] != null || update.values["model"] != null || update.values["model-api-key"] != null ||
            update.values["model-endpoint"] != null || update.values["model-region"] != null || update.values["model-deployment"] != null ||
            update.values["model-api-version"] != null || update.values["dashscope-region"] != null || update.values["temperature"] != null) {
            require(catalog.provider(selectedModelProvider) != null) {
                "Open kcode and enable the selected model provider before configuring it"
            }
        }
        val suppliedModel = update.values["model"]
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
        val selectedTemperature = update.values["temperature"]?.let { rawValue ->
            rawValue.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in range.minimum..range.maximum }
                ?: throw IllegalArgumentException("temperature must be a number from ${range.minimum} to ${range.maximum}")
        } ?: temperature
        val selectedDashscopeRegion = update.values["dashscope-region"]?.let { rawValue ->
            DashscopeRegion.entries.firstOrNull { it.code.equals(rawValue.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "dashscope-region must be one of: ${DashscopeRegion.entries.joinToString { it.code }}",
                )
        }?.code ?: dashscopeRegion

        val updatedModelApiKeys = when (val apiKey = update.values["model-api-key"]) {
            null -> modelApiKeys
            "" -> modelApiKeys - selectedModelProvider.name
            else -> modelApiKeys + (selectedModelProvider.name to apiKey)
        }
        val updatedSettings = ModelSettingsDocument.write(persisted, copy(
            provider = selectedModelProvider.name,
            modelId = selectedModel,
            modelApiKeys = updatedModelApiKeys,
            modelEndpoint = update.values["model-endpoint"]?.trim() ?: modelEndpoint,
            modelRegion = update.values["model-region"]?.trim() ?: modelRegion,
            modelDeployment = update.values["model-deployment"]?.trim() ?: modelDeployment,
            modelApiVersion = update.values["model-api-version"]?.trim() ?: modelApiVersion,
            dashscopeRegion = selectedDashscopeRegion,
            temperature = selectedTemperature,
        ))
        val changedFields = buildList {
            if (update.values["model-provider"] != null) add("model-provider")
            if (update.values["model"] != null || selectedModel != modelId) add("model")
            if (update.values["model-api-key"] != null) add("model-api-key")
            if (update.values["model-endpoint"] != null) add("model-endpoint")
            if (update.values["model-region"] != null) add("model-region")
            if (update.values["model-deployment"] != null) add("model-deployment")
            if (update.values["model-api-version"] != null) add("model-api-version")
            if (update.values["dashscope-region"] != null) add("dashscope-region")
            if (update.values["temperature"] != null) add("temperature")
        }
        AppliedSettingsUpdate(updatedSettings, changedFields)
    }
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
