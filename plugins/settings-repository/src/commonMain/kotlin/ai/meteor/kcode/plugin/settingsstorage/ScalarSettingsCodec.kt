package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.settings.StoredAppSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json

/** Operations run synchronously inside the native lease's serialized access block. */
internal interface ScalarSettingsAccess {
    val allKeys: List<String>
    fun decodeString(key: String, default: String? = null): String?
    fun decodeDouble(key: String, default: Double): Double
    fun encodeString(key: String, value: String): Boolean
    fun encodeDouble(key: String, value: Double): Boolean
}

internal class ScalarSettingsCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        allowSpecialFloatingPointValues = true
    }

    fun load(access: ScalarSettingsAccess): StoredAppSettings =
        access.decodeString(CommittedSnapshot)?.let { decodeSnapshot(it) }
            ?: readLegacy(access)

    private fun decodeSnapshot(encoded: String): StoredAppSettings {
        // Older snapshots may omit fields. Only absence uses defaults; explicit values stay intact.
        val defaults = json.encodeToJsonElement(defaultSettings()).jsonObject
        val stored = json.parseToJsonElement(encoded).jsonObject
        return json.decodeFromJsonElement(JsonObject(defaults + stored))
    }

    fun save(access: ScalarSettingsAccess, settings: StoredAppSettings) {
        // Validate serialization before touching any persisted field.
        val snapshot = settings.copy(modelApiKeys = settings.modelApiKeys.toMap(), searchApiKeys = settings.searchApiKeys.toMap())
        val encoded = json.encodeToString(snapshot)
        if (access.decodeString(CommittedSnapshot) == null) {
            check(access.encodeString(CommittedSnapshot, json.encodeToString(readLegacy(access)))) {
                "Failed to preserve committed app settings with MMKV"
            }
        }
        // Retain the stable scalar schema, but publish only one complete snapshot.
        writeLegacy(access, snapshot)
        check(access.encodeString(CommittedSnapshot, encoded)) { "Failed to commit app settings with MMKV" }
    }

    private fun readLegacy(mmkv: ScalarSettingsAccess): StoredAppSettings {
        val defaults = defaultSettings()
        val provider = mmkv.decodeString(Provider, defaults.provider) ?: defaults.provider
        val storedApiKeys = mmkv.allKeys.filter { it.startsWith(ModelApiKeyPrefix) }.mapNotNull { key ->
            mmkv.decodeString(key)?.takeIf(String::isNotBlank)?.let { key.removePrefix(ModelApiKeyPrefix) to it }
        }.toMap()
        return StoredAppSettings(
            provider = provider,
            modelId = mmkv.decodeString(ModelId, defaults.modelId) ?: defaults.modelId,
            modelApiKeys = storedApiKeys,
            modelEndpoint = mmkv.decodeString(ModelEndpoint).orEmpty(),
            modelRegion = mmkv.decodeString(ModelRegion).orEmpty(),
            modelDeployment = mmkv.decodeString(ModelDeployment).orEmpty(),
            modelApiVersion = mmkv.decodeString(ModelApiVersion).orEmpty(),
            dashscopeRegion = mmkv.decodeString(DashscopeRegion, defaults.dashscopeRegion) ?: defaults.dashscopeRegion,
            webSearchApiKey = mmkv.decodeString(WebSearchApiKey).orEmpty(),
            exaSearchApiKey = mmkv.decodeString(ExaSearchApiKey).orEmpty(),
            searchApiKeys = mmkv.allKeys.filter { it.startsWith(SearchApiKeyPrefix) }.mapNotNull { key ->
                mmkv.decodeString(key)?.let { key.removePrefix(SearchApiKeyPrefix) to it }
            }.toMap(),
            webSearchProvider = mmkv.decodeString(WebSearchProvider)
                ?: if (mmkv.decodeString(WebSearchApiKey).isNullOrBlank()) defaults.webSearchProvider else "bright_data",
            temperature = mmkv.decodeDouble(Temperature, defaults.temperature),
            language = mmkv.decodeString(Language, defaults.language) ?: defaults.language,
            shellExecutionMode = mmkv.decodeString(ShellExecutionModeKey, defaults.shellExecutionMode)
                ?: defaults.shellExecutionMode,
            toolPermissionMode = mmkv.decodeString(ToolPermissionModeKey, defaults.toolPermissionMode)
                ?: defaults.toolPermissionMode,
        )
    }

    private fun writeLegacy(mmkv: ScalarSettingsAccess, settings: StoredAppSettings) {
        val writesSucceeded = listOf(
            mmkv.encodeString(Provider, settings.provider),
            mmkv.encodeString(ModelId, settings.modelId),
            mmkv.encodeString(ModelEndpoint, settings.modelEndpoint),
            mmkv.encodeString(ModelRegion, settings.modelRegion),
            mmkv.encodeString(ModelDeployment, settings.modelDeployment),
            mmkv.encodeString(ModelApiVersion, settings.modelApiVersion),
            mmkv.encodeString(DashscopeRegion, settings.dashscopeRegion),
            mmkv.encodeString(WebSearchApiKey, settings.webSearchApiKey),
            mmkv.encodeString(ExaSearchApiKey, settings.exaSearchApiKey),
            mmkv.encodeString(WebSearchProvider, settings.webSearchProvider),
            mmkv.encodeDouble(Temperature, settings.temperature),
            mmkv.encodeString(Language, settings.language),
            mmkv.encodeString(ShellExecutionModeKey, settings.shellExecutionMode),
            mmkv.encodeString(ToolPermissionModeKey, settings.toolPermissionMode),
        ).all { it } && (mmkv.allKeys.filter { it.startsWith(ModelApiKeyPrefix) }
            .map { it.removePrefix(ModelApiKeyPrefix) } + settings.modelApiKeys.keys).distinct().all { provider ->
            mmkv.encodeString(
                ModelApiKeyPrefix + provider,
                settings.modelApiKeys[provider].orEmpty(),
            )
        }
        val searchWritesSucceeded = (mmkv.allKeys.filter { it.startsWith(SearchApiKeyPrefix) }
            .map { it.removePrefix(SearchApiKeyPrefix) } + settings.searchApiKeys.keys).distinct().all { provider ->
            mmkv.encodeString(SearchApiKeyPrefix + provider, settings.searchApiKeys[provider].orEmpty())
        }
        check(writesSucceeded && searchWritesSucceeded) { "Failed to persist app settings with MMKV" }
    }

    private companion object {
        const val CommittedSnapshot = "settings_snapshot.v1"
        const val Provider = "model_provider"
        const val ModelId = "model_id"
        const val ModelApiKeyPrefix = "model_api_key."
        const val ModelEndpoint = "model_endpoint"
        const val ModelRegion = "model_region"
        const val ModelDeployment = "model_deployment"
        const val ModelApiVersion = "model_api_version"
        const val DashscopeRegion = "dashscope_region"
        const val WebSearchApiKey = "web_search_api_key"
        const val SearchApiKeyPrefix = "search_api_key."
        const val ExaSearchApiKey = "exa_search_api_key"
        const val WebSearchProvider = "web_search_provider"
        const val Temperature = "temperature"
        const val Language = "ui_language"
        const val ShellExecutionModeKey = "shell_execution_mode"
        const val ToolPermissionModeKey = "tool_permission_mode"
    }
}
