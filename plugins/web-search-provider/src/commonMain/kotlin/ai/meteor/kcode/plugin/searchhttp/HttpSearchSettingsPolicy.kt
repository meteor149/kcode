package ai.meteor.kcode.plugin.searchhttp

import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.tools.search.SearchProviderOption
import ai.meteor.kcode.tools.search.SearchSettingsConfiguration
import ai.meteor.kcode.tools.search.SearchSettingsPolicy

/** Built-in routes and legacy storage compatibility are private HTTP provider policy. */
class HttpSearchSettingsPolicy : SearchSettingsPolicy {
    private var active = true
    private val catalog = listOf(
        SearchProviderOption("google", "Google", false),
        SearchProviderOption("exa", "Exa", true),
        SearchProviderOption("bright_data", "Bright Data", true),
    )

    override fun providers(): List<SearchProviderOption>? = if (active) catalog.toList() else null

    override fun resolve(settings: StoredAppSettings): SearchSettingsConfiguration {
        check(active) { "Search settings provider is closed" }
        val provider = catalog.firstOrNull { it.id == settings.webSearchProvider } ?: catalog.first()
        return SearchSettingsConfiguration(provider.id, mapOf(
            "exa" to settings.exaSearchApiKey,
            "bright_data" to settings.webSearchApiKey,
        ) + settings.searchApiKeys)
    }

    override fun update(settings: StoredAppSettings, configuration: SearchSettingsConfiguration): StoredAppSettings {
        check(active) { "Search settings provider is closed" }
        require(catalog.any { it.id == configuration.provider }) { "Search provider is not enabled" }
        val keys = settings.searchApiKeys + configuration.apiKeys
        return settings.copy(
            webSearchProvider = configuration.provider,
            searchApiKeys = keys.toMap(),
            webSearchApiKey = keys["bright_data"] ?: settings.webSearchApiKey,
            exaSearchApiKey = keys["exa"] ?: settings.exaSearchApiKey,
        )
    }

    fun close() { active = false }
}
