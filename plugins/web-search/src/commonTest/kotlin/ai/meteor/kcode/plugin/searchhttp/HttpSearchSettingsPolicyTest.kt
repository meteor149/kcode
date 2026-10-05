package ai.meteor.kcode.plugin.searchhttp

import ai.meteor.kcode.settings.StoredAppSettings
import ai.meteor.kcode.test.LegacySettings
import ai.meteor.kcode.test.copy
import ai.meteor.kcode.test.provider
import ai.meteor.kcode.test.exaSearchApiKey
import ai.meteor.kcode.test.webSearchProvider
import ai.meteor.kcode.tools.search.SearchSettingsConfiguration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HttpSearchSettingsPolicyTest {
    @Test
    fun rawHistoricalRouteSelectionIsFeatureOwnedAndDistinguishesAbsence() {
        val policy = HttpSearchSettingsPolicy()
        assertEquals("google", policy.resolve(StoredAppSettings()).provider)
        val absent = StoredAppSettings(legacyValues = Json.parseToJsonElement(
            """{"webSearchApiKey":"legacy-fixture"}""",
        ) as JsonObject)
        assertEquals("bright_data", policy.resolve(absent).provider)
        val explicit = StoredAppSettings(legacyValues = Json.parseToJsonElement(
            """{"webSearchApiKey":"legacy-fixture","webSearchProvider":"google"}""",
        ) as JsonObject)
        assertEquals("google", policy.resolve(explicit).provider)
        assertEquals(absent.legacyValues, policy.update(absent,
            SearchSettingsConfiguration("google", emptyMap())).legacyValues)
    }

    @Test
    fun legacyCredentialsAndCustomKeysSurviveSelectionAndClearing() {
        val policy = HttpSearchSettingsPolicy()
        val stored = LegacySettings(webSearchProvider = "exa", exaSearchApiKey = "legacy",
            webSearchApiKey = "bright", searchApiKeys = mapOf("custom.route" to "custom"))
        val resolved = policy.resolve(stored)
        assertEquals("legacy", resolved.apiKeys["exa"])
        assertEquals("bright", resolved.apiKeys["bright_data"])
        val updated = policy.update(stored, resolved.copy(provider = "bright_data",
            apiKeys = resolved.apiKeys + ("exa" to "")))
        assertEquals("", policy.resolve(updated).apiKeys["exa"])
        assertEquals("bright", policy.resolve(updated).apiKeys["bright_data"])
        assertEquals("custom", policy.resolve(updated).apiKeys["custom.route"])
        assertEquals("legacy", updated.exaSearchApiKey)
        assertEquals("bright_data", policy.resolve(updated).provider)
        assertEquals("google", policy.resolve(LegacySettings(webSearchProvider = "obsolete")).provider)
        assertFailsWith<IllegalArgumentException> { policy.update(stored, resolved.copy(provider = "unknown")) }

    }

    @Test
    fun namespacedValuesTakePrecedenceAndUnknownFieldsSurviveUpdates() {
        val policy = HttpSearchSettingsPolicy()
        val document = Json.parseToJsonElement(
            """{"provider":"exa","apiKeys":{"exa":"","custom.route":"retained"},"future":[null,{"key.with.dots":false}]}""",
        ) as JsonObject
        val other = Json.parseToJsonElement("""{"secret":"preserve"}""") as JsonObject
        val stored = LegacySettings(exaSearchApiKey = "must-not-return", namespaces = mapOf(
            "feature.web-search" to document,
            "disabled.feature" to other,
        ))
        assertEquals("", policy.resolve(stored).apiKeys["exa"])
        val updated = policy.update(stored, SearchSettingsConfiguration("google", emptyMap()))
        assertEquals(document["future"], updated.namespaces["feature.web-search"]!!["future"])
        assertEquals(other, updated.namespaces["disabled.feature"])
        assertEquals("", policy.resolve(updated).apiKeys["exa"])
        assertEquals("retained", policy.resolve(updated).apiKeys["custom.route"])
        assertEquals("must-not-return", updated.exaSearchApiKey)
    }

    @Test
    fun namespacedSchemaRejectsMalformedOwnedValuesWithoutRewritingUnknownData() {
        val policy = HttpSearchSettingsPolicy()
        for (encoded in listOf("""{"provider":null}""", """{"provider":7}""",
            """{"apiKeys":[]}""", """{"apiKeys":{"exa":null}}""")) {
            val stored = StoredAppSettings(namespaces = mapOf("feature.web-search" to
                (Json.parseToJsonElement(encoded) as JsonObject)))
            assertFailsWith<IllegalArgumentException> { policy.resolve(stored) }
            assertFailsWith<IllegalArgumentException> {
                policy.update(stored, SearchSettingsConfiguration("google", emptyMap()))
            }
        }
    }

    @Test
    fun emptyNamespaceUsesFeatureDefaultsWithoutResurrectingLegacyCredentials() {
        val policy = HttpSearchSettingsPolicy()
        val stored = LegacySettings(webSearchProvider = "exa", exaSearchApiKey = "legacy", namespaces =
            mapOf("feature.web-search" to JsonObject(emptyMap())))
        assertEquals(SearchSettingsConfiguration("google", emptyMap()), policy.resolve(stored))
        val migrated = policy.update(StoredAppSettings(), SearchSettingsConfiguration("google", emptyMap()))
        assertEquals("", migrated.webSearchProvider)
        assertEquals("google", policy.resolve(migrated).provider)
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
