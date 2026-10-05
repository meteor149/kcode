package ai.meteor.kcode.plugin.searchsettings

import ai.meteor.kcode.settings.AppliedSettingsUpdate
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.tools.search.SearchSettingsPolicy

fun StoredAppSettings.applySearchSettingsUpdate(update: SettingsUpdate, policy: SearchSettingsPolicy): AppliedSettingsUpdate {
    val providers = checkNotNull(policy.providers()) { "Search settings provider is closed" }
    val current = policy.resolve(this)
    val selected = update.values["search-provider"]?.let { raw ->
        providers.firstOrNull { it.id.equals(raw.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException("search-provider must be one of: ${providers.joinToString { it.id }}")
    } ?: providers.firstOrNull { it.id == current.provider }
        ?: throw IllegalArgumentException("Search provider is not enabled")
    require(update.values["search-api-key"] == null || selected.requiresApiKey) {
        "The selected search provider does not use an API key"
    }
    val keys = update.values["search-api-key"]?.let { current.apiKeys + (selected.id to it) } ?: current.apiKeys
    return AppliedSettingsUpdate(
        policy.update(this, current.copy(provider = selected.id, apiKeys = keys)),
        update.suppliedFields.filter { it in setOf("search-provider", "search-api-key") },
    )
}
