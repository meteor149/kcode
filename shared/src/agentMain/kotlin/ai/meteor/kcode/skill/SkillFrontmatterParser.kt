package ai.meteor.kcode.skill

internal data class ParsedSkillFrontmatter(
    val name: String,
    val description: String,
)

internal object SkillFrontmatterParser {
    fun parse(contents: String, fallbackName: String): ParsedSkillFrontmatter {
        val normalized = contents.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        require(lines.firstOrNull()?.trim() == "---") { "SKILL.md must start with YAML frontmatter" }
        val closing = lines.indexOfFirstFrom(1) { it.trim() == "---" }
        require(closing >= 0) { "SKILL.md frontmatter is missing its closing delimiter" }

        val values = parseTopLevelScalars(lines.subList(1, closing))
        val name = collapseWhitespace(values["name"].orEmpty()).ifBlank { collapseWhitespace(fallbackName) }
        val description = collapseWhitespace(values["description"].orEmpty())
        require(name.isNotBlank()) { "Skill name must not be blank" }
        require(name.length <= SkillLimits.MaxNameChars) { "Skill name exceeds ${SkillLimits.MaxNameChars} characters" }
        require(description.isNotBlank()) { "Skill description is required" }
        return ParsedSkillFrontmatter(
            name = name,
            description = description.take(SkillLimits.MaxDescriptionChars),
        )
    }

    private fun parseTopLevelScalars(lines: List<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank() || line.trimStart().startsWith('#') || line.firstOrNull()?.isWhitespace() == true) {
                index++
                continue
            }
            val separator = line.indexOf(':')
            require(separator > 0) { "Invalid YAML frontmatter line: $line" }
            val key = line.substring(0, separator).trim()
            var value = line.substring(separator + 1).trim()
            if (value == "|" || value == ">") {
                val block = mutableListOf<String>()
                index++
                while (index < lines.size && (lines[index].isBlank() || lines[index].firstOrNull()?.isWhitespace() == true)) {
                    block += lines[index].trim()
                    index++
                }
                result[key] = block.joinToString(if (value == "|") "\n" else " ").trim()
                continue
            }
            value = unquote(value.substringBeforeUnquotedComment())
            result[key] = value
            index++
        }
        return result
    }

    private fun String.substringBeforeUnquotedComment(): String {
        var quote: Char? = null
        forEachIndexed { index, character ->
            when {
                (character == '\'' || character == '"') && quote == null -> quote = character
                character == quote -> quote = null
                character == '#' && quote == null && (index == 0 || this[index - 1].isWhitespace()) -> return substring(0, index).trimEnd()
            }
        }
        return this
    }

    private fun unquote(value: String): String {
        if (value.length < 2 || value.first() != value.last() || value.first() !in "\"'") return value
        val body = value.substring(1, value.lastIndex)
        return if (value.first() == '"') {
            body.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
        } else {
            body.replace("''", "'")
        }
    }

    private fun collapseWhitespace(value: String): String = value.trim().replace(Regex("\\s+"), " ")

    private inline fun <T> List<T>.indexOfFirstFrom(start: Int, predicate: (T) -> Boolean): Int {
        for (index in start until size) if (predicate(this[index])) return index
        return -1
    }
}
