package app.kcode.settings

import kotlinx.browser.localStorage

object WebAppSettingsStore : AppSettingsStore {
    private const val Prefix = "kcode.settings."

    override val protection = SettingsProtection.BrowserLocalStorage

    override suspend fun load(): StoredAppSettings {
        return StoredAppSettings(
            provider = localStorage.getItem(Prefix + "provider") ?: "OpenAI",
            modelId = localStorage.getItem(Prefix + "modelId") ?: "gpt-4o-mini",
            apiKey = localStorage.getItem(Prefix + "apiKey").orEmpty(),
            modelEndpoint = localStorage.getItem(Prefix + "modelEndpoint").orEmpty(),
            modelRegion = localStorage.getItem(Prefix + "modelRegion").orEmpty(),
            modelDeployment = localStorage.getItem(Prefix + "modelDeployment").orEmpty(),
            modelApiVersion = localStorage.getItem(Prefix + "modelApiVersion").orEmpty(),
            webSearchApiKey = localStorage.getItem(Prefix + "webSearchApiKey").orEmpty(),
            exaSearchApiKey = localStorage.getItem(Prefix + "exaSearchApiKey").orEmpty(),
            webSearchProvider = localStorage.getItem(Prefix + "webSearchProvider")
                ?: if (localStorage.getItem(Prefix + "webSearchApiKey").isNullOrBlank()) "google" else "bright_data",
            temperature = localStorage.getItem(Prefix + "temperature")?.toDoubleOrNull() ?: 0.6,
            language = localStorage.getItem(Prefix + "language") ?: "zh",
            shellExecutionMode = localStorage.getItem(Prefix + "shellExecutionMode") ?: ShellExecutionMode.App.code,
            toolPermissionMode = localStorage.getItem(Prefix + "toolPermissionMode")
                ?: localStorage.getItem(Prefix + "shellPermissionMode")
                ?: ToolPermissionMode.Ask.code,
        )
    }

    override suspend fun save(settings: StoredAppSettings) {
        localStorage.setItem(Prefix + "provider", settings.provider)
        localStorage.setItem(Prefix + "modelId", settings.modelId)
        localStorage.setItem(Prefix + "apiKey", settings.apiKey)
        localStorage.setItem(Prefix + "modelEndpoint", settings.modelEndpoint)
        localStorage.setItem(Prefix + "modelRegion", settings.modelRegion)
        localStorage.setItem(Prefix + "modelDeployment", settings.modelDeployment)
        localStorage.setItem(Prefix + "modelApiVersion", settings.modelApiVersion)
        localStorage.setItem(Prefix + "webSearchApiKey", settings.webSearchApiKey)
        localStorage.setItem(Prefix + "exaSearchApiKey", settings.exaSearchApiKey)
        localStorage.setItem(Prefix + "webSearchProvider", settings.webSearchProvider)
        localStorage.setItem(Prefix + "temperature", settings.temperature.toString())
        localStorage.setItem(Prefix + "language", settings.language)
        localStorage.setItem(Prefix + "shellExecutionMode", settings.shellExecutionMode)
        localStorage.setItem(Prefix + "toolPermissionMode", settings.toolPermissionMode)
        localStorage.removeItem(Prefix + "shellPermissionMode")
    }
}
