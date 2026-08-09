package app.kcode.settings

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
) : AppSettingsStore {
    override suspend fun load(): StoredAppSettings {
        val values = dataStore.data.first()
        return StoredAppSettings(
            provider = values[Provider] ?: "OpenAI",
            modelId = values[ModelId] ?: "gpt-4o-mini",
            apiKey = values[ApiKey]?.let(reveal::transform).orEmpty(),
            modelEndpoint = values[ModelEndpoint].orEmpty(),
            modelRegion = values[ModelRegion].orEmpty(),
            modelDeployment = values[ModelDeployment].orEmpty(),
            modelApiVersion = values[ModelApiVersion].orEmpty(),
            webSearchApiKey = values[WebSearchApiKey]?.let(reveal::transform).orEmpty(),
            exaSearchApiKey = values[ExaSearchApiKey]?.let(reveal::transform).orEmpty(),
            webSearchProvider = values[WebSearchProvider]
                ?: if (values[WebSearchApiKey].isNullOrBlank()) "google" else "bright_data",
            temperature = values[Temperature] ?: 0.7,
            language = values[Language] ?: "zh",
            shellExecutionMode = values[ShellExecutionModeKey] ?: ShellExecutionMode.App.code,
            toolPermissionMode = values[ToolPermissionModeKey]
                ?: values[LegacyShellPermissionModeKey]
                ?: ToolPermissionMode.Ask.code,
        )
    }

    override suspend fun save(settings: StoredAppSettings) {
        dataStore.edit { values ->
            values[Provider] = settings.provider
            values[ModelId] = settings.modelId
            values[ApiKey] = protect.transform(settings.apiKey)
            values[ModelEndpoint] = settings.modelEndpoint
            values[ModelRegion] = settings.modelRegion
            values[ModelDeployment] = settings.modelDeployment
            values[ModelApiVersion] = settings.modelApiVersion
            values[WebSearchApiKey] = protect.transform(settings.webSearchApiKey)
            values[ExaSearchApiKey] = protect.transform(settings.exaSearchApiKey)
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
        val ApiKey = stringPreferencesKey("api_key")
        val ModelEndpoint = stringPreferencesKey("model_endpoint")
        val ModelRegion = stringPreferencesKey("model_region")
        val ModelDeployment = stringPreferencesKey("model_deployment")
        val ModelApiVersion = stringPreferencesKey("model_api_version")
        val WebSearchApiKey = stringPreferencesKey("web_search_api_key")
        val ExaSearchApiKey = stringPreferencesKey("exa_search_api_key")
        val WebSearchProvider = stringPreferencesKey("web_search_provider")
        val Temperature = doublePreferencesKey("temperature")
        val Language = stringPreferencesKey("ui_language")
        val ShellExecutionModeKey = stringPreferencesKey("shell_execution_mode")
        val ToolPermissionModeKey = stringPreferencesKey("tool_permission_mode")
        val LegacyShellPermissionModeKey = stringPreferencesKey("shell_permission_mode")
    }
}
