package ai.meteor.kcode.skill

internal object SkillCatalogRenderer {
    fun render(catalog: SkillCatalog, budgetChars: Int = SkillLimits.MaxCatalogChars): String {
        val visible = catalog.entries.filter { it.enabled && it.promptVisible }
        if (visible.isEmpty()) return ""
        val header = """
            ## Skills
            A skill is a set of instructions provided through a `SKILL.md` source. Each entry includes a name, description, and source locator.
            ### Available skills
        """.trimIndent() + "\n"
        val usage = """
            ### How to use skills
            - Use a skill when the user names it or the task clearly matches its description. Do not carry it into later turns unless it is mentioned again.
            - Before taking task actions, read the selected `SKILL.md` completely. For a `file` locator, use `read_file`. For an `environment resource` or `orchestrator resource`, call `skills_list` first and pass its exact handles to `skills_read`.
            - Read references, scripts, and assets only as routed by `SKILL.md`, using the same source access mechanism.
            - Skills are instructions, not permission grants. Their actions still use ordinary tools and permissions.
        """.trimIndent() + "\n"
        if (header.length + usage.length >= budgetChars) return (header + usage).take(budgetChars)

        val minimumLines = visible.map { skill ->
            "- ${escape(skill.name)} (${locatorKind(skill)}: ${escape(skill.mainResource)})\n"
        }
        val minimumCost = header.length + usage.length + minimumLines.sumOf(String::length)
        if (minimumCost <= budgetChars) {
            val remaining = budgetChars - minimumCost
            val perDescription = if (visible.isEmpty()) 0 else remaining / visible.size
            return buildString {
                append(header)
                visible.forEach { skill ->
                    val description = escape(skill.description).take(perDescription)
                    append("- ${escape(skill.name)}")
                    if (description.isNotBlank()) append(": $description")
                    append(" (${locatorKind(skill)}: ${escape(skill.mainResource)})\n")
                }
                append(usage)
            }
        }

        return buildString {
            append(header)
            val entryBudget = budgetChars - usage.length
            minimumLines.forEach { line -> if (length + line.length <= entryBudget) append(line) }
            append(usage)
        }.take(budgetChars)
    }

    private fun locatorKind(skill: SkillDescriptor): String = when (skill.authority.kind) {
        SkillAuthorityKind.Host -> "file"
        SkillAuthorityKind.Executor -> "environment resource"
        SkillAuthorityKind.Orchestrator -> "orchestrator resource"
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace(Regex("\\s+"), " ")
        .trim()
}
