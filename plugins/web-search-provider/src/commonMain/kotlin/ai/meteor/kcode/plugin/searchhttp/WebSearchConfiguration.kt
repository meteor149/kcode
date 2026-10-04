package ai.meteor.kcode.plugin.searchhttp

import ai.meteor.kcode.tools.search.SearchSettingsConfiguration

/** HTTP routing and legacy credential interpretation belong to this provider. */
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

fun SearchSettingsConfiguration.httpSearchConfiguration(): WebSearchConfiguration = WebSearchConfiguration(
    provider = WebSearchProvider.entries.firstOrNull { it.code == provider }
        ?: throw IllegalArgumentException("Selected search route is not supported by this HTTP provider"),
    brightDataApiKey = apiKeys["bright_data"].orEmpty(),
    exaApiKey = apiKeys["exa"].orEmpty(),
)
