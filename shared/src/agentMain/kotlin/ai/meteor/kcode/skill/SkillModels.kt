package ai.meteor.kcode.skill

data class SkillAuthority(
    val kind: SkillAuthorityKind,
    val id: String,
)

enum class SkillAuthorityKind {
    Host,
    Executor,
    Orchestrator,
}

enum class SkillScope {
    Repo,
    User,
    System,
    Admin,
}

data class SkillDescriptor(
    val authority: SkillAuthority,
    val packageId: String,
    val mainResource: String,
    val name: String,
    val description: String,
    val scope: SkillScope,
    val displayPath: String,
    val enabled: Boolean = true,
    val promptVisible: Boolean = true,
)

data class SkillWarning(
    val path: String,
    val message: String,
)

data class SkillCatalog(
    val entries: List<SkillDescriptor>,
    val warnings: List<SkillWarning> = emptyList(),
    val generation: String,
)

data class SkillReadRequest(
    val authority: SkillAuthority,
    val packageId: String,
    val resourceId: String,
)

data class SkillReadResult(
    val authority: SkillAuthority,
    val packageId: String,
    val resourceId: String,
    val contents: String,
    val external: Boolean,
)

interface SkillProvider {
    val authority: SkillAuthority

    suspend fun catalog(forceReload: Boolean = false): SkillCatalog

    suspend fun read(request: SkillReadRequest): SkillReadResult
}

internal object SkillLimits {
    const val MaxNameChars = 64
    const val MaxDescriptionChars = 1_024
    const val MaxPromptBytes = 8_000
    const val MaxHandleBytes = 2_048
    const val MaxCatalogChars = 8_000
    const val MaxDepth = 6
    const val MaxDirectories = 2_000
    const val MaxEntries = 20_000
    const val MaxSkills = 1_000
}
