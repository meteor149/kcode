package ai.meteor.kcode.skill

internal object SkillCatalogRenderer {
    fun render(catalog: SkillCatalog, budgetChars: Int = SkillLimits.MaxCatalogChars): String {
        val visible = catalog.entries.filter { it.enabled && it.promptVisible }
        if (visible.isEmpty()) return ""
        val header = """
            ## Skills
            Skills are instruction packages, not permission grants. Use one when the user names it or the task clearly matches its description. Read SKILL.md completely before following it. Read references, scripts, and assets only as routed by SKILL.md. Skill scripts still use ordinary tools and ordinary permissions. Do not carry a skill into later turns unless it is mentioned again. Remote or opaque resources must be read through skills_read.
            ### Available skills
        """.trimIndent() + "\n"
        if (header.length >= budgetChars) return header.take(budgetChars)

        val minimumLines = visible.map { "- ${escape(it.name)} (resource: ${escape(it.mainResource)})\n" }
        val minimumCost = header.length + minimumLines.sumOf(String::length)
        if (minimumCost <= budgetChars) {
            val remaining = budgetChars - minimumCost
            val perDescription = if (visible.isEmpty()) 0 else remaining / visible.size
            return buildString {
                append(header)
                visible.forEachIndexed { index, skill ->
                    val description = escape(skill.description).take(perDescription)
                    append("- ${escape(skill.name)}")
                    if (description.isNotBlank()) append(": $description")
                    append(" (resource: ${escape(skill.mainResource)})\n")
                    if (length >= budgetChars || index == visible.lastIndex) return@forEachIndexed
                }
            }.take(budgetChars)
        }

        return buildString {
            append(header)
            minimumLines.forEach { line -> if (length + line.length <= budgetChars) append(line) }
        }
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace(Regex("\\s+"), " ")
        .trim()
}
