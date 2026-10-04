package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings


import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

fun interface SecretCodec {
    fun transform(value: String): String
}

class DataStoreAppSettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val protect: SecretCodec,
    private val reveal: SecretCodec,
    override val protection: SettingsProtection,
    private val active: () -> Boolean = { true },
) : AppSettingsStore {
    override suspend fun load(): StoredAppSettings {
        check(active()) { "settings storage is closed" }
        val values = dataStore.data.first()
        val defaults = defaultSettings()
        val provider = values[Provider] ?: defaults.provider
        val storedApiKeys = values.asMap().entries.mapNotNull { (key, rawValue) ->
            if (!key.name.startsWith(ModelApiKeyPrefix)) return@mapNotNull null
            (rawValue as? String)?.let(reveal::transform)?.takeIf(String::isNotBlank)
                ?.let { key.name.removePrefix(ModelApiKeyPrefix) to it }
        }.toMap()
        return StoredAppSettings(
            provider = provider,
            modelId = values[ModelId] ?: defaults.modelId,
            modelApiKeys = storedApiKeys,
            modelEndpoint = values[ModelEndpoint].orEmpty(),
            modelRegion = values[ModelRegion].orEmpty(),
            modelDeployment = values[ModelDeployment].orEmpty(),
            modelApiVersion = values[ModelApiVersion].orEmpty(),
            dashscopeRegion = values[DashscopeRegion] ?: defaults.dashscopeRegion,
            webSearchApiKey = values[WebSearchApiKey]?.let(reveal::transform).orEmpty(),
            exaSearchApiKey = values[ExaSearchApiKey]?.let(reveal::transform).orEmpty(),
            searchApiKeys = values.asMap().entries.mapNotNull { (key, rawValue) ->
                if (!key.name.startsWith(SearchApiKeyPrefix)) return@mapNotNull null
                (rawValue as? String)?.let(reveal::transform)?.let { key.name.removePrefix(SearchApiKeyPrefix) to it }
            }.toMap(),
            webSearchProvider = values[WebSearchProvider]
                ?: if (values[WebSearchApiKey].isNullOrBlank()) defaults.webSearchProvider else "bright_data",
            temperature = values[Temperature] ?: defaults.temperature,
            language = values[Language] ?: defaults.language,
            shellExecutionMode = values[ShellExecutionModeKey] ?: defaults.shellExecutionMode,
            toolPermissionMode = values[ToolPermissionModeKey]
                ?: values[LegacyShellPermissionModeKey]
                ?: defaults.toolPermissionMode,
        )
    }

    override suspend fun save(settings: StoredAppSettings) {
        check(active()) { "settings storage is closed" }
        dataStore.edit { values ->
            values[Provider] = settings.provider
            values[ModelId] = settings.modelId
            values[ModelEndpoint] = settings.modelEndpoint
            values[ModelRegion] = settings.modelRegion
            values[ModelDeployment] = settings.modelDeployment
            values[ModelApiVersion] = settings.modelApiVersion
            values[DashscopeRegion] = settings.dashscopeRegion
            values.asMap().keys.filter { it.name.startsWith(ModelApiKeyPrefix) }.forEach { values.remove(it) }
            settings.modelApiKeys.forEach { (provider, apiKey) ->
                values[modelApiKey(provider)] = protect.transform(apiKey)
            }
            values[WebSearchApiKey] = protect.transform(settings.webSearchApiKey)
            values[ExaSearchApiKey] = protect.transform(settings.exaSearchApiKey)
            values.asMap().keys.filter { it.name.startsWith(SearchApiKeyPrefix) }.forEach { values.remove(it) }
            settings.searchApiKeys.forEach { (provider, apiKey) ->
                values[stringPreferencesKey(SearchApiKeyPrefix + provider)] = protect.transform(apiKey)
            }
            values[WebSearchProvider] = settings.webSearchProvider
            values[Temperature] = settings.temperature
            values[Language] = settings.language
            values[ShellExecutionModeKey] = settings.shellExecutionMode
            values[ToolPermissionModeKey] = settings.toolPermissionMode
            values.remove(LegacyShellPermissionModeKey)
        }
    }

    private companion object {
        val Provider = stringPreferencesKey("model_provider")
        val ModelId = stringPreferencesKey("model_id")
        const val ModelApiKeyPrefix = "model_api_key."
        fun modelApiKey(provider: String) = stringPreferencesKey(ModelApiKeyPrefix + provider)
        val ModelEndpoint = stringPreferencesKey("model_endpoint")
        val ModelRegion = stringPreferencesKey("model_region")
        val ModelDeployment = stringPreferencesKey("model_deployment")
        val ModelApiVersion = stringPreferencesKey("model_api_version")
        val DashscopeRegion = stringPreferencesKey("dashscope_region")
        val WebSearchApiKey = stringPreferencesKey("web_search_api_key")
        const val SearchApiKeyPrefix = "search_api_key."
        val ExaSearchApiKey = stringPreferencesKey("exa_search_api_key")
        val WebSearchProvider = stringPreferencesKey("web_search_provider")
        val Temperature = doublePreferencesKey("temperature")
        val Language = stringPreferencesKey("ui_language")
        val ShellExecutionModeKey = stringPreferencesKey("shell_execution_mode")
        val ToolPermissionModeKey = stringPreferencesKey("tool_permission_mode")
        val LegacyShellPermissionModeKey = stringPreferencesKey("shell_permission_mode")
    }
}
