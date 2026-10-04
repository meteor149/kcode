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
