package ai.meteor.kcode.plugin.skills

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
