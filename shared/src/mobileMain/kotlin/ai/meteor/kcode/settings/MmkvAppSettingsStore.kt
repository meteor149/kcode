package ai.meteor.kcode.settings

import ai.meteor.kcode.model.ModelProvider
import com.tencent.mmkv.kmp.MMKV

/** Android and iOS settings backed by the same MMKV schema. */
class MmkvAppSettingsStore(
    private val mmkv: MMKV,
    override val protection: SettingsProtection,
) : AppSettingsStore {
    override suspend fun load(): StoredAppSettings = readSnapshot()

    override suspend fun save(settings: StoredAppSettings) {
        writeSnapshot(settings)
    }

    private fun readSnapshot(): StoredAppSettings {
        val provider = mmkv.decodeString(Provider, "OpenAI") ?: "OpenAI"
        val storedApiKeys = ModelProvider.entries.mapNotNull { modelProvider ->
            mmkv.decodeString(ModelApiKeyPrefix + modelProvider.name)
                ?.takeIf(String::isNotBlank)
                ?.let { modelProvider.name to it }
        }.toMap()
        return StoredAppSettings(
            provider = provider,
            modelId = mmkv.decodeString(ModelId, "gpt-4o-mini") ?: "gpt-4o-mini",
            modelApiKeys = storedApiKeys,
            modelEndpoint = mmkv.decodeString(ModelEndpoint).orEmpty(),
            modelRegion = mmkv.decodeString(ModelRegion).orEmpty(),
            modelDeployment = mmkv.decodeString(ModelDeployment).orEmpty(),
            modelApiVersion = mmkv.decodeString(ModelApiVersion).orEmpty(),
            dashscopeRegion = mmkv.decodeString(DashscopeRegion, "china_mainland") ?: "china_mainland",
            webSearchApiKey = mmkv.decodeString(WebSearchApiKey).orEmpty(),
            exaSearchApiKey = mmkv.decodeString(ExaSearchApiKey).orEmpty(),
            webSearchProvider = mmkv.decodeString(WebSearchProvider)
                ?: if (mmkv.decodeString(WebSearchApiKey).isNullOrBlank()) "google" else "bright_data",
            temperature = mmkv.decodeDouble(Temperature, 0.7),
            language = mmkv.decodeString(Language, "zh") ?: "zh",
            shellExecutionMode = mmkv.decodeString(ShellExecutionModeKey, ShellExecutionMode.App.code)
                ?: ShellExecutionMode.App.code,
            toolPermissionMode = mmkv.decodeString(ToolPermissionModeKey, ToolPermissionMode.Ask.code)
                ?: ToolPermissionMode.Ask.code,
        )
    }

    private fun writeSnapshot(settings: StoredAppSettings) {
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
        ).all { it } && ModelProvider.entries.all { provider ->
            mmkv.encodeString(
                ModelApiKeyPrefix + provider.name,
                settings.modelApiKeys[provider.name].orEmpty(),
            )
        }
        check(writesSucceeded) { "Failed to persist app settings with MMKV" }
    }

    private companion object {
        const val Provider = "model_provider"
        const val ModelId = "model_id"
        const val ModelApiKeyPrefix = "model_api_key."
        const val ModelEndpoint = "model_endpoint"
        const val ModelRegion = "model_region"
        const val ModelDeployment = "model_deployment"
        const val ModelApiVersion = "model_api_version"
        const val DashscopeRegion = "dashscope_region"
        const val WebSearchApiKey = "web_search_api_key"
        const val ExaSearchApiKey = "exa_search_api_key"
        const val WebSearchProvider = "web_search_provider"
        const val Temperature = "temperature"
        const val Language = "ui_language"
        const val ShellExecutionModeKey = "shell_execution_mode"
        const val ToolPermissionModeKey = "tool_permission_mode"
    }
}
