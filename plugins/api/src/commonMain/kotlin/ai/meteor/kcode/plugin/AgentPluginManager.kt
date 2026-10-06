package ai.meteor.kcode.plugin

import ai.meteor.kcode.plugin.api.profiles.ProfileActivationRequest
import ai.meteor.kcode.plugin.api.profiles.ProfilePreview
import ai.meteor.kcode.plugin.api.profiles.ProfileTarget
import ai.meteor.kcode.plugin.api.profiles.ProfileCloneRequest
import ai.meteor.kcode.plugin.api.profiles.ProfileDraftWrite
import ai.meteor.kcode.plugin.api.profiles.ProfileDefinition
import ai.meteor.kcode.plugin.api.profiles.ProfileCatalogue
import ai.meteor.kcode.plugin.api.profiles.ProfileCompositionState
import ai.meteor.kcode.plugin.api.profiles.ProfileCompositionEdit

const val CurrentPluginApiVersion = 72

interface AgentPluginManager {
    suspend fun profileCatalogue(): ProfileCatalogue =
        error("This manager does not support Profile management")
    suspend fun profileDraft(id: String): ProfileDefinition? =
        error("This manager does not support Profile management")
    suspend fun writeProfileDraft(write: ProfileDraftWrite): ProfileCatalogue =
        error("This manager does not support Profile management")
    suspend fun cloneProfile(request: ProfileCloneRequest): ProfileCatalogue =
        error("This manager does not support Profile management")
    suspend fun deleteProfile(id: String, expectedRevision: Long): ProfileCatalogue =
        error("This manager does not support Profile management")
    suspend fun previewProfile(target: ProfileTarget): ProfilePreview =
        error("This manager does not support Profile management")
    suspend fun profileHistory(id: String): List<ProfileCompositionState> =
        error("This manager does not support Profile management")
    suspend fun activateProfile(request: ProfileActivationRequest, cancelActive: Boolean = false): ProfileCompositionState =
        error("This manager does not support Profile management")

    /** Null when this runtime does not use declarative Profiles. */
    suspend fun currentProfile(): ProfileCompositionState? = null

    /** Edit instances of the active Profile through the same managed publication boundary. */
    suspend fun editProfile(edit: ProfileCompositionEdit): ProfileCompositionState =
        error("This manager does not support Profile editing")

    /** Resolve platform packages and commit the complete dependency set as one composition. */
    suspend fun importPackages(packages: List<PluginPackageImport>): Unit =
        error("This manager does not support plugin package archives")

    /** All upserts, removals and enable changes publish together or restore the committed state. */
    suspend fun applyChanges(changes: PluginCompositionChange): Unit =
        error("This manager does not support composition transactions")

    suspend fun install(spec: DynamicPluginSpec)
    suspend fun replace(spec: DynamicPluginSpec)
    suspend fun uninstall(id: String)
    suspend fun installed(): List<DynamicPluginSpec>

    /** Enables or disables a configured plugin, including built-in providers and consumers. */
    suspend fun setEnabled(id: String, enabled: Boolean)
}

data class DynamicPluginSpec(
    val id: String,
    val version: String,
    val entryClass: String,
    val artifactPath: String,
    val sha256: String,
    val dependencies: List<String> = emptyList(),
    val config: Any? = Unit,
    val packageName: String? = null,
    val capabilities: Set<String> = emptySet(),
    val apiVersion: Int = CurrentPluginApiVersion,
    val enabled: Boolean = true,
    val packageInstallation: PluginPackageInstallation? = null,
)
