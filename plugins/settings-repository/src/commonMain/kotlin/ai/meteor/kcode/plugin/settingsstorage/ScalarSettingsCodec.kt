package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.settings.StoredAppSettings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Operations run synchronously inside the native lease's serialized access block. */
internal interface ScalarSettingsAccess {
    val allKeys: List<String>
    fun decodeString(key: String, default: String? = null): String?
    fun decodeDouble(key: String, default: Double): Double
    fun encodeString(key: String, value: String): Boolean
    fun encodeDouble(key: String, value: Double): Boolean
}

internal class ScalarSettingsCodec {
    fun load(access: ScalarSettingsAccess): StoredAppSettings {
        val keys = access.allKeys.toSet()
        if (CommittedSnapshot in keys) {
            return SettingsSnapshotCodec.decode(requireNotNull(access.decodeString(CommittedSnapshot)))
        }
        if (LegacySnapshot in keys) {
            return SettingsSnapshotCodec.migrateV1(requireNotNull(access.decodeString(LegacySnapshot)))
        }
        return readLegacy(access)
    }

    fun save(access: ScalarSettingsAccess, settings: StoredAppSettings) {
        val encoded = SettingsSnapshotCodec.encode(settings)
        check(access.encodeString(CommittedSnapshot, encoded)) { "Failed to commit app settings with MMKV" }
    }

    private fun readLegacy(access: ScalarSettingsAccess): StoredAppSettings {
        val keys = access.allKeys.toSet()
        val values = LegacyScalarFields.mapNotNull { (key, field) ->
            if (key !in keys) null else field to JsonPrimitive(requireNotNull(access.decodeString(key)))
        }.toMap().toMutableMap<String, JsonElement>()
        if ("temperature" in keys) values["temperature"] = JsonPrimitive(access.decodeDouble("temperature", 0.0))
        for ((prefix, field) in listOf("model_api_key." to "modelApiKeys", "search_api_key." to "searchApiKeys")) {
            val credentials = keys.filter { it.startsWith(prefix) }.associate { key ->
                key.removePrefix(prefix) to JsonPrimitive(requireNotNull(access.decodeString(key)))
            }
            if (credentials.isNotEmpty()) values[field] = JsonObject(credentials)
        }
        return StoredAppSettings(legacyValues = JsonObject(values))
    }

    private companion object {
        const val CommittedSnapshot = "settings_snapshot.v2"
        const val LegacySnapshot = "settings_snapshot.v1"
    }
}

/** Bounded historical name translation only: no defaults, provider selection, or new writes. */
internal val LegacyScalarFields = mapOf(
    "model_provider" to "provider", "model_id" to "modelId",
    "model_endpoint" to "modelEndpoint", "model_region" to "modelRegion",
    "model_deployment" to "modelDeployment", "model_api_version" to "modelApiVersion",
    "dashscope_region" to "dashscopeRegion", "web_search_api_key" to "webSearchApiKey",
    "exa_search_api_key" to "exaSearchApiKey", "web_search_provider" to "webSearchProvider",
    "ui_language" to "language", "shell_execution_mode" to "shellExecutionMode",
    "tool_permission_mode" to "toolPermissionMode",
)
