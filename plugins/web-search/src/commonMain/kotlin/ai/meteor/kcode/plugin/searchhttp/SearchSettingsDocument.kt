package ai.meteor.kcode.plugin.searchhttp

import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.tools.search.SearchSettingsConfiguration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Schema and legacy interpretation belong to the search feature, not the settings store. */
internal object SearchSettingsDocument {
    private const val Namespace = "feature.web-search"

    fun read(settings: StoredAppSettings): SearchSettingsConfiguration {
        val document = settings.namespaces[Namespace] ?: return readLegacy(settings.legacyValues)
        val provider = document["provider"]?.let { value ->
            require(value is JsonPrimitive && value.isString) { "Search provider must be a string" }
            value.content
        } ?: "google"
        val keys = document["apiKeys"]?.let { value ->
            require(value is JsonObject) { "Search credentials must be an object" }
            value.mapValues { (_, key) ->
                require(key is JsonPrimitive && key.isString) { "Search credentials must be strings" }
                key.content
            }
        }.orEmpty()
        return SearchSettingsConfiguration(provider, keys)
    }

    private fun readLegacy(document: JsonObject): SearchSettingsConfiguration {
        fun string(field: String): String? = document[field]?.let { value ->
            require(value is JsonPrimitive && value.isString) { "Legacy search $field must be a string" }
            value.content
        }
        val legacyKey = string("webSearchApiKey").orEmpty()
        val provider = string("webSearchProvider") ?: if (legacyKey.isNotBlank()) "bright_data" else "google"
        val keys = document["searchApiKeys"]?.let { value ->
            require(value is JsonObject) { "Legacy search credentials must be an object" }
            value.mapValues { (_, key) ->
                require(key is JsonPrimitive && key.isString) { "Legacy search credentials must be strings" }
                key.content
            }
        }.orEmpty()
        return SearchSettingsConfiguration(provider.ifEmpty { "google" },
            mapOf("exa" to string("exaSearchApiKey").orEmpty(), "bright_data" to legacyKey) + keys)
    }

    fun write(settings: StoredAppSettings, configuration: SearchSettingsConfiguration): StoredAppSettings {
        val previous = settings.namespaces[Namespace].orEmpty()
        val document = JsonObject(previous + mapOf(
            "provider" to JsonPrimitive(configuration.provider),
            "apiKeys" to JsonObject(configuration.apiKeys.mapValues { (_, key) -> JsonPrimitive(key) }),
        ))
        return settings.copy(namespaces = settings.namespaces + (Namespace to document))
    }
}
