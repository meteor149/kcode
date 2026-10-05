package ai.meteor.kcode.plugin.localization

private val placeholder = Regex("%(\\d+)\\$([sd])|%%")

internal fun validateTranslation(template: String) {
    require(!placeholder.replace(template, "").contains('%')) { "Unsupported translation placeholder" }
    placeholder.findAll(template).forEach { match ->
        if (match.value != "%%") require((match.groupValues[1].toIntOrNull() ?: 0) > 0) {
            "Translation argument indexes must be positive"
        }
    }
    translationArguments(template)
}

internal fun translationArguments(template: String): Map<Int, String> = placeholder.findAll(template)
    .filter { it.value != "%%" }
    .groupBy { it.groupValues[1].toInt() }
    .mapValues { (_, matches) ->
        val types = matches.map { it.groupValues[2] }.distinct()
        require(types.size == 1) { "Translation argument types must agree" }
        types.single()
    }

internal fun formatTranslation(template: String, arguments: List<Any>): String =
    ai.meteor.kcode.localization.formatLocalizedText(template, arguments)
