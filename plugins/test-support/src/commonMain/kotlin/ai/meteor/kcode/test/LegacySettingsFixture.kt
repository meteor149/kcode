package ai.meteor.kcode.test

import ai.meteor.kcode.settings.StoredAppSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Historical inputs only. This fixture creates raw migration values, never current feature schemas. */
fun LegacySettings(
    provider: String? = null,
    modelId: String? = null,
    modelApiKeys: Map<String, String>? = null,
    modelEndpoint: String? = null,
    modelRegion: String? = null,
    modelDeployment: String? = null,
    modelApiVersion: String? = null,
    dashscopeRegion: String? = null,
    webSearchApiKey: String? = null,
    exaSearchApiKey: String? = null,
    searchApiKeys: Map<String, String>? = null,
    webSearchProvider: String? = null,
    temperature: Double? = null,
    language: String? = null,
    shellExecutionMode: String? = null,
    toolPermissionMode: String? = null,
    namespaces: Map<String, JsonObject> = emptyMap(),
): StoredAppSettings = StoredAppSettings(namespaces, JsonObject(buildMap {
    provider?.let { value -> put("provider", JsonPrimitive(value)) }
    modelId?.let { value -> put("modelId", JsonPrimitive(value)) }
    modelApiKeys?.let { value -> put("modelApiKeys", JsonObject(value.mapValues { (_, item) -> JsonPrimitive(item) })) }
    modelEndpoint?.let { value -> put("modelEndpoint", JsonPrimitive(value)) }
    modelRegion?.let { value -> put("modelRegion", JsonPrimitive(value)) }
    modelDeployment?.let { value -> put("modelDeployment", JsonPrimitive(value)) }
    modelApiVersion?.let { value -> put("modelApiVersion", JsonPrimitive(value)) }
    dashscopeRegion?.let { value -> put("dashscopeRegion", JsonPrimitive(value)) }
    webSearchApiKey?.let { value -> put("webSearchApiKey", JsonPrimitive(value)) }
    exaSearchApiKey?.let { value -> put("exaSearchApiKey", JsonPrimitive(value)) }
    searchApiKeys?.let { value -> put("searchApiKeys", JsonObject(value.mapValues { (_, item) -> JsonPrimitive(item) })) }
    webSearchProvider?.let { value -> put("webSearchProvider", JsonPrimitive(value)) }
    temperature?.let { value -> put("temperature", JsonPrimitive(value)) }
    language?.let { value -> put("language", JsonPrimitive(value)) }
    shellExecutionMode?.let { value -> put("shellExecutionMode", JsonPrimitive(value)) }
    toolPermissionMode?.let { value -> put("toolPermissionMode", JsonPrimitive(value)) }
}))

/** Test updates to historical payloads remain explicit and preserve values not owned by the fixture. */
fun StoredAppSettings.copy(
    provider: String? = null,
    modelId: String? = null,
    modelApiKeys: Map<String, String>? = null,
    modelEndpoint: String? = null,
    modelRegion: String? = null,
    modelDeployment: String? = null,
    modelApiVersion: String? = null,
    dashscopeRegion: String? = null,
    webSearchApiKey: String? = null,
    exaSearchApiKey: String? = null,
    searchApiKeys: Map<String, String>? = null,
    webSearchProvider: String? = null,
    temperature: Double? = null,
    language: String? = null,
    shellExecutionMode: String? = null,
    toolPermissionMode: String? = null,
    namespaces: Map<String, JsonObject> = this.namespaces,
): StoredAppSettings {
    val updated = LegacySettings(
        provider = provider,
        modelId = modelId,
        modelApiKeys = modelApiKeys,
        modelEndpoint = modelEndpoint,
        modelRegion = modelRegion,
        modelDeployment = modelDeployment,
        modelApiVersion = modelApiVersion,
        dashscopeRegion = dashscopeRegion,
        webSearchApiKey = webSearchApiKey,
        exaSearchApiKey = exaSearchApiKey,
        searchApiKeys = searchApiKeys,
        webSearchProvider = webSearchProvider,
        temperature = temperature,
        language = language,
        shellExecutionMode = shellExecutionMode,
        toolPermissionMode = toolPermissionMode,
    )
    return copy(namespaces = namespaces, legacyValues = JsonObject(legacyValues + updated.legacyValues))
}

val StoredAppSettings.provider: String get() = (legacyValues["provider"] as? JsonPrimitive)?.content.orEmpty()
val StoredAppSettings.modelId: String get() = (legacyValues["modelId"] as? JsonPrimitive)?.content.orEmpty()
val StoredAppSettings.modelApiKeys: Map<String, String> get() = (legacyValues["modelApiKeys"] as? JsonObject).orEmpty().mapValues { (_, value) -> (value as JsonPrimitive).content }
val StoredAppSettings.modelEndpoint: String get() = (legacyValues["modelEndpoint"] as? JsonPrimitive)?.content.orEmpty()
val StoredAppSettings.modelRegion: String get() = (legacyValues["modelRegion"] as? JsonPrimitive)?.content.orEmpty()
val StoredAppSettings.modelDeployment: String get() = (legacyValues["modelDeployment"] as? JsonPrimitive)?.content.orEmpty()
val StoredAppSettings.modelApiVersion: String get() = (legacyValues["modelApiVersion"] as? JsonPrimitive)?.content.orEmpty()
val StoredAppSettings.dashscopeRegion: String get() = (legacyValues["dashscopeRegion"] as? JsonPrimitive)?.content.orEmpty()
val StoredAppSettings.webSearchApiKey: String get() = (legacyValues["webSearchApiKey"] as? JsonPrimitive)?.content.orEmpty()
val StoredAppSettings.exaSearchApiKey: String get() = (legacyValues["exaSearchApiKey"] as? JsonPrimitive)?.content.orEmpty()
val StoredAppSettings.searchApiKeys: Map<String, String> get() = (legacyValues["searchApiKeys"] as? JsonObject).orEmpty().mapValues { (_, value) -> (value as JsonPrimitive).content }
val StoredAppSettings.webSearchProvider: String get() = (legacyValues["webSearchProvider"] as? JsonPrimitive)?.content.orEmpty()
val StoredAppSettings.temperature: Double get() = (legacyValues["temperature"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
val StoredAppSettings.language: String get() = (legacyValues["language"] as? JsonPrimitive)?.content.orEmpty()
val StoredAppSettings.shellExecutionMode: String get() = (legacyValues["shellExecutionMode"] as? JsonPrimitive)?.content.orEmpty()
val StoredAppSettings.toolPermissionMode: String get() = (legacyValues["toolPermissionMode"] as? JsonPrimitive)?.content.orEmpty()
