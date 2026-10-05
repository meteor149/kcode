package ai.meteor.kcode.plugin.profiles

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Portable user intent. Resolved artifacts and host objects never belong here. */
@Serializable
data class ProfileDefinition(
    val formatVersion: Int = 1,
    val id: String,
    val displayName: String = id,
    val bundles: List<ProfileBundleReference> = emptyList(),
    val patches: List<ProfileOperation> = emptyList(),
    val dataScope: ProfileDataScope = ProfileDataScope(),
) {
    fun validate() {
        require(formatVersion == 1) { "Unsupported profile format: $formatVersion" }
        require(id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")) && id != "." && id != "..") { "Invalid profile id" }
        require(displayName.isNotBlank()) { "Profile display name must not be blank" }
        require(bundles.map { it.id }.distinct().size == bundles.size) { "Duplicate profile bundle" }
        bundles.forEach { require(it.id.isNotBlank() && it.version.isNotBlank()) { "Incomplete bundle reference" } }
        dataScope.validate()
    }
}

@Serializable
data class ProfileBundleReference(val id: String, val version: String)

/** Logical scope identifiers, resolved by the host rather than interpreted as paths. */
@Serializable
data class ProfileDataScope(
    val settings: String = "profile",
    val history: String = "profile",
    val workspace: String = "default",
) {
    fun validate() {
        listOf(settings, history, workspace).forEach {
            require(it.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}"))) { "Invalid data scope id" }
        }
    }
}

@Serializable
data class ProfileEntry(
    val id: String,
    val packageId: String,
    val config: JsonElement? = null,
    val enabled: Boolean = true,
    val children: List<ProfileEntry>? = null,
    val configurationKind: String = "json",
    val inject: Map<String, JsonElement> = emptyMap(),
    val intercept: Map<String, JsonElement> = emptyMap(),
    /** Null is a local realm; a nonempty label shares that named realm. */
    val isolate: Map<String, String?> = emptyMap(),
)

@Serializable
sealed interface ProfileOperation {
    @Serializable
    @SerialName("insert")
    data class Insert(val entries: List<ProfileEntry>, val parent: String? = null) : ProfileOperation

    @Serializable
    @SerialName("configure")
    data class Configure(val target: String, val config: JsonElement, val configurationKind: String = "json") : ProfileOperation

    @Serializable
    @SerialName("enable")
    data class Enable(val target: String) : ProfileOperation

    @Serializable
    @SerialName("disable")
    data class Disable(val target: String) : ProfileOperation

    @Serializable
    @SerialName("replace")
    data class Replace(val target: String, val packageId: String, val expectedPackageId: String? = null) : ProfileOperation

    @Serializable
    @SerialName("remove")
    data class Remove(val target: String) : ProfileOperation
}

@Serializable
data class ProfileBundle(
    val formatVersion: Int = 1,
    val id: String,
    val version: String,
    val patches: List<ProfileOperation>,
)
