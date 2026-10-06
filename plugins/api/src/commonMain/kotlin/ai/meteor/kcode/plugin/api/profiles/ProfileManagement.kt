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

/** Import publishes a new draft only; the exchange document carries no local archive addresses. */
data class ProfilePortableImport(
    val document: String,
    val id: String,
    val expectedRevision: Long,
    val displayName: String = id,
)

/** Temporary local input; callers retain the archive until import returns. Never persisted. */
data class ProfileBundleArchiveReference(val archivePath: String, val sha256: String)

/** Ordered Bundle archives are verified/staged, then published as a new isolated draft. */
data class ProfileBundleImport(
    val archives: List<ProfileBundleArchiveReference>,
    val id: String,
    val expectedRevision: Long,
    val displayName: String = id,
)

/** Only committed or historical recipes have verified package locks suitable for export. */
data class ProfilePortableExport(val target: ProfileTarget, val expectedRevision: Long)

/** Local locator, never persisted. Export locators are borrowed only during the consumer call. */
data class ProfileArchiveReference(val archivePath: String, val sha256: String)

/** Verify code and frozen layers, then create an isolated draft without activation. */
data class ProfileArchiveImport(
    val archive: ProfileArchiveReference,
    val id: String,
    val expectedRevision: Long,
    val displayName: String = id,
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
