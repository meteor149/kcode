package ai.meteor.kcode.plugin.settingscommands

import ai.meteor.kcode.plugin.searchhttp.HttpSearchSettingsPolicy
import ai.meteor.kcode.model.ModelProvider
import ai.meteor.kcode.settings.SettingsUpdate
import ai.meteor.kcode.settings.StoredAppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsUpdateTest {
    private val catalog = ai.meteor.kcode.model.ModelCatalogSnapshot(ModelProvider.entries.mapIndexed { index, provider ->
        ai.meteor.kcode.model.ModelProviderSpec(provider, listOf(ai.meteor.kcode.model.ModelOption(provider,
            when (provider) {
                ModelProvider.DeepSeek -> "deepseek-v4-pro"
                ModelProvider.Anthropic -> "claude-test"
                else -> "test-model"
            }, defaultTemperature = 0.6)), index)
    })
    private fun StoredAppSettings.applySettingsUpdate(update: SettingsUpdate, catalog: ai.meteor.kcode.model.ModelCatalogSnapshot = this@SettingsUpdateTest.catalog) =
        applySettingsUpdate(update, catalog, HttpSearchSettingsPolicy())

    @Test
    fun searchCommandUsesTheCurrentCustomCatalogAndProviderStoragePolicy() {
        val route = "private.search:v2"
        val policy = object : ai.meteor.kcode.tools.search.SearchSettingsPolicy {
            override fun providers() = listOf(ai.meteor.kcode.tools.search.SearchProviderOption(route, "Private Search", true))
            override fun resolve(settings: StoredAppSettings) =
                ai.meteor.kcode.tools.search.SearchSettingsConfiguration(route, settings.searchApiKeys)
            override fun update(settings: StoredAppSettings, configuration: ai.meteor.kcode.tools.search.SearchSettingsConfiguration) =
                settings.copy(webSearchProvider = configuration.provider, searchApiKeys = configuration.apiKeys)
        }
        val previous = StoredAppSettings(webSearchApiKey = "legacy", exaSearchApiKey = "exa",
            searchApiKeys = mapOf("other.route" to "other"))
        val result = previous.applySettingsUpdate(SettingsUpdate(searchProvider = route, searchApiKey = "custom-key"),
            catalog, policy)
        assertEquals(route, result.settings.webSearchProvider)
        assertEquals(mapOf("other.route" to "other", route to "custom-key"), result.settings.searchApiKeys)
        assertEquals("legacy", result.settings.webSearchApiKey)
        assertEquals("exa", result.settings.exaSearchApiKey)
        assertEquals(listOf("search-provider", "search-api-key"), result.changedFields)
        assertFailsWith<IllegalArgumentException> {
            previous.applySettingsUpdate(SettingsUpdate(searchProvider = "google"), catalog, policy)
        }
    }

    @Test
    fun customProviderUsesRegisteredIdAndPreservesOtherApiKeys() {
        val provider = ModelProvider("acme.gateway:v1")
        val customCatalog = ai.meteor.kcode.model.ModelCatalogSnapshot(listOf(
            ai.meteor.kcode.model.ModelProviderSpec(provider, listOf(ai.meteor.kcode.model.ModelOption(provider, "private-model", defaultTemperature = 0.6)), 0,
                displayName = "Acme Gateway"),
        ))
        val old = StoredAppSettings(modelApiKeys = mapOf("OpenAI" to "legacy"))
        val updated = old.applySettingsUpdate(SettingsUpdate(
            modelProvider = provider.id, model = "private-model", modelApiKey = "fixture",
        ), customCatalog).settings
        assertEquals(provider.id, updated.provider)
        assertEquals(mapOf("OpenAI" to "legacy", provider.id to "fixture"), updated.modelApiKeys)
        val searchOnly = updated.applySettingsUpdate(SettingsUpdate(searchProvider = "google"),
            ai.meteor.kcode.model.ModelCatalogSnapshot()).settings
        assertEquals(provider.id, searchOnly.provider)
        assertFailsWith<IllegalArgumentException> {
            updated.applySettingsUpdate(SettingsUpdate(modelApiKey = "late"), ai.meteor.kcode.model.ModelCatalogSnapshot())
        }
    }

    @Test
    fun unloadedProviderCannotBeConfiguredThroughAdb() {
        assertFailsWith<IllegalArgumentException> {
            StoredAppSettings().applySettingsUpdate(SettingsUpdate(modelProvider = "deepseek"),
                ai.meteor.kcode.model.ModelCatalogSnapshot())
        }
    }

    @Test
    fun updatesModelAndSearchSettingsWithoutReplacingUnspecifiedValues() {
        val original = StoredAppSettings(
            modelRegion = "preserved-region",
            language = "en",
        )

        val applied = original.applySettingsUpdate(
            SettingsUpdate(
                modelProvider = "deep_seek",
                model = "deepseek-v4-pro",
                modelApiKey = "model-secret",
                temperature = "0.3",
                searchProvider = "exa",
                searchApiKey = "search-secret",
            ),
        )

        assertEquals(ModelProvider.DeepSeek.name, applied.settings.provider)
        assertEquals("deepseek-v4-pro", applied.settings.modelId)
        assertEquals("model-secret", applied.settings.modelApiKeys[ModelProvider.DeepSeek.name])
        assertEquals(0.3, applied.settings.temperature)
        assertEquals("exa", applied.settings.webSearchProvider)
        assertEquals("search-secret", applied.settings.exaSearchApiKey)
        assertEquals("preserved-region", applied.settings.modelRegion)
        assertEquals("en", applied.settings.language)
        assertTrue("model-api-key" in applied.changedFields)
        assertTrue("search-api-key" in applied.changedFields)
    }

    @Test
    fun changingProviderSelectsItsFirstSupportedModelWhenModelIsOmitted() {
        val updated = StoredAppSettings().applySettingsUpdate(
            SettingsUpdate(modelProvider = "anthropic"),
        ).settings

        assertEquals(ModelProvider.Anthropic.name, updated.provider)
        assertTrue(updated.modelId.startsWith("claude-"))
    }

    @Test
    fun acceptsDocumentedProviderCodes() {
        val providerCodes = listOf(
            "openai",
            "azure_openai",
            "anthropic",
            "google",
            "deepseek",
            "openrouter",
            "bedrock",
            "mistral",
            "alibaba",
            "ollama",
            "glm",
        )

        providerCodes.forEach { providerCode ->
            StoredAppSettings().applySettingsUpdate(
                SettingsUpdate(modelProvider = providerCode),
            )
        }
    }

    @Test
    fun emptyApiKeyClearsOnlyTheSelectedModelProviderKey() {
        val original = StoredAppSettings(
            provider = ModelProvider.OpenAI.name,
            modelApiKeys = mapOf(
                ModelProvider.OpenAI.name to "remove-me",
                ModelProvider.Google.name to "keep-me",
            ),
        )

        val updated = original.applySettingsUpdate(
            SettingsUpdate(modelApiKey = ""),
        ).settings

        assertFalse(ModelProvider.OpenAI.name in updated.modelApiKeys)
        assertEquals("keep-me", updated.modelApiKeys[ModelProvider.Google.name])
    }

    @Test
    fun rejectsAProviderModelMismatchWithoutIncludingTheApiKeyInTheError() {
        val error = assertFailsWith<IllegalArgumentException> {
            StoredAppSettings().applySettingsUpdate(
                SettingsUpdate(
                    modelProvider = "anthropic",
                    model = "gpt-4o-mini",
                    modelApiKey = "must-not-leak",
                ),
            )
        }

        assertFalse("must-not-leak" in error.message.orEmpty())
    }

    @Test
    fun rejectsSearchKeysForGoogleAndOutOfRangeTemperatures() {
        assertFailsWith<IllegalArgumentException> {
            StoredAppSettings().applySettingsUpdate(
                SettingsUpdate(searchProvider = "google", searchApiKey = "unused"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StoredAppSettings().applySettingsUpdate(
                SettingsUpdate(temperature = "1.1"),
            )
        }
    }
}
