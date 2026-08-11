package ai.meteor.kcode.skill

class SkillRuntime(
    private val providers: List<SkillProvider>,
) {
    suspend fun catalog(forceReload: Boolean = false): SkillCatalog {
        val catalogs = providers.map { it.catalog(forceReload) }
        val entries = linkedMapOf<String, SkillDescriptor>()
        catalogs.forEach { catalog ->
            catalog.entries.forEach { skill ->
                val key = "${skill.authority.kind}:${skill.authority.id}:${skill.packageId}"
                if (key !in entries) entries[key] = skill
            }
        }
        return SkillCatalog(
            entries = entries.values.toList(),
            warnings = catalogs.flatMap(SkillCatalog::warnings),
            generation = catalogs.joinToString("|") { it.generation },
        )
    }

    suspend fun prepareTurn(originalUserPrompt: String): SkillTurnContext {
        val catalog = catalog(forceReload = true)
        val selected = SkillSelection.select(originalUserPrompt, catalog)
        val fragments = selected.mapNotNull { skill ->
            runCatching {
                val result = read(
                    SkillReadRequest(skill.authority, skill.packageId, skill.mainResource),
                )
                renderSkillFragment(skill, result.contents)
            }.getOrNull()
        }
        return SkillTurnContext(
            catalogInstructions = SkillCatalogRenderer.render(catalog),
            selectedSkillFragments = fragments,
            warnings = catalog.warnings,
        )
    }

    suspend fun read(request: SkillReadRequest): SkillReadResult {
        val provider = requireNotNull(providers.singleOrNull { it.authority == request.authority }) {
            "No provider owns the requested skill authority"
        }
        val result = provider.read(request)
        require(result.authority == request.authority) { "Provider changed the skill authority" }
        require(result.packageId == request.packageId) { "Provider changed the skill package" }
        require(result.resourceId == request.resourceId) { "Provider changed the skill resource" }
        return result
    }

    private fun renderSkillFragment(skill: SkillDescriptor, contents: String): String = buildString {
        appendLine("<skill>")
        appendLine("<name>${xml(skill.name.take(256))}</name>")
        appendLine("<path>${xml(skill.mainResource.take(1_024))}</path>")
        appendLine(truncateUtf8(contents, SkillLimits.MaxPromptBytes))
        append("</skill>")
    }

    private fun truncateUtf8(value: String, limit: Int): String {
        if (value.encodeToByteArray().size <= limit) return value
        var low = 0
        var high = value.length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (value.take(middle).encodeToByteArray().size <= limit) low = middle else high = middle - 1
        }
        return value.take(low)
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

data class SkillTurnContext(
    val catalogInstructions: String,
    val selectedSkillFragments: List<String>,
    val warnings: List<SkillWarning>,
) {
    fun prependTo(userContext: String): String = if (selectedSkillFragments.isEmpty()) {
        userContext
    } else {
        selectedSkillFragments.joinToString("\n\n", postfix = "\n\n$userContext")
    }
}
