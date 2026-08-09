package ai.meteor.kcode.tools.search

enum class WebSearchProvider(val code: String, val requiresApiKey: Boolean) {
    Google("google", false),
    Exa("exa", true),
    BrightData("bright_data", true);

    companion object {
        fun fromCode(code: String): WebSearchProvider = entries.firstOrNull { it.code == code } ?: Google
    }
}

data class WebSearchConfiguration(
    val provider: WebSearchProvider = WebSearchProvider.Google,
    val brightDataApiKey: String = "",
    val exaApiKey: String = "",
)
