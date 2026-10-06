package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import ai.meteor.kcode.plugin.api.profiles.ProfileDataScope
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Exchange data excludes machine overlays, artifact paths, runtime composition and business data. */
@Serializable
data class PortableProfileDocument(
    val formatVersion: Int = 1,
    val definition: ProfileDefinition,
    val bundles: List<ProfileBundle>,
    val lock: ProfileLock,
) {
    fun validate() {
        require(formatVersion == 1) { "Unsupported portable Profile format" }
        definition.validate()
        lock.validate()
        require(bundles.map { it.id }.toSet() == definition.bundles.map { it.id }.toSet()) {
            "Portable Profile must contain exactly its declared bundles"
        }
        ProfileCompiler().compile(definition, bundles).requireValid()
    }
}

/**
 * Host-owned export review for each opaque configuration field. Null denies publication.
 * Implementations must use feature schemas and remove credentials and machine addresses;
 * a key-name heuristic or unconditional identity policy is not a safe default.
 */
data class ProfileExportValue(
    val packageId: String,
    val entryId: String,
    val configurationKind: String,
    val location: String,
    val field: String,
    val value: JsonElement,
)

fun interface ProfileExportReview {
    fun review(value: ProfileExportValue): JsonElement?
}

/** Detached host policy selection. Unknown module identities remain denied. */
class ProfileExportPolicies(policies: Map<String, ProfileExportReview>) : ProfileExportReview {
    private val policies = policies.toMap().also {
        require(it.keys.all(String::isNotBlank)) { "Export policy module identities must not be blank" }
    }

    override fun review(value: ProfileExportValue): JsonElement? = policies[value.packageId]?.review(value)
}

/** Pure exchange boundary: no module resolution, resource allocation or repository mutation. */
class ProfilePortableExporter(
    private val review: ProfileExportReview = ProfileExportReview { null },
) {
    fun export(source: CommittedProfileGeneration): String {
        source.validate(restoring = true)
        require(source.composition.external.all { it.packageInstallation != null }) {
            "Legacy local plugin descriptors require verified package archives before export"
        }
        val session = ProfileExportSession(review, source.definition)
        val available = source.bundles.associateBy { it.id }
        val bundles = source.definition.bundles.map { reference ->
            val bundle = available.getValue(reference.id)
            bundle.copy(patches = session.operations(bundle.patches, "bundle:${bundle.id}@${bundle.version}"))
        }
        val definition = source.definition.copy(
            patches = session.operations(source.definition.patches, "profile"),
            dataScope = ProfileDataScope(workspace = "profile"),
        )
        val document = PortableProfileDocument(definition = definition, bundles = bundles, lock = source.lock)
        document.validate()
        return json.encodeToString(document)
    }

    companion object {
        private val json = Json { prettyPrint = true; encodeDefaults = true }

        /** Parsing is not authorization to install packages or activate the imported document. */
        fun decode(text: String): PortableProfileDocument =
            json.decodeFromString<PortableProfileDocument>(text).also { it.validate() }
    }
}
