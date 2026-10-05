package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.settings.AppSettingsStore
import ai.meteor.kcode.settings.SettingsProtection
import ai.meteor.kcode.settings.StoredAppSettings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun interface SecretCodec { fun transform(value: String): String }

class DataStoreAppSettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val protect: SecretCodec,
    private val reveal: SecretCodec,
    override val protection: SettingsProtection,
    private val active: () -> Boolean = { true },
) : AppSettingsStore {
    override suspend fun load(): StoredAppSettings {
        check(active()) { "settings storage is closed" }
        val values = dataStore.data.first().asMap().mapKeys { (key, _) -> key.name }
        if (CommittedSnapshot.name in values) return SettingsSnapshotCodec.decode(
            reveal.transform(requireNotNull(values[CommittedSnapshot.name] as? String)),
        )
        if (LegacySnapshot in values) return SettingsSnapshotCodec.migrateV1(
            reveal.transform(requireNotNull(values[LegacySnapshot] as? String)),
        )
        val legacy = LegacyScalarFields.mapNotNull { (key, field) ->
            if (key !in values) null else {
                val raw = requireNotNull(values[key] as? String)
                field to JsonPrimitive(if (key in LegacySecrets) reveal.transform(raw) else raw)
            }
        }.toMap().toMutableMap<String, JsonElement>()
        if ("temperature" in values) legacy["temperature"] = JsonPrimitive(requireNotNull(values["temperature"] as? Double))
        if ("toolPermissionMode" !in legacy && "shell_permission_mode" in values) {
            legacy["toolPermissionMode"] = JsonPrimitive(requireNotNull(values["shell_permission_mode"] as? String))
        }
        for ((prefix, field) in listOf("model_api_key." to "modelApiKeys", "search_api_key." to "searchApiKeys")) {
            val credentials = values.filterKeys { it.startsWith(prefix) }.map { (key, raw) ->
                key.removePrefix(prefix) to JsonPrimitive(reveal.transform(requireNotNull(raw as? String)))
            }.toMap()
            if (credentials.isNotEmpty()) legacy[field] = JsonObject(credentials)
        }
        return StoredAppSettings(legacyValues = JsonObject(legacy))
    }

    override suspend fun save(settings: StoredAppSettings) {
        check(active()) { "settings storage is closed" }
        // Protect every value, including opaque namespaces; publish a single atomic durable edit.
        val encoded = protect.transform(SettingsSnapshotCodec.encode(settings))
        dataStore.edit { values -> values[CommittedSnapshot] = encoded }
    }

    private companion object {
        val CommittedSnapshot = stringPreferencesKey("settings_snapshot.v2")
        const val LegacySnapshot = "settings_snapshot.v1"
        val LegacySecrets = setOf("web_search_api_key", "exa_search_api_key")
    }
}
