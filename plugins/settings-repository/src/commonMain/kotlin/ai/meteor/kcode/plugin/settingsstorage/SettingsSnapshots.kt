package ai.meteor.kcode.plugin.settingsstorage

import ai.meteor.kcode.settings.StoredAppSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Detach caller-owned containers, including nested opaque feature documents. */
internal fun StoredAppSettings.snapshot(): StoredAppSettings = copy(
    legacyValues = legacyValues.snapshot() as JsonObject,
    namespaces = namespaces.mapValues { (_, document) -> document.snapshot() as JsonObject },
)

private fun JsonElement.snapshot(): JsonElement = when (this) {
    is JsonObject -> JsonObject(mapValues { (_, value) -> value.snapshot() })
    is JsonArray -> JsonArray(map { it.snapshot() })
    else -> this
}
