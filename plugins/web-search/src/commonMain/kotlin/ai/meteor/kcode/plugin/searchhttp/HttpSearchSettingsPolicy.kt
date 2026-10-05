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
        val stored = SearchSettingsDocument.read(settings)
        val provider = catalog.firstOrNull { it.id == stored.provider } ?: catalog.first()
        return stored.copy(provider = provider.id)
    }

    override fun update(settings: StoredAppSettings, configuration: SearchSettingsConfiguration): StoredAppSettings {
        check(active) { "Search settings provider is closed" }
        require(catalog.any { it.id == configuration.provider }) { "Search provider is not enabled" }
        val keys = SearchSettingsDocument.read(settings).apiKeys + configuration.apiKeys
        return SearchSettingsDocument.write(settings, configuration.copy(apiKeys = keys))
    }

    fun close() { active = false }
}
