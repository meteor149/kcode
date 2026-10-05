package ai.meteor.kcode.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Opaque field differences; map keys remain complete identities, including dots. */
class SettingsPatch private constructor(private val changes: List<Change>) {
    private data class Change(
        val path: List<String>,
        val value: JsonElement?,
        val preserveExistingObject: Boolean = false,
    )

    fun apply(settings: StoredAppSettings): StoredAppSettings {
        fun replace(
            node: JsonObject,
            path: List<String>,
            value: JsonElement?,
            preserveExistingObject: Boolean,
        ): JsonObject {
            val values = node.toMutableMap()
            val key = path.first()
            if (path.size == 1) {
                if (value == null) values.remove(key)
                else if (!preserveExistingObject || values[key] !is JsonObject) values[key] = value
            } else {
                values[key] = replace(
                    values[key] as? JsonObject ?: JsonObject(emptyMap()),
                    path.drop(1),
                    value,
                    preserveExistingObject,
                )
            }
            return JsonObject(values)
        }
        val original = codec.encodeToJsonElement(StoredAppSettings.serializer(), settings) as JsonObject
        val updated = changes.fold(original) { current, change ->
            replace(current, change.path, change.value, change.preserveExistingObject)
        }
        return codec.decodeFromJsonElement(StoredAppSettings.serializer(), updated)
    }

    companion object {
        private val codec = Json { encodeDefaults = true }

        fun between(before: StoredAppSettings, after: StoredAppSettings): SettingsPatch {
            val changes = mutableListOf<Change>()
            fun compare(path: List<String>, old: JsonElement?, new: JsonElement?) {
                if (old == new) return
                if (old is JsonObject && new is JsonObject) {
                    (old.keys + new.keys).forEach { key -> compare(path + key, old[key], new[key]) }
                } else if (old == null && new is JsonObject) {
                    // A draft creating an object owns only its fields, not concurrent additions.
                    // Keep an empty object visible without erasing an already-created document.
                    changes += Change(path, JsonObject(emptyMap()), preserveExistingObject = true)
                    new.forEach { (key, value) -> compare(path + key, null, value) }
                } else changes += Change(path, new)
            }
            compare(emptyList(), codec.encodeToJsonElement(StoredAppSettings.serializer(), before),
                codec.encodeToJsonElement(StoredAppSettings.serializer(), after))
            return SettingsPatch(changes.toList())
        }
    }
}
