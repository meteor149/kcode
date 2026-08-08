package app.kcode.settings

/** A stable, platform-neutral snapshot. New settings can be added with defaults. */
data class StoredAppSettings(
    val provider: String = "OpenAI",
    val modelId: String = "gpt-4o-mini",
    val apiKey: String = "",
    val modelEndpoint: String = "",
    val modelRegion: String = "",
    val modelDeployment: String = "",
    val modelApiVersion: String = "",
    val webSearchApiKey: String = "",
    val exaSearchApiKey: String = "",
    val webSearchProvider: String = "google",
    val temperature: Double = 0.7,
    val language: String = "zh",
    val shellExecutionMode: String = ShellExecutionMode.App.code,
    val toolPermissionMode: String = ToolPermissionMode.Ask.code,
)

enum class ShellExecutionMode(val code: String) {
    App("app"),
    Adb("adb"),
    Root("root");

    companion object {
        fun fromCode(code: String): ShellExecutionMode = entries.firstOrNull { it.code == code } ?: App
    }
}

enum class ToolPermissionMode(val code: String) {
    Deny("deny"),
    Ask("ask"),
    Bypass("bypass");

    companion object {
        fun fromCode(code: String): ToolPermissionMode = entries.firstOrNull { it.code == code } ?: Ask
    }
}

enum class SettingsProtection {
    AndroidKeystore,
    DesktopAppData,
    BrowserLocalStorage,
    IosKeychain,
    Transient,
}

interface AppSettingsStore {
    val protection: SettingsProtection

    suspend fun load(): StoredAppSettings

    suspend fun save(settings: StoredAppSettings)
}

object TransientAppSettingsStore : AppSettingsStore {
    override val protection = SettingsProtection.Transient
    private var value = StoredAppSettings()

    override suspend fun load(): StoredAppSettings = value

    override suspend fun save(settings: StoredAppSettings) {
        value = settings
    }
}
