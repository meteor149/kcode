package ai.meteor.kcode.skill

internal object SkillSelection {
    fun select(originalUserPrompt: String, catalog: SkillCatalog): List<SkillDescriptor> {
        val enabled = catalog.entries.filter { it.enabled }
        val selected = linkedSetOf<String>()
        val result = mutableListOf<SkillDescriptor>()

        LinkedMention.findAll(originalUserPrompt).forEach { match ->
            val name = match.groupValues[1]
            val locator = match.groupValues[2]
            enabled.singleOrNull {
                it.name == name && (it.displayPath == locator || it.mainResource == locator || it.packageId == locator)
            }?.addOnce(selected, result)
        }

        PlainMention.findAll(originalUserPrompt).forEach { match ->
            val name = match.groupValues[1]
            enabled.filter { it.name == name }.singleOrNull()?.addOnce(selected, result)
        }
        return result
    }

    private fun SkillDescriptor.addOnce(keys: MutableSet<String>, output: MutableList<SkillDescriptor>) {
        val key = "${authority.kind}:${authority.id}:$packageId"
        if (keys.add(key)) output += this
    }

    private val LinkedMention = Regex("""\[\$([A-Za-z0-9_.:-]{1,129})]\(([^)\r\n]{1,2048})\)""")
    private val PlainMention = Regex("""(?<![A-Za-z0-9_])\$([A-Za-z][A-Za-z0-9_.:-]{0,128})""")
}
