package ai.meteor.kcode.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Opaque platform-neutral configuration. Feature providers own schemas and defaults. */
@Serializable
data class StoredAppSettings(
    /** Feature-owned JSON objects; unknown namespaces and fields survive ordinary updates. */
    val namespaces: Map<String, JsonObject> = emptyMap(),
    /** Opaque historical values. Only feature providers interpret their legacy schema. */
    val legacyValues: JsonObject = JsonObject(emptyMap()),
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

    /** Published settings services serialize this complete read/validate/commit operation. */
    suspend fun <T> transaction(block: suspend SettingsTransaction.() -> T): T =
        error("Use the transaction-capable store published by KcodeSettings")
}

/** A single-use commit, valid only while its transaction callback is executing. */
interface SettingsTransaction {
    val current: StoredAppSettings
    suspend fun commit(settings: StoredAppSettings)
}
