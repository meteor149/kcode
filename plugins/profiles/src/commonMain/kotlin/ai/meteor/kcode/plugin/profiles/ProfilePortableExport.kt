package ai.meteor.kcode.plugin.profiles

import ai.meteor.kcode.plugin.api.profiles.ProfileBundle
import ai.meteor.kcode.plugin.api.profiles.ProfileDataScope
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileEntry
import ai.meteor.kcode.plugin.api.profiles.ProfileOperation
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
fun interface ProfileExportReview {
    fun review(location: String, field: String, value: JsonElement): JsonElement?
}

/** Pure exchange boundary: no module resolution, resource allocation or repository mutation. */
class ProfilePortableExporter(
    private val review: ProfileExportReview = ProfileExportReview { _, _, _ -> null },
) {
    fun export(source: CommittedProfileGeneration): String {
        source.validate(restoring = true)
        val definition = source.definition.copy(
            patches = operations(source.definition.patches, "profile"),
            dataScope = ProfileDataScope(workspace = "profile"),
        )
        val bundles = source.bundles.map { bundle ->
            bundle.copy(patches = operations(bundle.patches, "bundle:${bundle.id}@${bundle.version}"))
        }
        val document = PortableProfileDocument(definition = definition, bundles = bundles, lock = source.lock)
        document.validate()
        return json.encodeToString(document)
    }

    private fun reviewed(location: String, field: String, value: JsonElement): JsonElement =
        requireNotNull(review.review(location, field, value)) {
            // Never include rejected values in diagnostics.
            "Profile export requires configuration review at $location/$field"
        }

    private fun fields(values: Map<String, JsonElement>, location: String, field: String) =
        values.mapValues { (key, value) -> reviewed(location, "$field/$key", value) }

    private fun entry(value: ProfileEntry, location: String): ProfileEntry = value.copy(
        config = value.config?.let { reviewed(location, "config", it) },
        inject = fields(value.inject, location, "inject"),
        intercept = fields(value.intercept, location, "intercept"),
        children = value.children?.mapIndexed { index, child -> entry(child, "$location/children/$index") },
    )

    private fun operations(values: List<ProfileOperation>, layer: String): List<ProfileOperation> =
        values.mapIndexed { index, operation ->
            val location = "$layer/patches/$index"
            when (operation) {
                is ProfileOperation.Insert -> operation.copy(
                    entries = operation.entries.mapIndexed { child, value -> entry(value, "$location/entries/$child") },
                )
                is ProfileOperation.Configure -> operation.copy(config = reviewed(location, "config", operation.config))
                is ProfileOperation.Context -> operation.copy(
                    inject = operation.inject?.let { fields(it, location, "inject") },
                    intercept = operation.intercept?.let { fields(it, location, "intercept") },
                )
                else -> operation
            }
        }

    companion object {
        private val json = Json { prettyPrint = true; encodeDefaults = true }

        /** Parsing is not authorization to install packages or activate the imported document. */
        fun decode(text: String): PortableProfileDocument =
            json.decodeFromString<PortableProfileDocument>(text).also { it.validate() }
    }
}
