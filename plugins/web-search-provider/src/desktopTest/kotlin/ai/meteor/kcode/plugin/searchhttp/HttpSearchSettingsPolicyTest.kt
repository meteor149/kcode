package ai.meteor.kcode.plugin.searchhttp

import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.tools.search.SearchSettingsConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HttpSearchSettingsPolicyTest {
    @Test
    fun legacyCredentialsAndCustomKeysSurviveSelectionAndClearing() {
        val policy = HttpSearchSettingsPolicy()
        val stored = StoredAppSettings(webSearchProvider = "exa", exaSearchApiKey = "legacy",
            webSearchApiKey = "bright", searchApiKeys = mapOf("custom.route" to "custom"))
        val resolved = policy.resolve(stored)
        assertEquals("legacy", resolved.apiKeys["exa"])
        assertEquals("bright", resolved.apiKeys["bright_data"])
        val updated = policy.update(stored, resolved.copy(provider = "bright_data",
            apiKeys = resolved.apiKeys + ("exa" to "")))
        assertEquals("", updated.exaSearchApiKey)
        assertEquals("bright", updated.webSearchApiKey)
        assertEquals("custom", updated.searchApiKeys["custom.route"])
        assertEquals("bright_data", policy.resolve(updated).provider)
        assertEquals("google", policy.resolve(StoredAppSettings(webSearchProvider = "obsolete")).provider)
        assertFailsWith<IllegalArgumentException> { policy.update(stored, resolved.copy(provider = "unknown")) }
        assertFailsWith<IllegalArgumentException> {
            SearchSettingsConfiguration("custom.route", emptyMap()).httpSearchConfiguration()
        }
    }

    @Test
    fun withdrawnCatalogAndStrictReferencesCannotKeepConfiguring() {
        val policy = HttpSearchSettingsPolicy()
        val snapshot = policy.resolve(StoredAppSettings())
        policy.close()
        assertNull(policy.providers())
        assertFailsWith<IllegalStateException> { policy.resolve(StoredAppSettings()) }
        assertFailsWith<IllegalStateException> { policy.update(StoredAppSettings(), snapshot) }
    }
}
