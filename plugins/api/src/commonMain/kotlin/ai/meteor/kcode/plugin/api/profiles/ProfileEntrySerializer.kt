package ai.meteor.kcode.plugin.api.profiles

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** A present JSON null config is an override; an absent field requests the module default. */
internal object ProfileEntrySerializer : KSerializer<ProfileEntry> {
    override val descriptor: SerialDescriptor get() = ProfileEntryMetadata.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ProfileEntry) {
        val json = encoder as? JsonEncoder ?: error("Profile entries require JSON")
        val metadata = ProfileEntryMetadata(value.id, value.packageId, value.enabled, value.children,
            value.configurationKind, value.inject, value.intercept, value.isolate)
        val fields = (json.json.encodeToJsonElement(metadata) as JsonObject).toMutableMap().apply { remove("config") }
        value.config?.let { fields["config"] = it }
        json.encodeJsonElement(JsonObject(fields))
    }

    override fun deserialize(decoder: Decoder): ProfileEntry {
        val json = decoder as? JsonDecoder ?: error("Profile entries require JSON")
        val fields = json.decodeJsonElement() as? JsonObject ?: error("Profile entry must be an object")
        val metadata = json.json.decodeFromJsonElement<ProfileEntryMetadata>(JsonObject(fields - "config"))
        return ProfileEntry(metadata.id, metadata.packageId, fields["config"], metadata.enabled,
            metadata.children, metadata.configurationKind, metadata.inject, metadata.intercept, metadata.isolate)
    }
}

@Serializable
@kotlinx.serialization.SerialName("ai.meteor.kcode.plugin.api.profiles.ProfileEntry")
private data class ProfileEntryMetadata(
    val id: String,
    val packageId: String,
    val enabled: Boolean = true,
    val children: List<ProfileEntry>? = null,
    val configurationKind: String = "json",
    val inject: Map<String, JsonElement> = emptyMap(),
    val intercept: Map<String, JsonElement> = emptyMap(),
    val isolate: Map<String, String?> = emptyMap(),
    val config: JsonElement? = null,
)
