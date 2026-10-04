package ai.meteor.kcode.tools.search

import ai.meteor.kcode.settings.StoredAppSettings

/** Provider-owned metadata; the SDK does not enumerate routes or choose defaults. */
data class SearchProviderOption(
    val id: String,
    val displayName: String,
    val requiresApiKey: Boolean,
)

data class SearchSettingsConfiguration(
    val provider: String,
    val apiKeys: Map<String, String>,
)

interface SearchSettingsPolicy {
    /** Null after withdrawal, so a retiring composition can stop rendering. */
    fun providers(): List<SearchProviderOption>?
    fun resolve(settings: StoredAppSettings): SearchSettingsConfiguration
    fun update(settings: StoredAppSettings, configuration: SearchSettingsConfiguration): StoredAppSettings
}
