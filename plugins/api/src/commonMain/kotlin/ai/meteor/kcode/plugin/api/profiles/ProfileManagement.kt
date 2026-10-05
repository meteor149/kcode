package ai.meteor.kcode.plugin.api.profiles

/** Active runtime ownership and saved selection are separate until an activation commits. */
data class ProfileCatalogue(
    val revision: Long,
    val selectedProfileId: String?,
    val profiles: List<ProfileSummary>,
    val activeProfileId: String? = null,
)

data class ProfileSummary(
    val id: String,
    val displayName: String,
    val hasDraft: Boolean,
    val generation: Long?,
    val draftDisplayName: String? = null,
)

enum class ProfileSource { Committed, Draft, History }

data class ProfileTarget(
    val profileId: String,
    val source: ProfileSource = ProfileSource.Committed,
    val generation: Long? = null,
) {
    fun validate() {
        ProfileDefinition(id = profileId).validate()
        require(if (source == ProfileSource.History) generation != null && generation > 0 else generation == null) {
            "Only a historical target specifies a generation"
        }
    }
}

data class ProfileActivationRequest(val target: ProfileTarget, val expectedRevision: Long)

data class ProfileDraftWrite(
    val definition: ProfileDefinition,
    val expectedRevision: Long,
    val createOnly: Boolean = false,
)

/** Copy composition/code intent into a new draft; business data is never copied. */
data class ProfileCloneRequest(
    val source: ProfileTarget,
    val id: String,
    val expectedRevision: Long,
    val displayName: String = id,
    val dataScope: ProfileDataScope = ProfileDataScope(workspace = "profile"),
)

data class ProfileDiagnostic(val layer: String, val operation: Int, val target: String?, val message: String)
data class ProfileOrigin(val layer: String, val operation: Int)

/** A local effective projection; machine paths are excluded from the portable definition. */
data class ProfilePreview(
    val revision: Long,
    val definition: ProfileDefinition,
    val entries: List<ProfileEntry>,
    val diagnostics: List<ProfileDiagnostic>,
    val origins: Map<String, Map<String, ProfileOrigin>>,
    val packagesVerified: Boolean = false,
)
