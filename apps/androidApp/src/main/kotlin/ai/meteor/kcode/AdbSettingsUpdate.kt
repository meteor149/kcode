package ai.meteor.kcode

import ai.meteor.kcode.model.DashscopeRegion
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.model.modelOption
import ai.meteor.kcode.model.modelsFor
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.tools.search.WebSearchProvider

internal data class AdbSettingsUpdate(
    val modelProvider: String? = null,
    val model: String? = null,
    val modelApiKey: String? = null,
    val modelEndpoint: String? = null,
    val modelRegion: String? = null,
    val modelDeployment: String? = null,
    val modelApiVersion: String? = null,
    val dashscopeRegion: String? = null,
    val temperature: String? = null,
    val searchProvider: String? = null,
    val searchApiKey: String? = null,
) {
    val isEmpty: Boolean
        get() = listOf(
            modelProvider,
            model,
            modelApiKey,
            modelEndpoint,
            modelRegion,
            modelDeployment,
            modelApiVersion,
            dashscopeRegion,
            temperature,
            searchProvider,
            searchApiKey,
        ).all { it == null }
}

internal data class AppliedAdbSettingsUpdate(
    val settings: StoredAppSettings,
    val changedFields: List<String>,
)

internal fun StoredAppSettings.applyAdbSettingsUpdate(update: AdbSettingsUpdate): AppliedAdbSettingsUpdate {
    require(!update.isEmpty) { "No settings were supplied" }

    val currentModelProvider = parseStoredModelProvider(provider)
    val selectedModelProvider = update.modelProvider?.let(::parseModelProvider) ?: currentModelProvider
    val selectedModel = when {
        update.model != null -> update.model.trim().also {
            require(it.isNotEmpty()) { "model must not be empty" }
            require(modelOption(selectedModelProvider, it) != null) {
                "model is not supported by the selected model provider"
            }
        }
        selectedModelProvider != currentModelProvider && modelOption(selectedModelProvider, modelId) == null ->
            modelsFor(selectedModelProvider).first().id
        else -> modelId
    }
    val selectedSearchProvider = update.searchProvider
        ?.let(::parseSearchProvider)
        ?: WebSearchProvider.fromCode(webSearchProvider)
    require(update.searchApiKey == null || selectedSearchProvider.requiresApiKey) {
        "The selected search provider does not use an API key"
    }

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
    val updatedSettings = copy(
        provider = selectedModelProvider.name,
        modelId = selectedModel,
        modelApiKeys = updatedModelApiKeys,
        modelEndpoint = update.modelEndpoint?.trim() ?: modelEndpoint,
        modelRegion = update.modelRegion?.trim() ?: modelRegion,
        modelDeployment = update.modelDeployment?.trim() ?: modelDeployment,
        modelApiVersion = update.modelApiVersion?.trim() ?: modelApiVersion,
        dashscopeRegion = selectedDashscopeRegion,
        webSearchApiKey = when {
            update.searchApiKey != null && selectedSearchProvider == WebSearchProvider.BrightData ->
                update.searchApiKey
            else -> webSearchApiKey
        },
        exaSearchApiKey = when {
            update.searchApiKey != null && selectedSearchProvider == WebSearchProvider.Exa ->
                update.searchApiKey
            else -> exaSearchApiKey
        },
        webSearchProvider = selectedSearchProvider.code,
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
    return AppliedAdbSettingsUpdate(updatedSettings, changedFields)
}

private fun parseStoredModelProvider(value: String): ModelProvider =
    ModelProvider.entries.firstOrNull { it.name == value } ?: ModelProvider.OpenAI

private fun parseModelProvider(value: String): ModelProvider {
    val normalized = value.filter(Char::isLetterOrDigit)
    return ModelProvider.entries.firstOrNull {
        it.name.filter(Char::isLetterOrDigit).equals(normalized, ignoreCase = true)
    } ?: throw IllegalArgumentException(
        "model-provider must be one of: ${ModelProvider.entries.joinToString { it.externalCode }}",
    )
}

private fun parseSearchProvider(value: String): WebSearchProvider =
    WebSearchProvider.entries.firstOrNull {
        it.code.equals(value.trim(), ignoreCase = true)
    } ?: throw IllegalArgumentException(
        "search-provider must be one of: ${WebSearchProvider.entries.joinToString { it.code }}",
    )

private val ModelProvider.externalCode: String
    get() = when (this) {
        ModelProvider.OpenAI -> "openai"
        ModelProvider.AzureOpenAI -> "azure_openai"
        ModelProvider.Anthropic -> "anthropic"
        ModelProvider.Google -> "google"
        ModelProvider.DeepSeek -> "deepseek"
        ModelProvider.OpenRouter -> "openrouter"
        ModelProvider.Bedrock -> "bedrock"
        ModelProvider.Mistral -> "mistral"
        ModelProvider.Alibaba -> "alibaba"
        ModelProvider.Ollama -> "ollama"
        ModelProvider.GLM -> "glm"
    }
