package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.settings.StoredAppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Versioned envelope migration preserves raw legacy JSON, including fields unknown to this build. */
internal object SettingsSnapshotCodec {
    private val json = Json { encodeDefaults = true; allowSpecialFloatingPointValues = true }

    fun encode(settings: StoredAppSettings): String = json.encodeToJsonElement(settings.snapshot()).toString()

    fun decode(encoded: String): StoredAppSettings = json.decodeFromJsonElement(json.parseToJsonElement(encoded))

    fun migrateV1(encoded: String): StoredAppSettings {
        val document = json.parseToJsonElement(encoded)
        require(document is JsonObject) { "Legacy settings snapshot must be an object" }
        val namespaces = document["namespaces"]?.let { value ->
            require(value is JsonObject && value.values.all { it is JsonObject }) { "Settings namespaces must contain objects" }
            value.mapValues { (_, namespace) -> namespace as JsonObject }
        }.orEmpty()
        return StoredAppSettings(namespaces, JsonObject(document - "namespaces"))
    }
}
