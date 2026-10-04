package ai.meteor.kcode.skill

interface SkillRuntime {
    suspend fun catalog(forceReload: Boolean = false): SkillCatalog
    suspend fun prepareTurn(originalUserPrompt: String): SkillTurnContext
    suspend fun read(request: SkillReadRequest): SkillReadResult

}

data class SkillTurnContext(
    val catalogInstructions: String,
    val selectedSkillFragments: List<String>,
    val warnings: List<SkillWarning>,
)
