package ai.meteor.kcode.session

internal fun conversationTitle(prompt: String): String {
    val normalized = prompt.trim().replace(Regex("\\s+"), " ")
    return when {
        normalized.isBlank() -> ""
        normalized.length <= 24 -> normalized
        else -> normalized.take(24).trimEnd() + "…"
    }
}
