package ai.meteor.kcode.settings

import kotlinx.serialization.Serializable

/** A platform-neutral snapshot. Empty values do not select a product default or provider. */
@Serializable
data class StoredAppSettings(
    val provider: String = "",
    val modelId: String = "",
    val modelApiKeys: Map<String, String> = emptyMap(),
    val modelEndpoint: String = "",
    val modelRegion: String = "",
    val modelDeployment: String = "",
    val modelApiVersion: String = "",
    val dashscopeRegion: String = "",
    val webSearchApiKey: String = "",
    val exaSearchApiKey: String = "",
    val searchApiKeys: Map<String, String> = emptyMap(),
    val webSearchProvider: String = "",
    val temperature: Double = 0.0,
    val language: String = "",
    val shellExecutionMode: String = "",
    val toolPermissionMode: String = "",
)

enum class ShellExecutionMode(val code: String) {
    App("app"),
    Adb("adb"),
    Root("root");

    companion object {
        fun fromCode(code: String): ShellExecutionMode? = entries.firstOrNull { it.code == code }
    }
}

enum class ToolPermissionMode(val code: String) {
    Deny("deny"),
    Ask("ask"),
    Bypass("bypass");

    companion object {
        fun fromCode(code: String): ToolPermissionMode? = entries.firstOrNull { it.code == code }
    }
}

enum class SettingsProtection {
    AndroidKeystore,
    DesktopAppData,
    Transient,
}

interface AppSettingsStore {
    val protection: SettingsProtection

    suspend fun load(): StoredAppSettings

    suspend fun save(settings: StoredAppSettings)
}
