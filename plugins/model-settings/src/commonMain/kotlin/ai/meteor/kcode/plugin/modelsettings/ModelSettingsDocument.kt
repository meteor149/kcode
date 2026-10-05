package ai.meteor.kcode.plugin.modelsettings

import ai.meteor.kcode.settings.StoredAppSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
internal data class ModelSettingsValues(
    val provider: String = "OpenAI",
    val modelId: String = "gpt-4o-mini",
    val modelApiKeys: Map<String, String> = emptyMap(),
    val modelEndpoint: String = "",
    val modelRegion: String = "",
    val modelDeployment: String = "",
    val modelApiVersion: String = "",
    val dashscopeRegion: String = "china_mainland",
    val temperature: Double = 0.7,
)

/** Partial configuration, schema defaults and legacy interpretation are feature-owned. */
internal object ModelSettingsDocument {
    private const val Namespace = "feature.model-settings"
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        allowSpecialFloatingPointValues = true
    }

    fun read(settings: StoredAppSettings): ModelSettingsValues {
        val document = settings.namespaces[Namespace] ?: settings.legacyValues
        for (field in listOf(
            "provider", "modelId", "modelEndpoint", "modelRegion",
            "modelDeployment", "modelApiVersion", "dashscopeRegion",
        )) {
            document[field]?.let { value ->
                require(value is JsonPrimitive && value.isString) { "Model $field must be a string" }
            }
        }
        document["modelApiKeys"]?.let { value ->
            require(value is JsonObject) { "Model credentials must be an object" }
            require(value.values.all { it is JsonPrimitive && it.isString }) { "Model credentials must be strings" }
        }
        document["temperature"]?.let { value ->
            require(value is JsonPrimitive && !value.isString && value.content.toDoubleOrNull() != null) {
                "Model temperature must be a number"
            }
        }
        return json.decodeFromJsonElement(document)
    }

    fun write(settings: StoredAppSettings, values: ModelSettingsValues): StoredAppSettings {
        val encoded = json.encodeToJsonElement(values) as JsonObject
        val document = JsonObject(settings.namespaces[Namespace].orEmpty() + encoded)
        return settings.copy(namespaces = settings.namespaces + (Namespace to document))
    }
}
